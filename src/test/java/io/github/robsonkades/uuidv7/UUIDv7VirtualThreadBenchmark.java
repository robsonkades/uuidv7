package io.github.robsonkades.uuidv7;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntToLongFunction;

/**
 * End-to-end short-lived virtual-thread workloads; requires Java 21+ to run.
 * Each reported operation is one task, including submission, scheduling,
 * generation, checksum consumption and joining. Multiply tasks/s by idsPerTask
 * for UUID/s; these are not isolated generator latency measurements.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class UUIDv7VirtualThreadBenchmark {

    private static final int TASKS = 64;

    @Param({"1", "10", "1000"})
    public int idsPerTask;

    private ExecutorService executor;

    @Setup(Level.Trial)
    public void setup() throws ReflectiveOperationException {
        executor = (ExecutorService) Executors.class
                .getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("Virtual tasks did not finish");
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(TASKS)
    public long fast() throws Exception {
        return runTasks(count -> {
            long sum = 0;
            for (int i = 0; i < count; i++) {
                var uuid = UUIDv7.randomUUID();
                sum += uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
            }
            return sum;
        });
    }

    @Benchmark
    @OperationsPerInvocation(TASKS)
    public long secureMonotonic() throws Exception {
        return runTasks(count -> {
            long sum = 0;
            for (int i = 0; i < count; i++) {
                var uuid = UUIDv7.secureMonotonicUUID();
                sum += uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
            }
            return sum;
        });
    }

    @Benchmark
    @OperationsPerInvocation(TASKS)
    public long secureUnordered() throws Exception {
        return runTasks(count -> {
            long sum = 0;
            for (int i = 0; i < count; i++) {
                var uuid = UUIDv7.secureUnorderedUUID();
                sum += uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
            }
            return sum;
        });
    }

    @Benchmark
    @OperationsPerInvocation(TASKS)
    public long fillLong() throws Exception {
        return runTasks(count -> {
            long[] pairs = new long[2 * count];
            UUIDv7.fill(pairs, 0, count);
            long sum = 0;
            for (int i = 0; i < pairs.length; i += 2) {
                sum += pairs[i] ^ pairs[i + 1];
            }
            return sum;
        });
    }

    @Benchmark
    @OperationsPerInvocation(TASKS)
    public long fillByte() throws Exception {
        return runTasks(count -> {
            byte[] bytes = new byte[16 * count];
            UUIDv7.fill(bytes, 0, count);
            long sum = 0;
            for (int i = 0; i < bytes.length; i += 16) {
                sum += (long) UUIDv7.LONG_BE.get(bytes, i) ^ (long) UUIDv7.LONG_BE.get(bytes, i + 8);
            }
            return sum;
        });
    }

    private long runTasks(IntToLongFunction generate) throws Exception {
        List<Future<Long>> futures = new ArrayList<>(TASKS);
        for (int i = 0; i < TASKS; i++) {
            futures.add(executor.submit(() -> generate.applyAsLong(idsPerTask)));
        }
        long sum = 0;
        for (Future<Long> future : futures) {
            sum += future.get();
        }
        return sum;
    }
}
