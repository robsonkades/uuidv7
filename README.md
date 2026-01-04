# UUIDv7

[![Maven Central](https://img.shields.io/maven-central/v/io.github.robsonkades/uuidv7)](https://search.maven.org/artifact/io.github.robsonkades/uuidv7)  
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)  
[![Build Status](https://github.com/robsonkades/uuid/actions/workflows/maven.yml/badge.svg)](https://github.com/robsonkades/uuid/actions)

**UUIDv7** is a small, high-performance Java library for generating [UUID version 7](https://www.rfc-editor.org/rfc/rfc9562#name-uuid-version-7) identifiers, combining a 48-bit millisecond timestamp with 74 bits of high-quality entropy. Unlike the standard `java.util.UUID.randomUUID()`, this implementation:

- Uses **ThreadLocalRandom** (non-blocking, fast PRNG) instead of `SecureRandom`.
- Avoids intermediate `byte[16]` allocations and `ByteBuffer` overhead.
- Produces about **50× the throughput** of a naïve implementation while still conforming to the RFC 9562 layout.
- Minimizes per-UUID garbage (≈32 B per call vs. ≈176 B in a typical version).

UUIDv7 is ideal for distributed systems, microservices, databases, and high-throughput applications that need time-sortable unique identifiers without sacrificing performance.

---

### Table of Contents

1. [Features](#features)
2. [Quick Start](#quick-start)
3. [Performance Comparison](#performance-comparison)
4. [License](#license)
5. [Contributing](#contributing)

---

## Features

- **Fully RFC-compliant** UUID v7 format (48 bits timestamp, 4 bits version, 2 bits variant, 74 bits random).
- **Extremely low overhead**: only one `UUID` object allocation per call (≈32 bytes).
- **High throughput**: bench tests show ~200 million UUID/s on modern hardware.
- **Thread-safe**: uses `ThreadLocalRandom` internally.
- **Zero external dependencies** beyond the JDK.
- **Java 8+ compatible** (tested up through Java 17/21).

---

## Quick Start

### Maven Dependency

```xml
<dependency>
  <groupId>io.github.robsonkades</groupId>
  <artifactId>uuidv7</artifactId>
  <version>1.0.1</version>
</dependency>
```

After adding the dependency, run:

```mvn clean install```

# Performance Comparison & License

## Performance Comparison

Below is a summary of benchmark results comparing the naïve UUIDv7 implementation (using `SecureRandom` + `ByteBuffer`) with this optimized implementation (using `ThreadLocalRandom` + bitwise assembly). All measurements were taken on a modern 8-core CPU (Intel/AMD), Java 17, Linux SSD, with JMH settings: 5 warmup iterations, 5 measurement iterations, 2 forks, single-threaded throughput mode.

| Implementation                    | Throughput (ops/ms) | Bytes Allocated per UUID (B/op) | GC Alloc Rate (MB/s) |
|-----------------------------------|---------------------|---------------------------------|----------------------|
| **SecureRandom + ByteBuffer**     | ~4 725 (≈4.7 M/s)   | ~176 B                          | ~793 MB/s            |
| **Optimized (ThreadLocalRandom)** | ~227 174 (≈227 M/s) | ~32 B                           | ~6 931 MB/s          |

- **Throughput**
   - *Naïve*: ~4 725 ops/ms → ≈4.7 million UUIDs per second.
   - *Optimized*: ~227 174 ops/ms → ≈227 million UUIDs per second.
   - Result: **≈50× faster** throughput in the optimized version.

- **Bytes Allocated per UUID**
   - *Naïve*: ~176 bytes of garbage (creates `byte[16]` + `ByteBuffer` + one `UUID`).
   - *Optimized*: ~32 bytes of garbage (only one `UUID` object).
   - Result: **≈82% fewer bytes** allocated per call.

- **GC Allocation Rate (MB/s)**
   - *Naïve*: ~793 MB allocated per second.
   - *Optimized*: ~6 931 MB allocated per second (because it generates far more UUIDs).
   - Although the optimized version allocates more in absolute MB/s, it does **not** increase per-UUID allocation—thus total throughput is massively higher and GC pauses, while more frequent, remain a small fraction of total runtime.

### Observations

1. **Throughput Gain**  
   The optimized code leverages non-blocking `ThreadLocalRandom` and direct 64-bit/bitwise assembly, eliminating array and buffer overhead. The result is order-of-magnitude faster UUID generation, making it suitable for high-throughput, low-latency systems.

2. **Garbage Generation**  
   By reducing each call to a single 32-byte `UUID` allocation, the optimized approach minimizes per-call garbage. This keeps pause times short even when producing hundreds of millions of UUIDs per second.

3. **GC Behavior**
   - The naïve version triggers ~54 collections, spending ~35 ms total in GC during the measured period.
   - The optimized version triggers ~268 collections, spending ~215 ms total in GC.
   - In both cases, GC overhead is negligible relative to total execution time, but the optimized version still wins because it produces far more UUIDs in the same wall-clock time.


### Benchmark

| Benchmark                                           | Version                                   | Mode  | Cnt | Score       | Error       | Units   |
|----------------------------------------------------|-------------------------------------------|-------|-----|-------------|-------------|---------|
| UUIDV7Benchmark.benchGenerate                      | java.util.UUID                            | thrpt | 20  | 4261.388    | ± 39.561    | ops/ms  |
| UUIDV7Benchmark.benchGenerate:gc.alloc.rate        | java.util.UUID                            | thrpt | 20  | 975.192     | ± 9.078     | MB/sec  |
| UUIDV7Benchmark.benchGenerate:gc.alloc.rate.norm   | java.util.UUID                            | thrpt | 20  | 240.002     | ± 0.001     | B/op    |
| UUIDV7Benchmark.benchGenerate:gc.count             | java.util.UUID                            | thrpt | 20  | 66.000      |             | counts  |
| UUIDV7Benchmark.benchGenerate:gc.time              | java.util.UUID                            | thrpt | 20  | 32.000      |             | ms      |
| UUIDV7Benchmark.benchGenerate                      | io.github.robsonkades:uuidv7              | thrpt | 20  | 148359.782  | ± 1159.173  | ops/ms  |
| UUIDV7Benchmark.benchGenerate:gc.alloc.rate        | io.github.robsonkades:uuidv7              | thrpt | 20  | 4526.684    | ± 35.304    | MB/sec  |
| UUIDV7Benchmark.benchGenerate:gc.alloc.rate.norm   | io.github.robsonkades:uuidv7              | thrpt | 20  | 32.000      | ± 0.001     | B/op    |
| UUIDV7Benchmark.benchGenerate:gc.count             | io.github.robsonkades:uuidv7              | thrpt | 20  | 174.000     |             | counts  |
| UUIDV7Benchmark.benchGenerate:gc.time              | io.github.robsonkades:uuidv7              | thrpt | 20  | 105.000     |             | ms      |
| UUIDV7Benchmark.benchGenerate                      | com.github.f4b6a3:uuid-creator            | thrpt | 20  | 69965.993   | ± 117.158   | ops/ms  |
| UUIDV7Benchmark.benchGenerate:gc.alloc.rate        | com.github.f4b6a3:uuid-creator            | thrpt | 20  | 2134.816    | ± 3.575     | MB/sec  |
| UUIDV7Benchmark.benchGenerate:gc.alloc.rate.norm   | com.github.f4b6a3:uuid-creator            | thrpt | 20  | 32.000      | ± 0.001     | B/op    |
| UUIDV7Benchmark.benchGenerate:gc.count             | com.github.f4b6a3:uuid-creator            | thrpt | 20  | 120.000     |             | counts  |
| UUIDV7Benchmark.benchGenerate:gc.time              | com.github.f4b6a3:uuid-creator            | thrpt | 20  | 69.000      |             | ms      |
| UUIDV7Benchmark.benchGenerate                      | com.fasterxml.uuid:java-uuid-generator    | thrpt | 20  | 4074.375    | ± 27.374    | ops/ms  |
| UUIDV7Benchmark.benchGenerate:gc.alloc.rate        | com.fasterxml.uuid:java-uuid-generator    | thrpt | 20  | 1118.863    | ± 7.527     | MB/sec  |
| UUIDV7Benchmark.benchGenerate:gc.alloc.rate.norm   | com.fasterxml.uuid:java-uuid-generator    | thrpt | 20  | 288.002     | ± 0.001     | B/op    |
| UUIDV7Benchmark.benchGenerate:gc.count             | com.fasterxml.uuid:java-uuid-generator    | thrpt | 20  | 76.000      |             | counts  |
| UUIDV7Benchmark.benchGenerate:gc.time              | com.fasterxml.uuid:java-uuid-generator    | thrpt | 20  | 41.000      |             | ms      |

---

## License

This project is licensed under the MIT License. See [LICENSE](./LICENSE) for details.

## Contributing

Contributions, bug reports, and feature requests are always welcome! Please see [CONTRIBUTING.md](./CONTRIBUTING.md) for more details.
