package io.github.robsonkades.uuidv7;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UUIDv7Test {

    @Test
    void shouldMatchRfc9562TestVector() {
        UUID uuid = UUIDv7.assemble(1_645_557_742_000L, 0x0CC3, 0x18C4DC0C0C07398FL);

        assertEquals(UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"), uuid);
        assertEquals(7, uuid.version());
        assertEquals(2, uuid.variant());
    }

    @Test
    void shouldRoundTripPackedFields() {
        UUID uuid = UUIDv7.assemble(0x0123456789ABL, 0x0FED, 0x0123456789ABCDEFL);

        assertEquals(0x0123456789ABL, UUIDv7.extractUnixTimestamp(uuid));
        assertEquals(0x0FED, UUIDv7.extractRandA(uuid));
        assertEquals(0x0123456789ABCDEFL, UUIDv7.extractRandB(uuid));
    }

    @Test
    void shouldSetVersionAndVariantBits() {
        UUID uuid = UUIDv7.randomUUID();

        assertEquals(7, uuid.version());
        assertEquals(2, uuid.variant());
    }

    @Test
    void shouldGenerateTimestampCloseToWallClock() {
        long before = System.currentTimeMillis();
        UUID uuid = UUIDv7.randomUUID();
        long after = System.currentTimeMillis();

        long timestamp = UUIDv7.extractUnixTimestamp(uuid);

        assertTrue(timestamp >= Math.max(0L, before));
        assertTrue(timestamp <= after + 1L);
    }

    @Test
    void shouldBeMonotonicForRepeatedCallsWithinSameMillisecond() {
        long unixTsMs = 1_700_000_000_000L;
        UUIDv7.GeneratorState state = new UUIDv7.GeneratorState(0L, unixTsMs, 0x0001, 0L);

        UUID previous = UUIDv7.assemble(unixTsMs, 0x0001, 0L);

        for (int i = 0; i < 4_096; i++) {
            UUID current = state.next(unixTsMs);
            assertEquals(unixTsMs, UUIDv7.extractUnixTimestamp(current));
            assertTrue(UUIDv7.compareUnsigned(previous, current) < 0);
            previous = current;
        }
    }

    @Test
    void shouldRemainMonotonicWhenClockMovesBackwards() {
        UUID first = UUIDv7.assemble(10_000L, 0x0010, 123L);
        UUIDv7.GeneratorState state = new UUIDv7.GeneratorState(0L, 10_000L, 0x0010, 123L);
        UUID second = state.next(9_999L);

        assertEquals(10_000L, UUIDv7.extractUnixTimestamp(second));
        assertTrue(UUIDv7.compareUnsigned(first, second) < 0);
    }

    @Test
    void shouldAdvanceTimestampOnCounterOverflow() {
        UUID previous = UUIDv7.assemble(55L, 0x0123, UUIDv7.RAND_B_MASK);
        UUIDv7.GeneratorState state = new UUIDv7.GeneratorState(0xDEADBEEFL, 55L, 0x0123, UUIDv7.RAND_B_MASK);

        UUID current = state.next(55L);

        assertEquals(56L, UUIDv7.extractUnixTimestamp(current));
        assertTrue(UUIDv7.compareUnsigned(previous, current) < 0);
    }

    @Test
    void secureGeneratorShouldProduceValidUuidV7Values() {
        UUID first = UUIDv7.secureRandomUUID();
        UUID second = UUIDv7.secureRandomUUID();

        assertEquals(7, first.version());
        assertEquals(2, first.variant());
        assertEquals(7, second.version());
        assertEquals(2, second.variant());
        assertNotEquals(first, second);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRemainCollisionFreeUnderConcurrentLoad() throws Exception {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        int iterationsPerThread = 20_000;
        int expected = threads * iterationsPerThread;

        Set<UUID> seen = ConcurrentHashMap.newKeySet(expected);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < iterationsPerThread; j++) {
                        seen.add(UUIDv7.randomUUID());
                    }
                    return null;
                });
            }

            start.countDown();

            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(expected, seen.size());
    }

    @Test
    void shouldSeedCounterWithHeadroomPerRfc9562Section62() {
        UUIDv7.GeneratorState state = new UUIDv7.GeneratorState(0x1234_5678_9ABC_DEF0L);

        for (long unixTsMs = 1_000L; unixTsMs < 1_064L; unixTsMs++) {
            UUID uuid = state.next(unixTsMs);
            assertEquals(unixTsMs, UUIDv7.extractUnixTimestamp(uuid));
            assertTrue(UUIDv7.extractRandB(uuid) <= UUIDv7.RAND_B_SEED_MASK,
                    "freshly seeded rand_b must keep its two most significant bits clear");
        }
    }

    @Test
    void shouldFillLongArrayWithValidMonotonicUuids() {
        int count = 100;
        long sentinel = 0xAAAAAAAAAAAAAAAAL;
        long[] dst = new long[2 * count + 4];
        java.util.Arrays.fill(dst, sentinel);

        UUIDv7.fill(dst, 2, count);

        UUID previous = null;
        for (int i = 0; i < count; i++) {
            UUID current = new UUID(dst[2 + 2 * i], dst[3 + 2 * i]);
            assertEquals(7, current.version());
            assertEquals(2, current.variant());
            if (previous != null) {
                assertTrue(UUIDv7.compareUnsigned(previous, current) < 0);
            }
            previous = current;
        }

        assertEquals(sentinel, dst[0]);
        assertEquals(sentinel, dst[1]);
        assertEquals(sentinel, dst[dst.length - 2]);
        assertEquals(sentinel, dst[dst.length - 1]);
    }

    @Test
    void shouldFillByteArrayWithRfcBigEndianLayout() {
        int count = 8;
        byte[] dst = new byte[16 * count];

        UUIDv7.fill(dst, 0, count);

        ByteBuffer buffer = ByteBuffer.wrap(dst);
        long before = System.currentTimeMillis();
        UUID previous = null;
        for (int i = 0; i < count; i++) {
            UUID current = new UUID(buffer.getLong(), buffer.getLong());
            assertEquals(7, current.version());
            assertEquals(2, current.variant());
            assertTrue(UUIDv7.extractUnixTimestamp(current) <= before + 1_000L);
            if (previous != null) {
                assertTrue(UUIDv7.compareUnsigned(previous, current) < 0);
            }
            previous = current;
        }
    }

    @Test
    void shouldRejectOutOfBoundsFill() {
        assertThrows(IndexOutOfBoundsException.class, () -> UUIDv7.fill(new long[4], 0, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> UUIDv7.fill(new long[4], 3, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> UUIDv7.fill(new long[4], 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> UUIDv7.fill(new byte[32], 17, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> UUIDv7.fill(new byte[32], 0, 3));
    }

    @Test
    void shouldFormatCanonicalStringRepresentation() {
        String value = UUIDv7.randomUUIDString();

        UUID parsed = UUID.fromString(value);
        assertEquals(7, parsed.version());
        assertEquals(2, parsed.variant());
        assertEquals(value, parsed.toString());
    }

    @Test
    void canonicalStringShouldMatchJdkFormatting() {
        UUID uuid = UUIDv7.assemble(1_645_557_742_000L, 0x0CC3, 0x18C4DC0C0C07398FL);

        assertEquals(uuid.toString(),
                UUIDv7.toCanonicalString(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()));
    }

    @Test
    void generatorInstanceShouldProduceStrictlyIncreasingValues() {
        UUIDv7Generator generator = UUIDv7Generator.create();

        UUID previous = generator.next();
        assertEquals(7, previous.version());
        assertEquals(2, previous.variant());

        for (int i = 0; i < 10_000; i++) {
            UUID current = generator.next();
            assertTrue(UUIDv7.compareUnsigned(previous, current) < 0);
            previous = current;
        }

        String value = generator.nextString();
        UUID parsed = UUID.fromString(value);
        assertEquals(7, parsed.version());
        assertTrue(UUIDv7.compareUnsigned(previous, parsed) < 0);
    }

    @Test
    void generatorInstanceShouldFillBuffersWithValidValues() {
        UUIDv7Generator generator = UUIDv7Generator.create();

        long[] longs = new long[2 * 16];
        generator.fill(longs, 0, 16);
        for (int i = 0; i < 16; i++) {
            UUID uuid = new UUID(longs[2 * i], longs[2 * i + 1]);
            assertEquals(7, uuid.version());
            assertEquals(2, uuid.variant());
        }

        byte[] bytes = new byte[16 * 16];
        generator.fill(bytes, 0, 16);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < 16; i++) {
            UUID uuid = new UUID(buffer.getLong(), buffer.getLong());
            assertEquals(7, uuid.version());
            assertEquals(2, uuid.variant());
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldGenerateValidUuidsOnVirtualThreadsWhenSupported() throws Exception {
        Object builder;
        Method start;
        try {
            builder = Thread.class.getMethod("ofVirtual").invoke(null);
            start = Class.forName("java.lang.Thread$Builder").getMethod("start", Runnable.class);
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            Assumptions.assumeTrue(false, "virtual threads are not supported on this JVM");
            return;
        }

        int threads = 8;
        int iterationsPerThread = 5_000;
        Set<UUID> seen = ConcurrentHashMap.newKeySet(threads * iterationsPerThread);
        AtomicBoolean monotonicPerThread = new AtomicBoolean(true);

        List<Thread> started = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            Runnable task = () -> {
                UUID previous = null;
                for (int j = 0; j < iterationsPerThread; j++) {
                    UUID current = UUIDv7.randomUUID();
                    if (current.version() != 7 || current.variant() != 2) {
                        monotonicPerThread.set(false);
                    }
                    if (previous != null && UUIDv7.compareUnsigned(previous, current) >= 0) {
                        monotonicPerThread.set(false);
                    }
                    previous = current;
                    seen.add(current);
                }
            };
            started.add((Thread) start.invoke(builder, task));
        }
        for (Thread thread : started) {
            thread.join();
        }

        assertTrue(monotonicPerThread.get(), "values seen by a single virtual thread must be strictly increasing");
        assertEquals(threads * iterationsPerThread, seen.size());

        Runnable secureTask = () -> {
            UUID secure = UUIDv7.secureRandomUUID();
            if (secure.version() != 7 || secure.variant() != 2) {
                monotonicPerThread.set(false);
            }
        };
        Thread secureThread = (Thread) start.invoke(builder, secureTask);
        secureThread.join();
        assertTrue(monotonicPerThread.get());
    }
}
