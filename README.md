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
- `UUIDv7.randomUUIDString()` — canonical 36-character string form without materializing an intermediate `UUID`.
- `UUIDv7Generator` — a caller-owned, single-threaded generator instance for code that already confines work per thread (event loops, actors, partitions). Not thread-safe by design.
- `UUIDv7.secureRandomUUID()` — the stronger-entropy API, backed by `SecureRandom` with buffered entropy.

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

starts a daemon thread that refreshes a volatile timestamp every ~0.5 ms, replacing the `System.currentTimeMillis()` call on the hot path with a plain volatile read. The monotonic state machine absorbs the 1–2 ms jitter, so ordering and uniqueness are unaffected; only the embedded creation timestamp may lag the true wall clock by that amount. The win is largest on platforms where reading the wall clock is expensive (e.g. Linux `CLOCK_REALTIME` via vDSO); on Windows the default clock is already cheap.

### Which API should I use?

- Use `UUIDv7.randomUUID()` for database keys, event IDs, trace IDs, queue message IDs, and other high-volume identifiers.
- Use `UUIDv7.fill(...)` when generating identifiers in batches (bulk inserts, event batching, ID pre-allocation) — it is ~6x faster per UUID and allocates nothing.
- Use `UUIDv7Generator` when your architecture already confines work to a thread and you want to own the generator state placement.
- Use `UUIDv7.secureRandomUUID()` only if stronger entropy in the UUID payload matters more than raw throughput.
- Do not use UUIDv7 as a secret token format. UUIDv7 embeds creation time by design.

## Design Notes

The implementation is intentionally optimized around the real bottlenecks of UUID generation on the JVM:

- The fast generator keeps mutable state in `ThreadLocal` storage, which avoids global locks, atomics, and cache-line ping-pong under contention. Virtual threads are detected (via a JIT-constant method-handle probe, so the check is free) and routed to a striped, lock-guarded pool instead, bounding state churn and `SecureRandom` construction.
- UUID bit packing is done directly into two `long` values. No `byte[16]`, `ByteBuffer`, boxing, or formatting work happens on the hot path.
- The monotonic `rand_b` counter is reseeded each millisecond with its two most significant bits clear, following the counter-seeding guidance of RFC 9562 Section 6.2. Every millisecond therefore starts with at least 2^60 increments of headroom, making counter rollover unreachable in practice.
- If the wall clock moves backwards, the generator reuses the last logical timestamp and advances monotonic state instead of emitting a smaller UUID.
- Generator state objects carry cache-line padding on both sides, so a compacting GC can never relocate two threads' states onto the same cache line (no false sharing regardless of heap layout).
- The secure generator keeps the same state machine, but draws bits from `SecureRandom` in buffered 512-byte blocks, amortizing the provider cost and its internal lock over dozens of UUIDs instead of paying a full `nextLong()` round trip per UUID.
- On GraalVM native image, the package is initialized at run time (metadata embedded in the JAR), so random seeds are never baked into the image heap at build time.

## Benchmark Methodology

Benchmarks were run with JMH 1.37 on this repository's current implementation and benchmark suite.

- JVM: Temurin OpenJDK 25.0.3
- OS: Windows 11
- Hardware: Intel Core i7-13700K (16 cores / 24 threads, hybrid P+E topology)
- JVM args: `-Xms1g -Xmx1g`
- Warmup: 5 iterations x 1 second
- Measurement: 5 iterations x 1 second
- Forks: 2
- Contended benchmarks: 8 threads (`@Threads(8)`)
- Benchmark source: `src/test/java/io/github/robsonkades/uuidv7/UUIDv7Benchmark.java`

Comparison targets:

- `optimizedFast`: `UUIDv7.randomUUID()`
- `optimizedFillLongBatch` / `optimizedFillByteBatch`: `UUIDv7.fill(...)`, 256 UUIDs per batch
- `optimizedGeneratorInstance`: `UUIDv7Generator.next()`
- `optimizedFastString`: `UUIDv7.randomUUIDString()`
- `optimizedSecure`: `UUIDv7.secureRandomUUID()`
- `legacyThreadLocalRandom`: bare `ThreadLocalRandom` baseline with no monotonicity or rollback handling
- `naiveSecureRandomByteBuffer`: `SecureRandom` + `byte[16]` + `ByteBuffer`
- `uuidCreator` / `uuidCreatorFast`: `com.github.f4b6a3:uuid-creator` 6.1.1
- `jugEpoch`: `com.fasterxml.uuid:java-uuid-generator` 5.2.0

These results are point-in-time measurements, not universal constants. Different JVMs, CPU topologies, entropy providers, and OS timer behavior will change the exact numbers. In particular, Windows reads the wall clock very cheaply; on Linux the optional cached clock mode recovers most of the (larger) vDSO clock cost.

## Benchmark Results

### Single-Thread Throughput

