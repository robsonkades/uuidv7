package io.github.robsonkades.uuidv7;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Static factory for generating UUID version 7 values.
 *
 * <p>This implementation follows the UUIDv7 layout defined by RFC 9562:
 * a 48-bit Unix epoch timestamp in milliseconds in the most significant bits,
 * the version and variant fields mandated by the specification, and 74 bits
 * available for uniqueness and monotonic sequencing.</p>
 *
 * <p>The class exposes two generation strategies:</p>
 * <ul>
 *   <li>{@link #randomUUID()} favors throughput and scalability. It uses
 *   per-thread state, avoids shared hot-path contention, and guarantees that
 *   values produced by the same thread remain strictly increasing even when
 *   multiple UUIDs are created within the same millisecond.</li>
 *   <li>{@link #secureRandomUUID()} favors unpredictability. It uses
 *   {@link SecureRandom} to seed and advance the random fields, which improves
 *   resistance to prediction at a materially higher cost per UUID.</li>
 * </ul>
 *
 * <p>Both methods are thread-safe. Ordering guarantees are intentionally local:
 * this implementation avoids a single global sequencer in order to preserve
 * multicore scalability, so it does not attempt to impose a total order across
 * all threads.</p>
 *
 * <p>Edge conditions are handled defensively. If the observed wall clock moves
 * backwards, generation continues from the last emitted timestamp and advances
 * the monotonic state instead of emitting a smaller UUID. If the generator
 * exhausts the available counter space for the active timestamp, it advances to
 * the next logical millisecond rather than knowingly wrapping and returning a
 * duplicate value.</p>
 *
 * <p>UUIDv7 exposes creation time by design. Even when the secure generation
 * path is used, these identifiers should not be treated as authentication
 * tokens, bearer secrets, or opaque security credentials.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * UUID id = UUIDv7.randomUUID();
 * UUID secureId = UUIDv7.secureRandomUUID();
 * }</pre>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9562.html">RFC 9562</a>
 */
public final class UUIDv7 {

    static final long TIMESTAMP_MASK = 0x0000FFFFFFFFFFFFL;
    static final int RAND_A_MASK = 0x0FFF;
    static final long RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL;

    private static final long VERSION_BITS = 0x0000000000007000L;
    private static final long VARIANT_BITS = 0x8000000000000000L;
    private static final long SPLITMIX64_GAMMA = 0x9E3779B97F4A7C15L;

    private static final AtomicLong SEEDER = new AtomicLong(
            mix64(System.nanoTime()) ^ mix64(System.currentTimeMillis()));

    private static final ThreadLocal<GeneratorState> FAST_STATE =
            ThreadLocal.withInitial(UUIDv7::newGeneratorState);

    private static final ThreadLocal<SecureGeneratorState> SECURE_STATE =
            ThreadLocal.withInitial(SecureGeneratorState::new);

    private UUIDv7() {
    }

    /**
     * Generates a UUIDv7 optimized for throughput.
     *
     * <p>This method uses a per-thread generator state backed by a fast
     * non-cryptographic mixing function. It avoids global locks, shared atomics,
     * temporary buffers, and other coordination points that would otherwise
     * limit throughput under contention.</p>
     *
     * <p>Within a single thread, values are strictly increasing even when
     * several UUIDs are created during the same millisecond. Across different
     * threads, the values remain RFC-compliant and practically unique, but no
     * total ordering across threads is promised.</p>
     *
     * <p>This is the recommended API for database keys, event identifiers,
     * distributed tracing identifiers, and other latency-sensitive use cases
     * where very high allocation rates matter more than cryptographic
     * unpredictability.</p>
     *
     * @return a new RFC 9562-compliant UUIDv7
     * @throws IllegalStateException if the system time exceeds the representable
     *         48-bit UUIDv7 timestamp range
     */
    public static UUID randomUUID() {
        return FAST_STATE.get().next(currentUnixTimestamp());
    }

    /**
     * Generates a UUIDv7 using {@link SecureRandom}-backed entropy.
     *
     * <p>This method preserves the same RFC 9562 bit layout as
     * {@link #randomUUID()}, but it uses a per-thread {@link SecureRandom}
     * instance to seed the random fields for each timestamp and to advance the
     * monotonic state while the timestamp remains unchanged. The result is more
     * resistant to prediction than the fast path, at a substantially lower
     * throughput and with higher allocation and provider cost.</p>
     *
     * <p>Use this method only when stronger entropy properties are required for
     * the UUID payload itself. It is still not a substitute for dedicated secret
     * generation because UUIDv7 embeds the creation timestamp by design.</p>
     *
     * @return a new RFC 9562-compliant UUIDv7 generated from a secure entropy
     *         source
     * @throws IllegalStateException if the system time exceeds the representable
     *         48-bit UUIDv7 timestamp range
     */
    public static UUID secureRandomUUID() {
        return SECURE_STATE.get().next(currentUnixTimestamp());
    }

    static UUID assemble(long unixTsMs, int randA, long randB) {
        long timestamp = normalizeTimestamp(unixTsMs);
        long msb = (timestamp << 16) | VERSION_BITS | (randA & RAND_A_MASK);
        long lsb = VARIANT_BITS | (randB & RAND_B_MASK);
        return new UUID(msb, lsb);
    }

    static long extractUnixTimestamp(UUID uuid) {
        return (uuid.getMostSignificantBits() >>> 16) & TIMESTAMP_MASK;
    }

    static int extractRandA(UUID uuid) {
        return (int) (uuid.getMostSignificantBits() & RAND_A_MASK);
    }

    static long extractRandB(UUID uuid) {
        return uuid.getLeastSignificantBits() & RAND_B_MASK;
    }

    static int compareUnsigned(UUID left, UUID right) {
        int msb = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        if (msb != 0) {
            return msb;
        }
        return Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private static GeneratorState newGeneratorState() {
        long threadId = Thread.currentThread().getId();
        long seed = SEEDER.getAndAdd(SPLITMIX64_GAMMA) ^ mix64(threadId) ^ mix64(System.nanoTime());
        return new GeneratorState(seed);
    }

    private static long currentUnixTimestamp() {
        return normalizeTimestamp(System.currentTimeMillis());
    }

    private static long normalizeTimestamp(long unixTsMs) {
        if (unixTsMs < 0L) {
            return 0L;
        }
        if (unixTsMs > TIMESTAMP_MASK) {
            throw new IllegalStateException("UUIDv7 timestamp exceeds 48-bit Unix epoch range: " + unixTsMs);
        }
        return unixTsMs;
    }

    private static long mix64(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Per-thread state for the high-throughput generator.
     *
     * <p>The invariants are:</p>
     * <ul>
     *   <li>{@code lastUnixTsMs} is the logical timestamp used for the next UUID.</li>
     *   <li>{@code randA} is randomized whenever the logical timestamp advances.</li>
     *   <li>{@code randB} acts as a monotonic counter while the logical timestamp
     *   remains unchanged.</li>
     * </ul>
     *
     * <p>The state is intentionally thread-confined. That removes the need for
     * atomics or locks on the hot path and avoids cache-line contention between
     * producers.</p>
     */
    static final class GeneratorState {

        private long lastUnixTsMs = -1L;
        private long splitMix64State;
        private int randA;
        private long randB;

        GeneratorState(long seed) {
            this.splitMix64State = seed;
        }

        GeneratorState(long seed, long lastUnixTsMs, int randA, long randB) {
            this.splitMix64State = seed;
            this.lastUnixTsMs = lastUnixTsMs;
            this.randA = randA & RAND_A_MASK;
            this.randB = randB & RAND_B_MASK;
        }

        /**
         * Produce the next UUID for the supplied wall-clock millisecond.
         *
         * <p>If wall-clock time advances, the random fields are reseeded for the
         * new timestamp. If time stays the same or moves backwards, the method
         * keeps the previous logical timestamp and advances the monotonic state
         * instead so that the returned UUID remains greater than the previous
         * value produced by this state instance.</p>
         */
        UUID next(long requestedUnixTsMs) {
            long unixTsMs = normalizeTimestamp(requestedUnixTsMs);

            if (unixTsMs > lastUnixTsMs) {
                lastUnixTsMs = unixTsMs;
                reseed();
            } else if (unixTsMs == lastUnixTsMs) {
                incrementCounter();
            } else {
                incrementCounter();
            }

            return assemble(lastUnixTsMs, randA, randB);
        }

        /**
         * Reinitialize the random portion for a new logical timestamp.
         *
         * <p>The fast generator uses SplitMix64 output as a per-thread source of
         * high-quality mixed bits. This is a throughput-oriented choice rather
         * than a cryptographic one.</p>
         */
        private void reseed() {
            randA = (int) nextLong() & RAND_A_MASK;
            randB = nextLong() & RAND_B_MASK;
        }

        /**
         * Advance the same-timestamp monotonic state.
         *
         * <p>On overflow, the generator moves to the next logical millisecond
         * instead of wrapping {@code randB}. Wrapping would knowingly violate the
         * monotonicity and uniqueness assumptions for values produced by this
         * state instance.</p>
         */
        private void incrementCounter() {
            if (randB == RAND_B_MASK) {
                if (lastUnixTsMs == TIMESTAMP_MASK) {
                    throw new IllegalStateException("UUIDv7 counter overflow at maximum representable timestamp");
                }
                lastUnixTsMs++;
                reseed();
                return;
            }
            randB++;
        }

        private long nextLong() {
            splitMix64State += SPLITMIX64_GAMMA;
            return mix64(splitMix64State);
        }
    }

    /**
     * Per-thread state for the secure generator.
     *
     * <p>This state machine mirrors {@link GeneratorState} but uses
     * {@link SecureRandom} both to seed a new timestamp and to choose a positive
     * random increment while the timestamp remains unchanged. That follows the
     * RFC 9562 "monotonic random" style more closely than incrementing by one,
     * but it is substantially slower than the fast generator.</p>
     */
    static final class SecureGeneratorState {

        private final SecureRandom secureRandom = new SecureRandom();
        private long lastUnixTsMs = -1L;
        private int randA;
        private long randB;

        /**
         * Produce the next UUID for the supplied wall-clock millisecond using a
         * {@link SecureRandom}-backed state transition.
         */
        UUID next(long requestedUnixTsMs) {
            long unixTsMs = normalizeTimestamp(requestedUnixTsMs);

            if (unixTsMs > lastUnixTsMs) {
                lastUnixTsMs = unixTsMs;
                reseed();
            } else if (unixTsMs == lastUnixTsMs) {
                incrementCounter();
            } else {
                incrementCounter();
            }

            return assemble(lastUnixTsMs, randA, randB);
        }

        /**
         * Reinitialize the UUIDv7 random fields for a new logical timestamp.
         *
         * <p>Two secure {@code long} values are used so the implementation can
         * fill both {@code rand_a} and {@code rand_b} directly without
         * intermediate arrays.</p>
         */
        private void reseed() {
            long upper = secureRandom.nextLong();
            long lower = secureRandom.nextLong();

            randA = (int) (upper & RAND_A_MASK);
            randB = (((upper >>> 12) & 0x000FFFFFFFFFFFFFL) << 10) | ((lower >>> 54) & 0x03FFL);
        }

        /**
         * Advance the secure monotonic state.
         *
         * <p>The increment is a positive random value instead of a fixed step.
         * This keeps same-millisecond UUIDs ordered while avoiding the
         * predictability of a simple sequential counter. If the increment would
         * overflow {@code randB}, the generator rolls forward to the next logical
         * millisecond and reseeds.</p>
         */
        private void incrementCounter() {
            long increment = (secureRandom.nextLong() & 0x03FFL) + 1L;

            if (RAND_B_MASK - randB < increment) {
                if (lastUnixTsMs == TIMESTAMP_MASK) {
                    throw new IllegalStateException("UUIDv7 counter overflow at maximum representable timestamp");
                }
                lastUnixTsMs++;
                reseed();
                return;
            }
            randB += increment;
        }
    }
}
