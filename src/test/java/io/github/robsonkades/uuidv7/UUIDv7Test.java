package io.github.robsonkades.uuidv7;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
}
