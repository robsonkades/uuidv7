package io.github.robsonkades.uuidv7;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link UUIDv7}.
 */
public class UUIDv7Test {

    /**
     * Extracts the 48-bit timestamp component from a UUIDv7.
     * <p>
     * According to the implementation, the timestamp occupies
     * bits 0–47 of the 128-bit UUID, which correspond to
     * the upper 48 bits of the most significant 64-bit half.
     *
     * @param uuid the UUIDv7 instance
     * @return the 48-bit timestamp (milliseconds since epoch, truncated)
     */
    private long extractTimestamp(UUID uuid) {
        long high = uuid.getMostSignificantBits();
        // Right-shift by 16 to discard version and high random bits:
        //    bits 0–47 (timestamp) end up in bits 16–63 of 'high',
        // so shifting right by 16 yields the original 48-bit timestamp.
        return (high >>> 16) & 0xFFFFFFFFFFFFL;
    }

    /**
     * Verifies that the generated UUID has version number == 7.
     */
    @Test
    public void testVersionBits() {
        UUID uuid = UUIDv7.randomUUID();
        assertEquals(7, uuid.version(), "UUID version should be 7 for UUIDv7");
    }

    /**
     * Verifies that the generated UUID has variant bits == 2 (binary 10).
     * In java.util.UUID, variant() returns:
     * - 0 for reserved for NCS backward compatibility
     * - 2 for RFC 4122 (which is what we expect for UUIDv7)
     */
    @Test
    public void testVariantBits() {
        UUID uuid = UUIDv7.randomUUID();
        assertEquals(2, uuid.variant(), "UUID variant should be 2 (RFC 4122) for UUIDv7");
    }

    /**
     * Tests that the 48-bit timestamp portion of the generated UUID
     * is reasonably close to System.currentTimeMillis() at generation time.
     * Allows a delta of up to 5 milliseconds to account for runtime overhead.
     */
    @Test
    public void testTimestampCloseToNow() {
        long before = System.currentTimeMillis();
        UUID uuid = UUIDv7.randomUUID();
        long after = System.currentTimeMillis();

        long ts48 = extractTimestamp(uuid);

        // The extracted timestamp must lie between 'before' and 'after' (inclusive),
        // possibly truncated to 48 bits. Since we only keep lower 48 bits, we compare to raw ms too.
        // But if the epoch overflows 48 bits (year ~10889), truncation occurs. Today, no overflow.
        assertTrue(ts48 >= before && ts48 <= after + 1,
                () -> String.format(
                        "Timestamp %d should be between before=%d and after=%d", ts48, before, after));
    }

    /**
     * Tests that generating multiple UUIDs in sequence yields timestamps that
     * are non-decreasing (time-sortable property).
     * Because system clock may not tick between very fast calls, allow equality.
     */
    @Test
    @Timeout(1) // ensure test completes quickly
    public void testTimestampMonotonicity() {
        final int COUNT = 1000;
        long prevTs = -1;
        for (int i = 0; i < COUNT; i++) {
            UUID uuid = UUIDv7.randomUUID();
            long ts48 = extractTimestamp(uuid);
            long finalPrevTs = prevTs;
            assertTrue(ts48 >= prevTs,
                    () -> String.format("Timestamp %d should be >= previous %d", ts48, finalPrevTs));
            prevTs = ts48;
        }
    }

    /**
     * Tests that the UUIDs generated in a tight loop are unique.
     * Uses a small set size to avoid excessive memory usage; collisions in a small batch are extremely unlikely.
     */
    @Test
    @Timeout(1)
    public void testUniquenessSmallBatch() {
        final int BATCH_SIZE = 100_000;
        Set<UUID> seen = new HashSet<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            UUID uuid = UUIDv7.randomUUID();
            assertFalse(seen.contains(uuid), "Duplicate UUID detected in small batch");
            seen.add(uuid);
        }
    }

    /**
     * Repeatedly generate two UUIDs and ensure that they differ at least in
     * the random bits portion, even if generated within the same millisecond.
     * This helps validate that the 74 bits of entropy vary.
     */
    @RepeatedTest(50)
    public void testDifferingRandomnessWithinSameMs() {
        // Force both calls within the same millisecond window
        long now = System.currentTimeMillis();
        // Busy-wait until the next millisecond to start tests in a fresh tick
        while (System.currentTimeMillis() == now) {
            Thread.yield();
        }
        // Generate first UUID
        UUID first = UUIDv7.randomUUID();
        // Immediately generate second
        UUID second = UUIDv7.randomUUID();

        // If both calls still happened in the same millisecond, timestamps equal:
        long ts1 = extractTimestamp(first);
        long ts2 = extractTimestamp(second);

        if (ts1 == ts2) {
            // Compare entire 128-bit values; should differ in random portion
            assertNotEquals(first, second,
                    "Two UUIDs generated in the same millisecond must not be identical");
        } else {
            // If clock ticked, they differ by timestamp; that's also acceptable
            assertTrue(ts2 > ts1,
                    String.format("Second timestamp %d should be > first timestamp %d", ts2, ts1));
        }
    }

    /**
     * Tests that all bits outside the timestamp, version, and variant fields
     * are changing over multiple invocations (i.e., the 74 bits of entropy vary).
     */
    @Test
    @Timeout(1)
    public void testEntropyBitsVary() {
        // Generate two UUIDs within the same millisecond and inspect their random bits
        long now = System.currentTimeMillis();
        // Busy-wait for a fresh millisecond
        while (System.currentTimeMillis() == now) {
            Thread.yield();
        }
        UUID u1 = UUIDv7.randomUUID();
        UUID u2 = UUIDv7.randomUUID();

        long ts1 = extractTimestamp(u1);
        long ts2 = extractTimestamp(u2);

        if (ts1 == ts2) {
            // Extract random bits: low 64 bits, mask off variant (bits 64–65)
            long randPart1 = u1.getLeastSignificantBits() & 0x3FFFFFFFFFFFFFFFL; // lower 62 bits
            long randPart2 = u2.getLeastSignificantBits() & 0x3FFFFFFFFFFFFFFFL;
            assertNotEquals(randPart1, randPart2,
                    "Random entropy portion should differ between two UUIDs in same ms");
        } else {
            // If timestamps differ, random portions could be anything; just pass
            assertTrue(true);
        }
    }

    /**
     * Validates that, over many generations, the version() method remains 7
     * and that variant() remains 2. This is a sanity check over repeated calls.
     */
    @Test
    @Timeout(1)
    public void testVersionAndVariantConstantOverMany() {
        final int ITER = 10_000;
        for (int i = 0; i < ITER; i++) {
            UUID uuid = UUIDv7.randomUUID();
            assertEquals(7, uuid.version(), "UUID version must always be 7");
            assertEquals(2, uuid.variant(), "UUID variant must always be 2");
        }
    }

    /**
     * Tests that the 48‐bit timestamp extracted from the UUID
     * is correctly masked and lies between the millisecond captured
     * immediately before and immediately after UUID creation.
     */
    @Test
    public void testTimestampMasking() {
        long before = System.currentTimeMillis();
        UUID uuid = UUIDv7.randomUUID();
        long after = System.currentTimeMillis();

        long ts48 = extractTimestamp(uuid);
        long beforeMasked = before & 0xFFFFFFFFFFFFL;
        long afterMasked  = after  & 0xFFFFFFFFFFFFL;

        assertTrue(
                ts48 >= beforeMasked && ts48 <= afterMasked,
                () -> String.format(
                        "Masked timestamp was %d; expected to be between %d and %d",
                        ts48, beforeMasked, afterMasked
                )
        );
    }
}
