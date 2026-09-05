package io.github.robsonkades.uuidv7;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UUIDv7StateTest {

    @Test
    void batchesMatchScalarSequenceAcrossClockChangesAndCounterRollover() {
        for (long counter : new long[]{0, UUIDv7.RAND_B_MASK - 4, UUIDv7.RAND_B_MASK}) {
            for (long observed : new long[]{99, 100, 101}) {
                for (int count : new int[]{0, 1, 4, 5, 256}) {
                    UUIDv7.GeneratorState scalar = new UUIDv7.GeneratorState(123, 100, 7, counter);
                    UUIDv7.GeneratorState longsState = new UUIDv7.GeneratorState(123, 100, 7, counter);
                    UUIDv7.GeneratorState bytesState = new UUIDv7.GeneratorState(123, 100, 7, counter);
                    long[] longs = new long[2 * count + 2];
                    byte[] bytes = new byte[16 * count + 2];
                    Arrays.fill(longs, -1L);
                    Arrays.fill(bytes, (byte) -1);
                    longsState.fill(longs, 1, count, observed);
                    bytesState.fill(bytes, 1, count, observed);
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    buffer.position(1);
                    for (int i = 0; i < count; i++) {
                        UUID expected = scalar.next(observed);
                        assertEquals(expected, new UUID(longs[1 + 2 * i], longs[2 + 2 * i]));
                        assertEquals(expected, new UUID(buffer.getLong(), buffer.getLong()));
                    }
                    assertEquals(-1L, longs[0]);
                    assertEquals(-1L, longs[longs.length - 1]);
                    assertEquals((byte) -1, bytes[0]);
                    assertEquals((byte) -1, bytes[bytes.length - 1]);
                    UUID next = scalar.next(observed);
                    assertEquals(next, longsState.next(observed));
                    assertEquals(next, bytesState.next(observed));
                }
            }
        }
    }

    @Test
    void maximumTimestampExhaustionKeepsWrittenPrefixAndDoesNotWrap() {
        for (boolean binary : new boolean[]{false, true}) {
            UUIDv7.GeneratorState state = new UUIDv7.GeneratorState(
                    123, UUIDv7.TIMESTAMP_MASK, 7, UUIDv7.RAND_B_MASK - 1);
            long[] longs = new long[4];
            byte[] bytes = new byte[32];
            assertThrows(IllegalStateException.class, () -> {
                if (binary) {
                    state.fill(bytes, 0, 2, UUIDv7.TIMESTAMP_MASK);
                } else {
                    state.fill(longs, 0, 2, UUIDv7.TIMESTAMP_MASK);
                }
            });
            UUID expected = UUIDv7.assemble(UUIDv7.TIMESTAMP_MASK, 7, UUIDv7.RAND_B_MASK);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            assertEquals(expected, binary ? new UUID(buffer.getLong(), buffer.getLong())
                    : new UUID(longs[0], longs[1]));
            assertEquals(0, binary ? buffer.getLong() : longs[2]);
            assertEquals(0, binary ? buffer.getLong() : longs[3]);
            assertThrows(IllegalStateException.class, () -> state.next(UUIDv7.TIMESTAMP_MASK));
        }
    }

    @Test
    void reservationFailureLeavesStateUnchanged() {
        UUIDv7.GeneratorState state = new UUIDv7.GeneratorState(123, 100, 7, UUIDv7.RAND_B_MASK - 4);
        assertFalse(state.tryReserve(5, 99));
        assertEquals(UUIDv7.assemble(100, 7, UUIDv7.RAND_B_MASK - 3), state.next(99));
        assertTrue(state.tryReserve(3, 99));
        assertEquals(UUIDv7.RAND_B_MASK, state.randB);
        assertTrue(state.tryReserve(Integer.MAX_VALUE, 101));
        assertEquals(101, state.lastUnixTsMs);
        assertTrue(state.randB <= UUIDv7.RAND_B_MASK);
    }

    @Test
    void monotonicStateHandlesFrozenClockRollbackAndRecovery() {
        UUIDv7.GeneratorState fast = new UUIDv7.GeneratorState(123);
        UUIDv7.SecureGeneratorState secure = new UUIDv7.SecureGeneratorState(new PatternRandom(new byte[16]));
        UUID lastFast = null;
        UUID lastSecure = null;
        long logical = 0;
        for (long observed : new long[]{100, 100, 100, 50, 99, 100, 101, 200}) {
            logical = Math.max(logical, observed);
            UUID nextFast = fast.next(observed);
            UUID nextSecure = secure.next(observed);
            assertEquals(logical, UUIDv7.extractUnixTimestamp(nextFast));
            assertEquals(logical, UUIDv7.extractUnixTimestamp(nextSecure));
            if (lastFast != null) {
                assertTrue(UUIDv7.compareUnsigned(lastFast, nextFast) < 0);
                assertTrue(UUIDv7.compareUnsigned(lastSecure, nextSecure) < 0);
            }
            lastFast = nextFast;
            lastSecure = nextSecure;
        }
    }

    @Test
    void unorderedModeUsesAll74BitsAndRefreshesAcrossBufferBoundaries() {
        byte[] pattern = new byte[32];
        Arrays.fill(pattern, 0, 16, (byte) -1);
        PatternRandom random = new PatternRandom(pattern);
        UUIDv7.SecureGeneratorState state = new UUIDv7.SecureGeneratorState(random);
        for (int i = 0; i < 80; i++) {
            long observed = 100 - i;
            UUID uuid = state.nextUnordered(observed);
            assertEquals(UUIDv7.assemble(observed, i % 2 == 0 ? UUIDv7.RAND_A_MASK : 0,
                    i % 2 == 0 ? UUIDv7.RAND_B_MASK : 0), uuid);
        }
        assertEquals(3, random.refills);
    }

    @Test
    void unorderedCallsLeaveMonotonicTimestampAndCounterIntact() {
        UUIDv7.SecureGeneratorState state = new UUIDv7.SecureGeneratorState(new PatternRandom(new byte[16]));
        UUID first = state.next(100);
        for (int i = 0; i < 40; i++) {
            assertEquals(200, UUIDv7.extractUnixTimestamp(state.nextUnordered(200)));
        }
        UUID next = state.next(99);
        assertEquals(first.getMostSignificantBits(), next.getMostSignificantBits());
        assertEquals(first.getLeastSignificantBits() + 1, next.getLeastSignificantBits());
    }

    @Test
    void unorderedPayloadRefillsWhenOnlyTwoEntropyBytesRemain() {
        byte[] pattern = new byte[16];
        Arrays.fill(pattern, (byte) -1);
        PatternRandom random = new PatternRandom(pattern);
        UUIDv7.SecureGeneratorState state = new UUIDv7.SecureGeneratorState(random);
        state.next(100); // 16 bytes for initial seeding
        for (int i = 0; i < 247; i++) {
            state.next(100); // 494 bytes for increments
        }
        assertEquals(1, random.refills);
        assertEquals(UUIDv7.assemble(99, UUIDv7.RAND_A_MASK, UUIDv7.RAND_B_MASK),
                state.nextUnordered(99));
        assertEquals(2, random.refills);
    }

    @Test
    void timestampNormalizationPreservesRepresentableBoundaries() {
        assertEquals(0, UUIDv7.extractUnixTimestamp(UUIDv7.assemble(-1, 0, 0)));
        assertEquals(UUIDv7.TIMESTAMP_MASK,
                UUIDv7.extractUnixTimestamp(UUIDv7.assemble(UUIDv7.TIMESTAMP_MASK, 0, 0)));
        assertThrows(IllegalStateException.class,
                () -> UUIDv7.assemble(UUIDv7.TIMESTAMP_MASK + 1, 0, 0));
    }

    @Test
    void secureIncrementBoundariesAndOverflowAreDeterministic() {
        for (int value : new int[]{0, 255}) {
            byte[] pattern = new byte[16];
            Arrays.fill(pattern, (byte) value);
            int increment = value == 0 ? 1 : 1024;
            UUIDv7.SecureGeneratorState state = new UUIDv7.SecureGeneratorState(
                    new PatternRandom(pattern), 100, 7, UUIDv7.RAND_B_MASK - increment);
            assertEquals(UUIDv7.RAND_B_MASK, UUIDv7.extractRandB(state.next(100)));
            UUID rolled = state.next(99);
            assertEquals(101, UUIDv7.extractUnixTimestamp(rolled));
            assertTrue(UUIDv7.extractRandB(rolled) <= UUIDv7.RAND_B_SEED_MASK);
            UUIDv7.SecureGeneratorState exhausted = new UUIDv7.SecureGeneratorState(
                    new PatternRandom(pattern), UUIDv7.TIMESTAMP_MASK, 7, UUIDv7.RAND_B_MASK);
            assertThrows(IllegalStateException.class, () -> exhausted.next(UUIDv7.TIMESTAMP_MASK));
        }
    }

    @Test
    void securePublicAliasesShareAnOrderedSequence() {
        UUID first = UUIDv7.secureRandomUUID();
        UUID second = UUIDv7.secureMonotonicUUID();
        UUID unordered = UUIDv7.secureUnorderedUUID();
        UUID third = UUIDv7.secureRandomUUID();
        assertTrue(UUIDv7.compareUnsigned(first, second) < 0);
        assertTrue(UUIDv7.compareUnsigned(second, third) < 0);
        assertEquals(7, unordered.version());
        assertEquals(2, unordered.variant());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void virtualThreadsExerciseAllGenerationContracts() throws Exception {
        ExecutorService executor;
        try {
            executor = (ExecutorService) Executors.class
                    .getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        } catch (NoSuchMethodException e) {
            Assumptions.assumeTrue(false, "requires Java 21+");
            return;
        }
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 128; t++) {
                futures.add(executor.submit(() -> {
                    UUID previous = UUIDv7.randomUUID();
                    long[] longs = new long[32];
                    UUIDv7.fill(longs, 0, 16);
                    for (int i = 0; i < 16; i++) {
                        UUID current = new UUID(longs[2 * i], longs[2 * i + 1]);
                        assertTrue(UUIDv7.compareUnsigned(previous, current) < 0);
                        previous = current;
                    }
                    byte[] bytes = new byte[256];
                    UUIDv7.fill(bytes, 0, 16);
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    for (int i = 0; i < 16; i++) {
                        UUID current = new UUID(buffer.getLong(), buffer.getLong());
                        assertTrue(UUIDv7.compareUnsigned(previous, current) < 0);
                        previous = current;
                    }
                    assertTrue(UUIDv7.compareUnsigned(previous,
                            UUID.fromString(UUIDv7.randomUUIDString())) < 0);
                    securePublicAliasesShareAnOrderedSequence();
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stripeReleasesLockOnTimestampExhaustion() {
        for (boolean binary : new boolean[]{false, true}) {
            UUIDv7.FastStripe stripe = new UUIDv7.FastStripe();
            stripe.state.lastUnixTsMs = UUIDv7.TIMESTAMP_MASK;
            stripe.state.randB = UUIDv7.RAND_B_MASK - 1;
            assertThrows(IllegalStateException.class, () -> {
                if (binary) {
                    stripe.fill(new byte[32], 0, 2, UUIDv7.TIMESTAMP_MASK);
                } else {
                    stripe.fill(new long[4], 0, 2, UUIDv7.TIMESTAMP_MASK);
                }
            });
            assertFalse(stripe.lock.isLocked());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentMixedBatchesOnOneStripeStayUniqueAndOrderedPerCaller() throws Exception {
        UUIDv7.FastStripe stripe = new UUIDv7.FastStripe();
        // Force the shared slow path before exercising reservations.
        stripe.state.lastUnixTsMs = 100;
        stripe.state.randB = UUIDv7.RAND_B_MASK - 2;
        int threads = 8;
        int rounds = 100;
        int batch = 32;
        Set<UUID> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    UUID previous = null;
                    long[] longs = new long[batch * 2];
                    byte[] bytes = new byte[batch * 16];
                    for (int r = 0; r < rounds; r++) {
                        boolean binary = r % 2 == 0;
                        if (binary) {
                            stripe.fill(bytes, 0, batch, 100);
                        } else {
                            stripe.fill(longs, 0, batch, 100);
                        }
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        for (int i = 0; i < batch; i++) {
                            UUID next = binary ? new UUID(buffer.getLong(), buffer.getLong())
                                    : new UUID(longs[2 * i], longs[2 * i + 1]);
                            assertEquals(7, next.version());
                            assertEquals(2, next.variant());
                            if (previous != null) {
                                assertTrue(UUIDv7.compareUnsigned(previous, next) < 0);
                            }
                            assertTrue(seen.add(next));
                            previous = next;
                        }
                        UUID single = stripe.next(100);
                        assertTrue(UUIDv7.compareUnsigned(previous, single) < 0);
                        assertTrue(seen.add(single));
                        previous = single;
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * rounds * (batch + 1), seen.size());
    }

    private static final class PatternRandom extends SecureRandom {
        private final byte[] pattern;
        private int position;
        private int refills;

        PatternRandom(byte[] pattern) {
            this.pattern = pattern;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            refills++;
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = pattern[position++ % pattern.length];
            }
        }
    }
}
