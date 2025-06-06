package io.github.robsonkades.uuidv7;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility class for generating UUID version 7 identifiers.
 * <p>
 * A UUIDv7 consists of:
 * <ul>
 *   <li>48 bits: current time in milliseconds since Unix epoch (truncated to 48 bits).</li>
 *   <li>4 bits: version number (0b0111).</li>
 *   <li>12 bits: high bits of random entropy.</li>
 *   <li>2 bits: variant (0b10). </li>
 *   <li>62 bits: remaining random entropy.</li>
 * </ul>
 * This class uses {@link ThreadLocalRandom} for high-performance entropy and
 * directly assembles the two 64-bit halves (high/low) with bitwise operations,
 * avoiding intermediate byte arrays or ByteBuffer overhead.
 * <p>
 * UUIDv7 identifiers are roughly time-sortable (the high 48 bits represent
 * the timestamp). They are ideal for databases, distributed logs, or any
 * system that benefits from lexicographically sortable unique IDs.
 */
public final class UUIDv7 {

    private UUIDv7() {
        // Prevent instantiation
    }

    /**
     * Generates a UUID version 7.
     *
     * <p>The format is:
     * <ul>
     *   <li>Bits 0–47: 48-bit timestamp (milliseconds since epoch, big-endian).</li>
     *   <li>Bits 48–51: 4-bit version (binary 0111).</li>
     *   <li>Bits 52–63: 12 random bits (extracted from a 64-bit random value).</li>
     *   <li>Bits 64–65: 2-bit variant (binary 10).</li>
     *   <li>Bits 66–127: Remaining 62 random bits (52 bits from 64-bit random, plus
     *       10 bits from a 32-bit random, for a total of 74 bits entropy).</li>
     * </ul>
     *
     * @return a {@link java.util.UUID} instance representing a UUIDv7.
     *
     * @see java.util.UUID
     * @see java.util.concurrent.ThreadLocalRandom
     */
    public static UUID randomUUID() {
        // 1) Fetch current time in ms, mask to 48 bits
        long currentMillis = System.currentTimeMillis();
        long ts48 = currentMillis & 0xFFFFFFFFFFFFL;  // 48-bit mask

        // 2) Get 74 bits of entropy from ThreadLocalRandom: 64 + 32 bits
        long random64 = ThreadLocalRandom.current().nextLong();
        int random32 = ThreadLocalRandom.current().nextInt();

        // Assemble the high 64 bits:
        //   [ 48-bit timestamp ] [ 4-bit version=7 ] [ 12 high random bits ]
        long high = (ts48 << 16);                         // place 48 ms bits at bits 0–47 of high<<16 = bits 16–63
        long randHigh12 = (random64 >>> 52) & 0x0FFFL;    // top 12 bits of random64
        high |= randHigh12;                              // bits 52–63
        high |= 0x0000000000007000L;                     // set version (4 bits = 0b0111) at bits 48–51

        // Assemble the low 64 bits:
        //   [ 2-bit variant=10 ] [ 52 low bits of random64 ] [ 10 high bits of random32 ]
        long low = 0x8000000000000000L;                   // set variant 0b10 at bits 64–65
        long randLow52 = random64 & 0x000FFFFFFFFFFFFFL;  // lower 52 bits of random64
        int rand32High10 = (random32 >>> 22) & 0x3FF;     // top 10 bits of random32
        low |= (randLow52 << 10);                         // place 52 bits at bits 66–117
        low |= rand32High10;                              // place 10 bits at bits 118–127

        return new UUID(high, low);
    }
}