| Implementation | Throughput | ns/UUID |
|---|---:|---:|
| `optimizedFillLongBatch` | 1.473 B ops/s | 0.68 |
| `optimizedFillByteBatch` | 1.177 B ops/s | 0.85 |
| `optimizedFast` | 248.4 M ops/s | 4.03 |
| `legacyThreadLocalRandom` | 248.1 M ops/s | 4.03 |
| `optimizedGeneratorInstance` | 247.7 M ops/s | 4.04 |
| `optimizedSecure` | 121.4 M ops/s | 8.24 |
| `jugEpoch` | 74.2 M ops/s | 13.5 |
| `optimizedFastString` | 49.0 M ops/s | 20.4 |
| `uuidCreatorFast` | 33.8 M ops/s | 29.6 |
| `naiveSecureRandomByteBuffer` | 3.99 M ops/s | 251 |
| `uuidCreator` | 3.67 M ops/s | 272 |

The bulk `fill` APIs allocate nothing; the unit APIs allocate only the returned `UUID` (32 B/op) or `String`.

### Contended Throughput (8 threads)

| Implementation | Throughput | vs single-thread |
|---|---:|---:|
| `contendedOptimizedFast` | 1.053 B ops/s | 4.2x (scales) |
| `contendedUuidCreatorFast` | 24.9 M ops/s | 0.74x (regresses) |
| `contendedJugEpoch` | 8.8 M ops/s | 0.12x (collapses) |

### Single-Thread Sample Latency

| Implementation | Mean | p99 | p99.9 | p99.99 | Max |
|---|---:|---:|---:|---:|---:|
| `optimizedFast` | 32.5 ns | ≤100 ns | 200 ns | 18.8 µs | 133 µs |
| `jugEpoch` | 45.1 ns | ≤100 ns | 600 ns | 14.4 µs | 869 µs |
| `uuidCreatorFast` | 62.0 ns | ≤100 ns | 900 ns | 32.6 µs | 407 µs |
| `naiveSecureRandomByteBuffer` | 298.2 ns | 400 ns | 9.9 µs | 60.9 µs | 1.18 ms |

Sample-time percentiles at or below 100 ns are limited by the ~100 ns timer granularity on Windows (that is also why p50 reports as 0). The p99.99 outliers on all implementations are OS scheduling and safepoint noise, not generator behavior.

## Reading the Results

- `optimizedFast` is statistically identical to the bare `ThreadLocalRandom` baseline (248.4 M vs 248.1 M ops/s): the monotonic sequencing, rollback handling, and RFC 9562 counter-seeding machinery cost nothing measurable.
- The bulk `fill(long[])` API reaches **~0.68 ns per UUID** — about 5.9x the unit API — by reading the clock once per batch and keeping the generator state in registers, with zero allocations.
- `optimizedSecure` now runs at **121.4 M ops/s**, roughly 65x faster than the previous unbuffered implementation of this same library (1.86 M ops/s), thanks to 512-byte buffered entropy. The secure path is now only ~2x slower than the fast path, ~30x faster than the naive `SecureRandom` baseline, and ~33x faster than `uuidCreator`'s default generator.
- Under 8-thread load, `optimizedFast` reaches 1.05 B ops/s while both comparison libraries drop **below their own single-thread numbers** (`uuidCreatorFast` 33.8 M → 24.9 M; `jugEpoch` 74.2 M → 8.8 M), which is the signature of a shared global sequencer. Per-thread scaling here is bounded by the hybrid P+E core topology, not by coordination: there is no shared state between platform threads.
- `optimizedGeneratorInstance` ties `optimizedFast` on this JVM: `ThreadLocal.get()` on modern JDKs is below measurement noise on this hardware. The instance API's value is architectural (caller-owned state placement, no thread-local map dependency) rather than raw single-thread speed.
- Tail latency is the best of the group: p99.9 of 200 ns vs 600 ns (`jugEpoch`) and 900 ns (`uuidCreatorFast`).
- `optimizedFastString` shows that string formatting (~20 ns/op) dominates UUID generation (~4 ns/op) — if you only need the string form, `randomUUIDString()` still saves the intermediate `UUID` allocation over `randomUUID().toString()`.

## Running the Benchmarks

```bash
# Build the executable benchmark jar
mvn -Pbenchmarks -DskipTests package

# Full suite (~15-20 minutes)
java -jar target/benchmarks.jar UUIDv7Benchmark

# Single benchmark, allocation profiling, contended group
java -jar target/benchmarks.jar 'UUIDv7Benchmark.optimizedFast$'
java -jar target/benchmarks.jar UUIDv7Benchmark -prof gc
java -jar target/benchmarks.jar UUIDv7Benchmark.contended

# With the cached clock enabled
java -jar target/benchmarks.jar 'UUIDv7Benchmark.optimizedFast$' \
  -jvmArgs "-Xms1g -Xmx1g -Dio.github.robsonkades.uuidv7.cachedClock=true"
```

## License

This project is licensed under the MIT License. See [LICENSE](./LICENSE) for details.

## Contributing

Contributions, bug reports, and feature requests are welcome. See [CONTRIBUTING.md](./CONTRIBUTING.md).
