package io.github.robsonkades.uuidv7;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.f4b6a3.uuid.UuidCreator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@OutputTimeUnit(TimeUnit.SECONDS)
public class UUIDv7Benchmark {

    private final NaiveSecureRandomByteBufferV7 naive = new NaiveSecureRandomByteBufferV7();
    private TimeBasedEpochGenerator jugEpoch;

    @Setup(Level.Trial)
    public void setup() {
        jugEpoch = Generators.timeBasedEpochGenerator();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public UUID optimizedFast() {
        return UUIDv7.randomUUID();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public UUID optimizedSecure() {
        return UUIDv7.secureRandomUUID();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public UUID uuidCreator() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public UUID uuidCreatorFast() {
        return UuidCreator.getTimeOrderedEpochFast();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public UUID jugEpoch() {
        return jugEpoch.generate();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public UUID legacyThreadLocalRandom() {
        return LegacyThreadLocalRandomV7.next();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public UUID naiveSecureRandomByteBuffer() {
        return naive.next();
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public UUID latencyOptimizedFast() {
        return UUIDv7.randomUUID();
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public UUID latencyUuidCreatorFast() {
        return UuidCreator.getTimeOrderedEpochFast();
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public UUID latencyJugEpoch() {
        return jugEpoch.generate();
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public UUID latencyNaiveSecureRandomByteBuffer() {
        return naive.next();
    }

    static final class LegacyThreadLocalRandomV7 {

        private LegacyThreadLocalRandomV7() {
        }

        static UUID next() {
            long ts48 = System.currentTimeMillis() & UUIDv7.TIMESTAMP_MASK;
            long random64 = ThreadLocalRandom.current().nextLong();
            int random32 = ThreadLocalRandom.current().nextInt();

            long msb = (ts48 << 16) | 0x7000L | ((random64 >>> 52) & UUIDv7.RAND_A_MASK);
            long randB = ((random64 & 0x000FFFFFFFFFFFFFL) << 10) | ((random32 >>> 22) & 0x3FFL);
            long lsb = 0x8000000000000000L | randB;

            return new UUID(msb, lsb);
        }
    }

    static final class NaiveSecureRandomByteBufferV7 {

        private final SecureRandom secureRandom = new SecureRandom();

        UUID next() {
            byte[] bytes = new byte[16];
            secureRandom.nextBytes(bytes);

            long unixTsMs = Math.max(0L, System.currentTimeMillis());
            bytes[0] = (byte) (unixTsMs >>> 40);
            bytes[1] = (byte) (unixTsMs >>> 32);
            bytes[2] = (byte) (unixTsMs >>> 24);
            bytes[3] = (byte) (unixTsMs >>> 16);
            bytes[4] = (byte) (unixTsMs >>> 8);
            bytes[5] = (byte) unixTsMs;
            bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70);
            bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
    }
}
