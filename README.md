# UUIDv7

[![Maven Central](https://img.shields.io/maven-central/v/io.github.robsonkades/uuidv7)](https://search.maven.org/artifact/io.github.robsonkades/uuidv7)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Build Status](https://github.com/robsonkades/uuid/actions/workflows/maven.yml/badge.svg)](https://github.com/robsonkades/uuid/actions)

`uuidv7` is a small, dependency-free Java library for generating RFC 9562 UUID version 7 identifiers.
It is designed for production workloads that care about time ordering, throughput, low allocation pressure, and multicore scalability.

The library exposes two generation modes:

- `UUIDv7.randomUUID()` is the default high-throughput API. It uses per-thread state, avoids shared hot-path contention, and preserves monotonic ordering for values produced by the same thread within the same millisecond.
- `UUIDv7.secureRandomUUID()` is the stronger-entropy API. It uses `SecureRandom` to seed and advance the UUID state, which improves unpredictability at a much higher per-call cost.

## Features

- RFC 9562 UUIDv7 layout
- 48-bit Unix epoch timestamp in milliseconds
- Correct version and variant bits
- Clock rollback handling
- Same-millisecond monotonic sequencing
- No runtime dependencies beyond the JDK
- Java 17+
- Fast path allocates only the returned `UUID` object

## Quick Start

### Maven

```xml
<dependency>
  <groupId>io.github.robsonkades</groupId>
  <artifactId>uuidv7</artifactId>
  <version>1.0.1</version>
</dependency>
```

### Usage

```java
import io.github.robsonkades.uuidv7.UUIDv7;

import java.util.UUID;

UUID fast = UUIDv7.randomUUID();
UUID secure = UUIDv7.secureRandomUUID();
```

### Which API should I use?

- Use `UUIDv7.randomUUID()` for database keys, event IDs, trace IDs, queue message IDs, and other high-volume identifiers.
- Use `UUIDv7.secureRandomUUID()` only if stronger entropy in the UUID payload matters more than raw throughput.
- Do not use UUIDv7 as a secret token format. UUIDv7 embeds creation time by design.

## Design Notes

The implementation is intentionally optimized around the real bottlenecks of UUID generation on the JVM:

- The fast generator keeps mutable state in `ThreadLocal` storage, which avoids global locks, atomics, and cache-line ping-pong under contention.
- UUID bit packing is done directly into two `long` values. No `byte[16]`, `ByteBuffer`, boxing, or formatting work happens on the hot path.
- If the wall clock moves backwards, the generator reuses the last logical timestamp and advances monotonic state instead of emitting a smaller UUID.
- If the same-millisecond counter space is exhausted, the generator advances to the next logical millisecond rather than knowingly wrapping and returning duplicates.
- The secure generator keeps the same state machine, but uses `SecureRandom` to seed new timestamps and to advance same-millisecond state with positive random increments.

## Benchmark Methodology

Benchmarks were run with JMH 1.37 on this repository's current implementation and benchmark suite.

- JVM: GraalVM CE 25.0.2
- Hardware: 24 logical CPUs
- JVM args: `-Xms1g -Xmx1g`
- Warmup: 5 iterations x 1 second
- Measurement: 5 iterations x 1 second
- Forks: 2
- Benchmark source: `src/test/java/io/github/robsonkades/uuidv7/UUIDv7Benchmark.java`

Comparison targets:

- `optimizedFast`: `UUIDv7.randomUUID()`
- `optimizedSecure`: `UUIDv7.secureRandomUUID()`
- `legacyThreadLocalRandom`: previous no-state baseline
- `naiveSecureRandomByteBuffer`: `SecureRandom` + `byte[16]` + `ByteBuffer`
- `uuidCreator`: `com.github.f4b6a3:uuid-creator`
- `uuidCreatorFast`: `com.github.f4b6a3:uuid-creator` fast mode
- `jugEpoch`: `com.fasterxml.uuid:java-uuid-generator`

These results are point-in-time measurements, not universal constants. Different JVMs, CPU topologies, entropy providers, and OS timer behavior will change the exact numbers.

## Benchmark Results

### Single-Thread Throughput

| Implementation | Throughput | Allocation |
|---|---:|---:|
| `optimizedFast` | 250.3 M ops/s | 32.0 B/op |
| `legacyThreadLocalRandom` | 279.1 M ops/s | 32.0 B/op |
| `jugEpoch` | 69.4 M ops/s | 32.0 B/op |
| `uuidCreatorFast` | 35.7 M ops/s | 64.0 B/op |
| `naiveSecureRandomByteBuffer` | 3.75 M ops/s | 208.0 B/op |
| `uuidCreator` | 3.59 M ops/s | 231.3 B/op |
| `optimizedSecure` | 1.86 M ops/s | 368.2 B/op |

### 24-Thread Throughput

| Implementation | Throughput | Allocation |
|---|---:|---:|
| `optimizedFast` | 1.069 B ops/s | 32.0 B/op |
| `legacyThreadLocalRandom` | 1.081 B ops/s | 32.0 B/op |
| `uuidCreatorFast` | 27.8 M ops/s | 64.3 B/op |
| `optimizedSecure` | 21.8 M ops/s | 368.4 B/op |
| `jugEpoch` | 10.6 M ops/s | 32.6 B/op |
| `uuidCreator` | 2.44 M ops/s | 225.0 B/op |
| `naiveSecureRandomByteBuffer` | 2.23 M ops/s | 208.0 B/op |

### Single-Thread Sample Latency

| Implementation | Mean | p95 | p99 | p99.9 |
|---|---:|---:|---:|---:|
| `optimizedFast` | 32.1 ns | 100 ns | 100 ns | 221.3 ns |
| `jugEpoch` | 38.6 ns | 100 ns | 100 ns | 300.0 ns |
| `uuidCreatorFast` | 54.4 ns | 100 ns | 100 ns | 400.0 ns |
| `naiveSecureRandomByteBuffer` | 328.7 ns | 300 ns | 400 ns | 9486.7 ns |

## Reading the Results

- `optimizedFast` stays close to the bare `ThreadLocalRandom` baseline in single-thread mode while adding rollback handling and same-thread monotonic sequencing.
- Under 24-thread load, `optimizedFast` scales essentially linearly and remains near the legacy baseline, which is the main design goal of the implementation.
- `optimizedFast` is about 67x faster than the naive `SecureRandom + ByteBuffer` baseline in single-thread throughput and about 480x faster at 24 threads.
- `optimizedFast` is about 7x faster than `uuidCreatorFast` in single-thread throughput and about 38x faster at 24 threads.
- `jugEpoch` and `uuidCreator` show a large collapse under contention, which strongly suggests shared internal coordination costs.
- `optimizedSecure` is intentionally much slower than `optimizedFast`. That is the expected trade-off for stronger entropy and random monotonic increments.

## License

This project is licensed under the MIT License. See [LICENSE](./LICENSE) for details.

## Contributing

Contributions, bug reports, and feature requests are welcome. See [CONTRIBUTING.md](./CONTRIBUTING.md).
