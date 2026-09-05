# UUIDv7

[![Maven Central](https://img.shields.io/maven-central/v/io.github.robsonkades/uuidv7)](https://search.maven.org/artifact/io.github.robsonkades/uuidv7)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Build Status](https://github.com/robsonkades/uuidv7/actions/workflows/maven.yml/badge.svg)](https://github.com/robsonkades/uuidv7/actions)
[![javadoc](https://javadoc.io/badge2/io.github.robsonkades/uuidv7/javadoc.svg)](https://javadoc.io/doc/io.github.robsonkades/uuidv7)

`uuidv7` is a small, dependency-free Java library for generating RFC 9562 UUID version 7 identifiers.
It is designed for production workloads that care about time ordering, throughput, low allocation pressure, and multicore scalability.

The library exposes the following generation APIs:

- `UUIDv7.randomUUID()` — the default high-throughput API. Per-thread state, no shared hot-path contention, monotonic ordering for values produced by the same thread.
- `UUIDv7.fill(long[]/byte[], offset, count)` — bulk generation with **zero allocations**. The clock is read once per batch; this is the fastest API (sub-nanosecond per UUID).
- `UUIDv7.randomUUIDString()` — canonical 36-character string form through the JDK's optimized UUID formatter.
- `UUIDv7Generator` — a caller-owned, single-threaded generator instance for code that already confines work per thread (event loops, actors, partitions). Not thread-safe by design.
- `UUIDv7.secureMonotonicUUID()` — secure seeds and increments of 1–1024, ordered per calling thread.
- `UUIDv7.secureRandomUUID()` — compatibility alias for `secureMonotonicUUID()`, sharing the same sequence.
- `UUIDv7.secureUnorderedUUID()` — 74 fresh random payload bits per UUID from buffered `SecureRandom`, without monotonic sequencing.

The new explicitly named secure APIs are available in this source revision; older published versions may not include them.

Bulk allocation claims concern warmed-up generation into caller-owned buffers. Initial state creation and contended lock bookkeeping can allocate; bulk generation creates no per-UUID objects or temporary batch arrays.

## Features

- RFC 9562 UUIDv7 layout
- 48-bit Unix epoch timestamp in milliseconds
- Correct version and variant bits
- Clock rollback handling
- Same-millisecond monotonic sequencing with guaranteed counter headroom (RFC 9562 §6.2 counter seeding)
- Bulk APIs (`long[]` pairs or RFC big-endian `byte[]`) with zero allocations
- Virtual-thread aware: virtual threads are transparently routed to a striped generator pool (Java 21+), so per-request state churn and per-thread `SecureRandom` construction never happen
- Optional cached clock mode for clock-read-bound platforms (opt-in system property)
- GraalVM native image ready: run-time initialization metadata is embedded in the JAR, so image builds never freeze the random seed state
- No runtime dependencies beyond the JDK
- Java 17+
- Fast path allocates only the returned `UUID` object; bulk paths allocate nothing

## Quick Start

### Maven

```xml
<dependency>
  <groupId>io.github.robsonkades</groupId>
  <artifactId>uuidv7</artifactId>
  <version>1.1.0</version>
</dependency>
```

### Usage

```java
import io.github.robsonkades.uuidv7.UUIDv7;
import io.github.robsonkades.uuidv7.UUIDv7Generator;

import java.util.UUID;

// Default APIs — always thread-safe (platform and virtual threads)
UUID fast = UUIDv7.randomUUID();
String key = UUIDv7.randomUUIDString();
UUID secure = UUIDv7.secureRandomUUID();
UUID monotonic = UUIDv7.secureMonotonicUUID(); // same sequence as secureRandomUUID()
UUID unordered = UUIDv7.secureUnorderedUUID(); // fresh random payload on every call

// Bulk generation — zero allocations, clock read once per batch
long[] pairs = new long[2 * 1024];
UUIDv7.fill(pairs, 0, 1024);            // (msb, lsb) pairs

byte[] raw = new byte[16 * 1024];
UUIDv7.fill(raw, 0, 1024);              // RFC 9562 big-endian binary layout

// Instance API — for thread-confined code; NOT thread-safe
UUIDv7Generator generator = UUIDv7Generator.create();
UUID id = generator.next();
```

### Cached clock (opt-in)

Since the UUIDv7 timestamp has millisecond granularity, the wall clock only needs to be observed about once per millisecond. Enabling:

```
-Dio.github.robsonkades.uuidv7.cachedClock=true
```

starts a daemon thread that attempts to refresh a volatile timestamp about every 0.5 ms, replacing the `System.currentTimeMillis()` call on the hot path with a volatile read. Scheduling and JVM pauses can delay updates: **there is no fixed upper bound on timestamp staleness**. Monotonic generators remain ordered when the clock freezes or moves backwards. The unordered secure API follows the observed clock directly. This option is disabled by default; measure both throughput and timestamp error on the target OS before enabling it.

### Which API should I use?

- Use `UUIDv7.randomUUID()` for database keys, event IDs, trace IDs, queue message IDs, and other high-volume identifiers.
- Use `UUIDv7.fill(...)` when generating identifiers in batches (bulk inserts, event batching, ID pre-allocation). It is the highest-throughput path; this revision measured 15–17x the unit API per UUID with 256-value batches and reused buffers.
- Use `UUIDv7Generator` when your architecture already confines work to a thread and you want to own the generator state placement.
- Use `UUIDv7.secureMonotonicUUID()` for secure-entropy seeds with per-thread ordering. Given a value, the next output from the same state at the same logical timestamp has at most 1024 candidates unless the counter overflows. Secure seeding does not make that sequence cryptographically unpredictable.
- Use `UUIDv7.secureUnorderedUUID()` when each payload should be freshly randomized. Values are not guaranteed to increase within a millisecond or across clock rollback. These calls do not advance the secure monotonic sequence.
- Do not use UUIDv7 as a secret token format. UUIDv7 embeds creation time by design.

## Design Notes

The implementation is intentionally optimized around the real bottlenecks of UUID generation on the JVM:

- The fast generator keeps mutable state in `ThreadLocal` storage, which avoids global locks, atomics, and cache-line ping-pong under contention. Virtual threads are detected (via a JIT-constant method-handle probe, so the check is free) and routed to a striped, lock-guarded pool instead, bounding state churn and `SecureRandom` construction.
- UUID bit packing is done directly into two `long` values. No `byte[16]`, `ByteBuffer`, boxing, or formatting work happens on the hot path.
- The fast seed sequence is initialized from `SecureRandom` once per loaded library copy. Each generator receives a mixed sequence value; steady-state fast generation does not call `SecureRandom`. Initialization can incur provider startup costs. Cross-process uniqueness remains probabilistic, and cloning a running process snapshot also clones its generator state.
- Bulk generation reserves a consecutive counter interval, then writes a constant MSB and increasing LSBs. Virtual threads release the stripe lock before writing the normal batch. Counter rollover uses a locked scalar fallback. A batch exhausting the maximum timestamp can leave a written prefix; emitted or reserved values are not rolled back.
- The monotonic `rand_b` counter is reseeded each millisecond with its two most significant bits clear, following the counter-seeding guidance of RFC 9562 Section 6.2. Every millisecond therefore starts with at least 2^60 counter positions of headroom; secure increments consume 1–1024 positions each.
- If the wall clock moves backwards, the generator reuses the last logical timestamp and advances monotonic state instead of emitting a smaller UUID.
- Generator state objects carry cache-line padding on both sides, so a compacting GC can never relocate two threads' states onto the same cache line (no false sharing regardless of heap layout).
- Both secure APIs share a buffered entropy source with 512-byte blocks per thread or stripe. The monotonic API consumes bounded random increments; the unordered API consumes fresh random fields per UUID, including all 62 bits of `rand_b`.
- On GraalVM native image, the package is initialized at run time (metadata embedded in the JAR), so random seeds are never baked into the image heap at build time.

## Benchmark Methodology

The results below were measured from this source revision on September 5, 2026. They describe this machine and JVM; they are not universal capacity claims.

- Source: this revision, benchmarked against base commit `db6c80d502d3cfd4291e9ce882dade58d52b85d9`
- JVM: Temurin OpenJDK 25.0.4.1+1-LTS
- OS: Windows 11 Pro 10.0.26200
- Hardware: Intel Core i7-13700K (16 cores / 24 threads, hybrid P+E topology)
- JVM args: `-Xms1g -Xmx1g`
- Warmup: 5 iterations x 1 second
- Measurement: 5 iterations x 1 second
- Forks: 2
- Profiler: JMH `-prof gc`
- Contended benchmarks: 8 threads (`@Threads(8)`)
- Main benchmark source: `src/test/java/io/github/robsonkades/uuidv7/UUIDv7Benchmark.java`
- Virtual-thread benchmark source: `src/test/java/io/github/robsonkades/uuidv7/UUIDv7VirtualThreadBenchmark.java`

Comparison targets:

- `optimizedFast`: `UUIDv7.randomUUID()`
- `optimizedFillLongBatch` / `optimizedFillByteBatch`: `UUIDv7.fill(...)`, 256 UUIDs per batch
- `optimizedGeneratorInstance`: `UUIDv7Generator.next()`
- `optimizedFastString`: `UUIDv7.randomUUIDString()`
- `fastUuidToString`: `UUIDv7.randomUUID().toString()`
- `optimizedSecure`: `UUIDv7.secureRandomUUID()` / `secureMonotonicUUID()`
- `secureUnordered`: `UUIDv7.secureUnorderedUUID()`
- `legacyThreadLocalRandom`: bare `ThreadLocalRandom` baseline with no monotonicity or rollback handling
- `naiveSecureRandomByteBuffer`: `SecureRandom` + `byte[16]` + `ByteBuffer`
- `uuidCreator` / `uuidCreatorFast`: `com.github.f4b6a3:uuid-creator` 6.1.1
- `jugEpoch`: `com.fasterxml.uuid:java-uuid-generator` 5.2.0

The `±` values below are JMH's 99.9% confidence-interval half-widths. Different JVMs, CPU topologies, entropy providers, power states, and OS timer behavior will change the results. The default system clock was used; cached-clock mode was disabled.

## Benchmark Results

### Single-Thread Throughput

| Implementation | Throughput | Amortized cost | Allocation |
|---|---:|---:|---:|
| `optimizedFillLongBatch` | 4.449 B ± 29.8 M UUID/s | 0.225 ns/UUID | ≈0 B/UUID |
| `optimizedFillByteBatch` | 4.030 B ± 33.4 M UUID/s | 0.248 ns/UUID | ≈0 B/UUID |
| `optimizedFast` | 260.1 M ± 3.9 M UUID/s | 3.84 ns/UUID | 32.00 B/UUID |
| `optimizedGeneratorInstance` | 257.6 M ± 2.9 M UUID/s | 3.88 ns/UUID | 32.00 B/UUID |
| `legacyThreadLocalRandom` | 172.7 M ± 117.3 M UUID/s | 5.79 ns/UUID | 32.00 B/UUID |
| `optimizedFastString` | 123.7 M ± 1.1 M strings/s | 8.08 ns/string | 80.00 B/string |
| `fastUuidToString` | 123.6 M ± 2.2 M strings/s | 8.09 ns/string | 80.00 B/string |
| `optimizedSecure` | 118.4 M ± 6.0 M UUID/s | 8.45 ns/UUID | 32.78 B/UUID |
| `jugEpoch` | 74.5 M ± 0.7 M UUID/s | 13.42 ns/UUID | 32.00 B/UUID |
| `uuidCreatorFast` | 34.5 M ± 1.1 M UUID/s | 29.02 ns/UUID | 80.00 B/UUID |
| `secureUnordered` | 27.3 M ± 0.4 M UUID/s | 36.59 ns/UUID | 38.25 B/UUID |
| `naiveSecureRandomByteBuffer` | 4.06 M ± 0.10 M UUID/s | 246.6 ns/UUID | 240.00 B/UUID |
| `uuidCreator` | 3.70 M ± 0.07 M UUID/s | 270.6 ns/UUID | 293.65 B/UUID |

The bulk costs are amortized across 256 identifiers per invocation into reused caller-owned arrays. They are not individual-call latency. JMH measured their normalized allocation below its displayed resolution (`≈10⁻⁶ B/UUID`). `optimizedSecure` and `secureUnordered` provide different contracts: the former uses bounded monotonic increments, while the latter consumes fresh random payload bits for every UUID.

### Contended Throughput (8 threads)

| Implementation | Aggregate throughput | vs single-thread | Allocation |
|---|---:|---:|---:|
| `contendedOptimizedFast` | 1.018 B ± 31.5 M UUID/s | 3.91x | 32.00 B/UUID |
| `contendedUuidCreatorFast` | 25.0 M ± 1.6 M UUID/s | 0.73x | 96.29 B/UUID |
| `contendedJugEpoch` | 9.8 M ± 4.5 M UUID/s | 0.13x | 32.28 B/UUID |

These are aggregate eight-thread results. They show the observed scaling on this hybrid CPU but do not isolate the cause of each competitor's behavior.

### Single-Thread Sample Latency

| Implementation | Mean | p99 | p99.9 | p99.99 | Max |
|---|---:|---:|---:|---:|---:|
| `optimizedFast` | 29.2 ns | 100 ns | 200 ns | 12.3 µs | 138 µs |
| `uuidCreatorFast` | 60.3 ns | 100 ns | 1.0 µs | 27.4 µs | 283 µs |
| `jugEpoch` | 163.2 ns | 100 ns | 500 ns | 29.5 µs | 34.5 ms |
| `naiveSecureRandomByteBuffer` | 569.6 ns | 500 ns | 49.7 µs | 225.7 µs | 2.25 ms |

Sample-time values at or below 100 ns have limited resolution on this Windows run. The maximum values include external runtime and OS effects; no JFR, safepoint log, or scheduler trace was collected to attribute them.

### Short-Lived Virtual Threads

Each JMH invocation submits 64 new virtual-thread tasks and joins them. The score is tasks/s because `@OperationsPerInvocation(64)` is used. `UUID/s` is derived as `tasks/s × idsPerTask`; allocation includes task scheduling, futures, result collection and, for bulk methods, the destination array.

| API | IDs/task | Tasks/s | Derived UUID/s | Allocation/task |
|---|---:|---:|---:|---:|
| `fast` | 1 | 1.711 M ± 0.070 M | 1.711 M | 429.2 B |
| `fast` | 10 | 1.696 M ± 0.040 M | 17.0 M | 430.6 B |
| `fast` | 1,000 | 344.1 K ± 29.2 K | 344.1 M | 1,000.5 B |
| `fillByte` | 1 | 1.737 M ± 0.061 M | 1.737 M | 461.1 B |
| `fillByte` | 10 | 1.691 M ± 0.036 M | 16.9 M | 605.1 B |
| `fillByte` | 1,000 | 1.414 M ± 0.095 M | 1.414 B | 16,445.6 B |
| `fillLong` | 1 | 1.623 M ± 0.049 M | 1.623 M | 461.2 B |
| `fillLong` | 10 | 1.626 M ± 0.041 M | 16.3 M | 605.3 B |
| `fillLong` | 1,000 | 1.324 M ± 0.013 M | 1.324 B | 16,445.5 B |
| `secureMonotonic` | 1 | 1.618 M ± 0.025 M | 1.618 M | 430.7 B |
| `secureMonotonic` | 10 | 1.579 M ± 0.064 M | 15.8 M | 440.7 B |
| `secureMonotonic` | 1,000 | 320.7 K ± 7.4 K | 320.7 M | 1,576.5 B |
| `secureUnordered` | 1 | 1.618 M ± 0.022 M | 1.618 M | 436.4 B |
| `secureUnordered` | 10 | 1.519 M ± 0.016 M | 15.2 M | 516.0 B |
| `secureUnordered` | 1,000 | 157.2 K ± 20.6 K | 157.2 M | 8,141.1 B |

For one or ten identifiers, virtual-thread lifecycle dominates the task. At 1,000 identifiers, bulk reservation moves much more work through each task; the reported bulk allocation is primarily the caller-owned 16,000-byte destination array plus task infrastructure.

### Focused Before/After Measurements

The batch comparison used an archived JAR from `db6c80d` and the candidate JAR, both on the environment above with two forks, three warmup iterations, three measurement iterations and `-prof gc`. A second run with five warmup and five measurement iterations confirmed the direction, although absolute scores varied with CPU placement.

| Change | Before | After | Observed ratio |
|---|---:|---:|---:|
| `fill(byte[])`, 256 UUIDs | 1.122 B UUID/s | 2.461 B UUID/s | 2.19x |
| `fill(long[])`, 256 UUIDs | 1.471 B UUID/s | 2.728 B UUID/s | 1.86x |
| Manual string formatter → JDK formatter | 49.5 M strings/s, 136 B/op | 122.5 M strings/s, 80 B/op | 2.47x throughput, 41% fewer bytes/op |

The before/after processes ran sequentially rather than in randomized interleaved order. Treat the ratios as local evidence for the optimization direction, not as guaranteed production improvements.

## Reading the Results

- The default static API and caller-owned generator have overlapping throughput intervals on this run: 260.1 M ± 3.9 M versus 257.6 M ± 2.9 M UUID/s.
- `randomUUIDString()` and `randomUUID().toString()` also overlap: 123.7 M ± 1.1 M versus 123.6 M ± 2.2 M strings/s, both at 80 B/op. Delegating to the JDK removed the former manual formatter's measured penalty on this JDK.
- Secure monotonic generation reached 118.4 M UUID/s; secure unordered generation reached 27.3 M UUID/s. The throughput difference reflects fresh entropy consumption and different ordering guarantees.
- The eight-thread fast benchmark reached 1.018 B UUID/s aggregate, 3.91x its single-thread score on this 16-core hybrid processor.
- Batch generation is the highest-throughput path when callers can reuse buffers. Its sub-nanosecond figures are amortized loop costs and must not be presented as request latency.

## Running the Benchmarks

```bash
# Build the executable benchmark jar
mvn -Pbenchmarks -DskipTests package

# Main suite with allocation profile and machine-readable output
java -jar target/benchmarks.jar \
  '^io\.github\.robsonkades\.uuidv7\.UUIDv7Benchmark\..*$' \
  -prof gc -rf json -rff target/main-jmh.json

# Virtual-thread suite (Java 21+)
java -jar target/benchmarks.jar \
  '^io\.github\.robsonkades\.uuidv7\.UUIDv7VirtualThreadBenchmark\..*$' \
  -prof gc -rf json -rff target/virtual-thread-jmh.json

# Single benchmark, allocation profiling, contended group
java -jar target/benchmarks.jar 'UUIDv7Benchmark.optimizedFast$'
java -jar target/benchmarks.jar UUIDv7Benchmark -prof gc
java -jar target/benchmarks.jar UUIDv7Benchmark.contended

# With the cached clock enabled
java -jar target/benchmarks.jar 'UUIDv7Benchmark.optimizedFast$' \
  -jvmArgs "-Xms1g -Xmx1g -Dio.github.robsonkades.uuidv7.cachedClock=true"

# Direct formatting versus the JDK UUID.toString() path, with allocation evidence
java -jar target/benchmarks.jar 'UUIDv7Benchmark.(optimizedFastString|fastUuidToString)$' \
  -prof gc -rf json -rff target/strings.json

# Java 21+ only: short-lived virtual threads, 64 tasks submitted per invocation
java -jar target/benchmarks.jar UUIDv7VirtualThreadBenchmark \
  -p idsPerTask=1,10,1000 -prof gc -rf json -rff target/virtual-threads.json
```

The virtual-thread benchmarks report **tasks/s**, with `idsPerTask` UUIDs per task. They include task creation, scheduling, generation, checksum consumption and joining; the bulk variants also allocate their destination arrays per task. Multiply by `idsPerTask` to obtain UUID/s. They do not measure isolated generator latency or claim zero allocation for an entire virtual-thread request. The original batch microbenchmarks reuse destination arrays and report UUID/s through `@OperationsPerInvocation(256)`.

For comparisons, preserve the commit, full JDK build, OS/CPU details, clock setting, JMH JSON, confidence intervals and `gc.alloc.rate.norm`. Run one benchmark process at a time on an otherwise idle machine. The existing CI tests Java 17/21/25 on Linux; performance conclusions also need measurements on each deployment platform.

## License

This project is licensed under the MIT License. See [LICENSE](./LICENSE) for details.

## Contributing

Contributions, bug reports, and feature requests are welcome. See [CONTRIBUTING.md](./CONTRIBUTING.md).
