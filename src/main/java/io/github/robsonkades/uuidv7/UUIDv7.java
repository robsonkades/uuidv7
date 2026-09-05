package io.github.robsonkades.uuidv7;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Static factory for generating UUID version 7 values.
 *
 * <p>This implementation follows the UUIDv7 layout defined by RFC 9562:
 * a 48-bit Unix epoch timestamp in milliseconds in the most significant bits,
 * the version and variant fields mandated by the specification, and 74 bits
 * available for uniqueness and monotonic sequencing.</p>
 *
 * <p>The class exposes the following generation strategies:</p>
 * <ul>
 *   <li>{@link #randomUUID()} favors throughput and scalability. It uses
 *   per-thread state, avoids shared hot-path contention, and guarantees that
 *   values produced by the same thread remain strictly increasing even when
 *   multiple UUIDs are created within the same millisecond.</li>
 *   <li>{@link #randomUUIDString()} produces the canonical 36-character string
 *   form through the JDK UUID formatter.</li>
 *   <li>{@link #fill(long[], int, int)} and {@link #fill(byte[], int, int)}
 *   generate UUIDs in bulk with zero allocations, amortizing clock reads and
 *   per-thread state lookups across the whole batch.</li>
 *   <li>{@link #secureMonotonicUUID()} uses secure entropy for seeds and
 *   bounded random increments, preserving ordering per thread.</li>
 *   <li>{@link #secureUnorderedUUID()} draws all 74 payload bits from
 *   {@link SecureRandom} for every UUID, without monotonic sequencing.</li>
 * </ul>
 *
 * <p>For callers that already confine work to a single thread (event loops,
 * actors, partitioned pipelines), {@link UUIDv7Generator} offers an instance
 * API without the per-call {@link ThreadLocal} lookup.</p>
 *
 * <p>Bulk allocation claims describe warmed-up generation into caller-owned
 * buffers. Initial state creation and contended lock bookkeeping can allocate;
 * no per-UUID objects or temporary batch arrays are created by the bulk API.</p>
 *
 * <p>All static methods are thread-safe, including when invoked from virtual
 * threads: virtual threads are transparently routed to a striped pool of
 * generator states so that neither per-thread state churn nor per-thread
 * {@link SecureRandom} construction can degrade throughput. Ordering
 * guarantees are intentionally local: this implementation avoids a single
 * global sequencer in order to preserve multicore scalability, so it does not
 * attempt to impose a total order across all threads.</p>
 *
 * <p>Since the UUIDv7 timestamp has millisecond granularity, the wall clock
 * only needs to be observed once per millisecond. Setting the system property
 * {@value #CACHED_CLOCK_PROPERTY} to {@code true} enables a cached clock
 * updated by a daemon thread roughly every 0.5&nbsp;ms, replacing the
 * {@link System#currentTimeMillis()} call on the hot path with a plain
 * volatile read. The timestamp is approximate: scheduling and JVM pauses can
 * delay updates, with no fixed upper bound on staleness. Monotonic generators
 * retain their ordering guarantees even while the cached time is unchanged.</p>
 *
 * <p>For monotonic generators, edge conditions are handled defensively.
 * If the observed wall clock moves
 * backwards, generation continues from the last emitted timestamp and advances
 * the monotonic state instead of emitting a smaller UUID. Following the
 * counter-seeding guidance of RFC 9562 Section 6.2, the two most significant
 * bits of the monotonic {@code rand_b} counter are seeded to zero, which
 * guarantees at least 2^60 counter positions of headroom per millisecond; if the
 * counter space is nevertheless exhausted, the generator advances to the next
 * logical millisecond rather than knowingly wrapping and returning a duplicate
 * value.</p>
 *
 * <p>UUIDv7 exposes creation time by design. Even when the secure generation
 * path is used, these identifiers should not be treated as authentication
 * tokens, bearer secrets, or opaque security credentials.</p>
 *
 * <p>GraalVM native image: this package must be initialized at run time so
 * that random seeds are not frozen into the image heap at build time. The
 * published JAR embeds the required {@code native-image.properties}, so no
 * extra configuration is needed by users.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * UUID id = UUIDv7.randomUUID();
 * String key = UUIDv7.randomUUIDString();
 * UUID secureId = UUIDv7.secureRandomUUID();
 *
 * long[] batch = new long[2 * 1024];
 * UUIDv7.fill(batch, 0, 1024); // 1024 UUIDs, zero allocations
 * }</pre>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9562.html">RFC 9562</a>
 */
public final class UUIDv7 {

    static final long TIMESTAMP_MASK = 0x0000FFFFFFFFFFFFL;
    static final int RAND_A_MASK = 0x0FFF;
    static final long RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL;

    /**
     * Seed mask for the monotonic {@code rand_b} counter. Per RFC 9562
     * Section 6.2 (fixed-length dedicated counter seeding), the two most
     * significant counter bits are seeded to zero so that every millisecond
     * starts with at least 2^60 increments of guaranteed headroom, making
     * counter rollover unreachable in practice.
     */
    static final long RAND_B_SEED_MASK = RAND_B_MASK >>> 2;

    /**
     * Name of the boolean system property that enables the cached millisecond
     * clock ({@code io.github.robsonkades.uuidv7.cachedClock}). When enabled, a
     * daemon thread refreshes a volatile timestamp roughly every 0.5&nbsp;ms
     * and the generators read that instead of calling
     * {@link System#currentTimeMillis()} on every UUID.
     */
    public static final String CACHED_CLOCK_PROPERTY = "io.github.robsonkades.uuidv7.cachedClock";

    private static final long VERSION_BITS = 0x0000000000007000L;
    private static final long VARIANT_BITS = 0x8000000000000000L;
    private static final long SPLITMIX64_GAMMA = 0x9E3779B97F4A7C15L;

    static final VarHandle LONG_BE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

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
     * limit throughput under contention. Virtual threads are routed to a
     * striped pool of states instead of per-thread state.</p>
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
        long unixTsMs = currentUnixTimestamp();
        if (VirtualThreads.current()) {
            return FastStripes.next(unixTsMs);
        }
        return FAST_STATE.get().next(unixTsMs);
    }

    /**
     * Generates a UUIDv7 and returns its canonical 36-character string form.
     *
     * <p>Equivalent to {@code randomUUID().toString()}. It delegates canonical
     * formatting to the JDK, whose compact-string implementation is optimized
     * for the active Java release.</p>
     *
     * @return the canonical lowercase hexadecimal representation of a new
     *         RFC 9562-compliant UUIDv7
     * @throws IllegalStateException if the system time exceeds the representable
     *         48-bit UUIDv7 timestamp range
     */
    public static String randomUUIDString() {
        long unixTsMs = currentUnixTimestamp();
        if (VirtualThreads.current()) {
            return FastStripes.nextString(unixTsMs);
        }
        GeneratorState state = FAST_STATE.get();
        state.advance(unixTsMs);
        return toCanonicalString(state.msb(), state.lsb());
    }

    /**
     * Generates {@code count} UUIDv7 values into {@code dst} as
     * {@code (mostSignificantBits, leastSignificantBits)} pairs, starting at
     * {@code offset}. Writes {@code 2 * count} longs and allocates nothing.
     *
     * <p>The wall clock is read once for the whole batch and the generated
     * values are strictly increasing within the batch, so this is the highest
     * throughput API offered by this class.</p>
     *
     * @param dst    destination array
     * @param offset index of the first long written
     * @param count  number of UUIDs to generate
     * @throws IndexOutOfBoundsException if the range
     *         {@code [offset, offset + 2 * count)} is not within {@code dst}
     * @throws IllegalStateException if the system time exceeds the representable
     *         48-bit UUIDv7 timestamp range
     */
    public static void fill(long[] dst, int offset, int count) {
        Objects.checkFromIndexSize(offset, Math.multiplyExact(count, 2), dst.length);
        if (count == 0) {
            return;
        }
        long unixTsMs = currentUnixTimestamp();
        if (VirtualThreads.current()) {
            FastStripes.fill(dst, offset, count, unixTsMs);
            return;
        }
        FAST_STATE.get().fill(dst, offset, count, unixTsMs);
    }

    /**
     * Generates {@code count} UUIDv7 values into {@code dst} in the RFC 9562
     * big-endian binary layout (16 bytes per UUID), starting at {@code offset}.
     * Writes {@code 16 * count} bytes and allocates nothing.
     *
     * <p>The wall clock is read once for the whole batch and the generated
     * values are strictly increasing within the batch.</p>
     *
     * @param dst    destination array
     * @param offset index of the first byte written
     * @param count  number of UUIDs to generate
     * @throws IndexOutOfBoundsException if the range
     *         {@code [offset, offset + 16 * count)} is not within {@code dst}
     * @throws IllegalStateException if the system time exceeds the representable
     *         48-bit UUIDv7 timestamp range
     */
    public static void fill(byte[] dst, int offset, int count) {
        Objects.checkFromIndexSize(offset, Math.multiplyExact(count, 16), dst.length);
        if (count == 0) {
            return;
        }
        long unixTsMs = currentUnixTimestamp();
        if (VirtualThreads.current()) {
            FastStripes.fill(dst, offset, count, unixTsMs);
            return;
        }
        FAST_STATE.get().fill(dst, offset, count, unixTsMs);
    }

    /**
     * Generates a UUIDv7 using {@link SecureRandom}-backed entropy.
     * This is the compatibility alias for {@link #secureMonotonicUUID()},
     * including its bounded-increment predictability. It does not draw a fresh
     * 74-bit random payload on every call; see {@link #secureUnorderedUUID()}.
     *
     * <p>This method preserves the same RFC 9562 bit layout as
     * {@link #randomUUID()}, but it draws the random fields from a per-thread
     * {@link SecureRandom}. Entropy is fetched in 512-byte blocks and buffered
     * in the per-thread state, amortizing the provider and lock costs of
     * {@link SecureRandom} over many UUIDs; note that this keeps a small block
     * of random material resident in the heap between calls. Virtual threads
     * are routed to a striped pool so that no {@link SecureRandom} instance is
     * ever constructed per virtual thread.</p>
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
        return secureMonotonicUUID();
    }

    /**
     * Generates a UUIDv7 with secure seeds and monotonic random increments.
     * Values from this method and {@link #secureRandomUUID()} share the same
     * sequence, strictly increasing per calling thread.
     *
     * <p>Within one logical millisecond, the next value from the same state
     * advances by 1 to 1024 unless the counter overflows. Observing a value
     * therefore narrows the next value to at most 1024 candidates in that case.
     * Use {@link #secureUnorderedUUID()} when fresh random payloads matter
     * more than same-millisecond ordering. Neither API is a token generator.</p>
     *
     * @return a monotonic UUIDv7 backed by secure entropy
     * @throws IllegalStateException if the timestamp range is exhausted
     */
    public static UUID secureMonotonicUUID() {
        long unixTsMs = currentUnixTimestamp();
        if (VirtualThreads.current()) {
            return SecureStripes.next(unixTsMs);
        }
        return SECURE_STATE.get().next(unixTsMs);
    }

    /**
     * Generates a UUIDv7 with 74 fresh random payload bits from buffered
     * {@link SecureRandom} output. No monotonic ordering is promised, including
     * within one millisecond or across clock rollback. The timestamp uses the
     * configured wall clock; it is not clamped to a previous UUID's timestamp.
     * Calls do not advance the secure monotonic sequence.
     *
     * @return a UUIDv7 with a freshly randomized payload
     * @throws IllegalStateException if the timestamp exceeds the 48-bit range
     */
    public static UUID secureUnorderedUUID() {
        long unixTsMs = currentUnixTimestamp();
        if (VirtualThreads.current()) {
            return SecureStripes.stripe().nextUnordered(unixTsMs);
        }
        return SECURE_STATE.get().nextUnordered(unixTsMs);
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

    static GeneratorState newGeneratorState() {
        // Mix the sequence value so neighboring generators do not start one
        // SplitMix64 step apart. No entropy-provider calls on this path after
        // Seeder initialization.
        return new GeneratorState(mix64(Seeder.NEXT.getAndAdd(SPLITMIX64_GAMMA)));
    }

    /**
     * Reads and normalizes the current wall-clock millisecond. This is the only
     * place where normalization happens on the hot path; the generator states
     * trust that their input is already within the 48-bit range.
     */
    static long currentUnixTimestamp() {
        return normalizeTimestamp(WallClock.now());
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
     * Formats {@code (msb, lsb)} as the canonical lowercase UUID string through
     * the JDK implementation. HotSpot can eliminate the temporary UUID when it
     * does not escape.
     */
    static String toCanonicalString(long msb, long lsb) {
        return new UUID(msb, lsb).toString();
    }

    private static void writeBatch(long[] dst, int offset, int count, long msb, long firstLsb) {
        for (int i = 0; i < count; i++) {
            dst[offset++] = msb;
            dst[offset++] = firstLsb + i;
        }
    }

    private static void writeBatch(byte[] dst, int offset, int count, long msb, long firstLsb) {
        for (int i = 0; i < count; i++) {
            LONG_BE.set(dst, offset, msb);
            LONG_BE.set(dst, offset + 8, firstLsb + i);
            offset += 16;
        }
    }

    private static int stripeCount() {
        int target = Runtime.getRuntime().availableProcessors() * 4;
        int size = 1;
        while (size < target) {
            size <<= 1;
        }
        return size;
    }

    /**
     * Holder for the shared seed sequence. Kept in its own class so that
     * GraalVM native image initializes it at run time; a build-time-initialized
     * seed would bake the same SplitMix64 sequence into every process started
     * from the same image.
     */
    private static final class Seeder {
        static final AtomicLong NEXT = new AtomicLong(new SecureRandom().nextLong());
    }

    /**
     * Clock indirection. {@code CACHED} is a JIT-time constant after class
     * initialization, so the untaken branch is eliminated from compiled code.
     */
    private static final class WallClock {
        static final boolean CACHED = Boolean.getBoolean(CACHED_CLOCK_PROPERTY);

        static long now() {
            return CACHED ? Ticker.nowMillis : System.currentTimeMillis();
        }
    }

    /**
     * Lazy holder for the cached clock. Only initialized (and only starts its
     * daemon thread) when the cached clock property is enabled and the first
     * UUID is generated. The volatile read on the hot path compiles to a plain
     * load on x86; the cache line is shared read-mostly and invalidated once
     * per refresh, so cross-core coherence traffic is negligible.
     */
    private static final class Ticker {
        static volatile long nowMillis = System.currentTimeMillis();

        static {
            Thread ticker = new Thread(Ticker::run, "uuidv7-clock-ticker");
            ticker.setDaemon(true);
            ticker.start();
        }

        private static void run() {
            while (true) {
                nowMillis = System.currentTimeMillis();
                LockSupport.parkNanos(500_000L);
            }
        }
    }

    /**
     * Reflective probe for {@code Thread.isVirtual()} so the library can run on
     * Java 17 while still detecting virtual threads on Java 21+. The method
     * handle is a JIT-time constant: on Java 17 the {@code null} check folds to
     * {@code false}, on Java 21+ the {@code invokeExact} inlines to the plain
     * flag test inside {@code Thread.isVirtual()}.
     */
    private static final class VirtualThreads {
        static final MethodHandle IS_VIRTUAL = lookupIsVirtual();

        private static MethodHandle lookupIsVirtual() {
            try {
                return MethodHandles.publicLookup()
                        .findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                return null;
            }
        }

        static boolean current() {
            MethodHandle isVirtual = IS_VIRTUAL;
            if (isVirtual == null) {
                return false;
            }
            try {
                return (boolean) isVirtual.invokeExact(Thread.currentThread());
            } catch (Throwable t) {
                throw new IllegalStateException("Thread.isVirtual() probe failed", t);
            }
        }
    }

    /**
     * Striped generator pool used by virtual threads. Creating one state per
     * virtual thread would allocate and reseed on effectively every request in
     * thread-per-request servers, so virtual threads share a fixed pool of
     * padded states indexed by thread id, each guarded by a virtual-thread
     * friendly {@link ReentrantLock}.
     */
    private static final class FastStripes {
        static final FastStripe[] STRIPES = createStripes();
        static final int MASK = STRIPES.length - 1;

        private static FastStripe[] createStripes() {
            FastStripe[] stripes = new FastStripe[stripeCount()];
            for (int i = 0; i < stripes.length; i++) {
                stripes[i] = new FastStripe();
            }
            return stripes;
        }

        static FastStripe stripe() {
            return STRIPES[(int) mix64(Thread.currentThread().getId()) & MASK];
        }

        static UUID next(long unixTsMs) {
            return stripe().next(unixTsMs);
        }

        static String nextString(long unixTsMs) {
            return stripe().nextString(unixTsMs);
        }

        static void fill(long[] dst, int offset, int count, long unixTsMs) {
            stripe().fill(dst, offset, count, unixTsMs);
        }

        static void fill(byte[] dst, int offset, int count, long unixTsMs) {
            stripe().fill(dst, offset, count, unixTsMs);
        }
    }

    static final class FastStripe {

        final ReentrantLock lock = new ReentrantLock();
        final GeneratorState state = newGeneratorState();

        UUID next(long unixTsMs) {
            long msb;
            long lsb;
            lock.lock();
            try {
                state.advance(unixTsMs);
                msb = state.msb();
                lsb = state.lsb();
            } finally {
                lock.unlock();
            }
            return new UUID(msb, lsb);
        }

        String nextString(long unixTsMs) {
            long msb;
            long lsb;
            lock.lock();
            try {
                state.advance(unixTsMs);
                msb = state.msb();
                lsb = state.lsb();
            } finally {
                lock.unlock();
            }
            return toCanonicalString(msb, lsb);
        }

        void fill(long[] dst, int offset, int count, long unixTsMs) {
            if (count == 0) {
                return;
            }
            long msb;
            long firstLsb;
            lock.lock();
            try {
                if (!state.tryReserve(count, unixTsMs)) {
                    // Rollover can change the MSB within the batch.
                    state.fill(dst, offset, count, unixTsMs);
                    return;
                }
                msb = state.msb();
                firstLsb = state.lsb() - (count - 1L);
            } finally {
                lock.unlock();
            }
            writeBatch(dst, offset, count, msb, firstLsb);
        }

        void fill(byte[] dst, int offset, int count, long unixTsMs) {
            if (count == 0) {
                return;
            }
            long msb;
            long firstLsb;
            lock.lock();
            try {
                if (!state.tryReserve(count, unixTsMs)) {
                    state.fill(dst, offset, count, unixTsMs);
                    return;
                }
                msb = state.msb();
                firstLsb = state.lsb() - (count - 1L);
            } finally {
                lock.unlock();
            }
            writeBatch(dst, offset, count, msb, firstLsb);
        }
    }

    /**
     * Striped pool for the secure generator, used by virtual threads. This
     * bounds the number of {@link SecureRandom} instances to the stripe count
     * instead of one per virtual thread.
     */
    private static final class SecureStripes {
        static final SecureStripe[] STRIPES = createStripes();
        static final int MASK = STRIPES.length - 1;

        private static SecureStripe[] createStripes() {
            SecureStripe[] stripes = new SecureStripe[stripeCount()];
            for (int i = 0; i < stripes.length; i++) {
                stripes[i] = new SecureStripe();
            }
            return stripes;
        }

        static UUID next(long unixTsMs) {
            return stripe().next(unixTsMs);
        }

        static SecureStripe stripe() {
            return STRIPES[(int) mix64(Thread.currentThread().getId()) & MASK];
        }
    }

    static final class SecureStripe {

        final ReentrantLock lock = new ReentrantLock();
        final SecureGeneratorState state = new SecureGeneratorState();

        UUID nextUnordered(long unixTsMs) {
            lock.lock();
            try {
                return state.nextUnordered(unixTsMs);
            } finally {
                lock.unlock();
            }
        }

        UUID next(long unixTsMs) {
            long msb;
            long lsb;
            lock.lock();
            try {
                state.advance(unixTsMs);
                msb = state.msb();
                lsb = state.lsb();
            } finally {
                lock.unlock();
            }
            return new UUID(msb, lsb);
        }
    }

    /**
     * Leading cache-line padding for {@link GeneratorState}. Generator states
     * are thread-confined, but compacting garbage collectors may relocate two
     * states from different threads onto the same 64-byte cache line, turning
     * every {@code randB} write into cross-core coherence traffic. The
     * pre/post padding (56 bytes each) guarantees the hot fields never share a
     * cache line with a neighboring object, regardless of heap layout. Field
     * layout is reliable because the JVM never interleaves fields across
     * classes in a hierarchy.
     */
    abstract static class GeneratorStatePrePad {
        long pp00, pp01, pp02, pp03, pp04, pp05, pp06;
    }

    abstract static class GeneratorStateFields extends GeneratorStatePrePad {
        long lastUnixTsMs = -1L;
        long splitMix64State;
        int randA;
        long randB;
    }

    /**
     * Per-thread state for the high-throughput generator.
     *
     * <p>The invariants are:</p>
     * <ul>
     *   <li>{@code lastUnixTsMs} is the logical timestamp used for the next UUID
     *   and is always within the 48-bit range.</li>
     *   <li>{@code randA} is randomized whenever the logical timestamp advances
     *   and always fits in 12 bits.</li>
     *   <li>{@code randB} acts as a monotonic counter while the logical timestamp
     *   remains unchanged, is reseeded with its two most significant bits clear
     *   (RFC 9562 Section 6.2), and always fits in 62 bits.</li>
     * </ul>
     *
     * <p>The state is intentionally thread-confined. That removes the need for
     * atomics or locks on the hot path and avoids cache-line contention between
     * producers.</p>
     */
    static final class GeneratorState extends GeneratorStateFields {

        long tp00, tp01, tp02, tp03, tp04, tp05, tp06;

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
         * Produce the next UUID for the supplied (already normalized)
         * wall-clock millisecond.
         */
        UUID next(long unixTsMs) {
            advance(unixTsMs);
            return new UUID(msb(), lsb());
        }

        /**
         * Advance the state for the supplied wall-clock millisecond. If time
         * advances, the random fields are reseeded for the new timestamp. If
         * time stays the same or moves backwards, the previous logical
         * timestamp is kept and the monotonic counter advances instead, so the
         * next value remains greater than the previous one produced by this
         * state instance.
         */
        void advance(long unixTsMs) {
            if (unixTsMs > lastUnixTsMs) {
                lastUnixTsMs = unixTsMs;
                reseed();
            } else {
                incrementCounter();
            }
        }

        /**
         * Reserves a positive-sized batch within one logical millisecond.
         * On success, state describes the last reserved value. On failure,
         * state is unchanged and the caller must handle counter rollover.
         * A freshly seeded counter has enough headroom for any positive int.
         */
        boolean tryReserve(int count, long unixTsMs) {
            if (unixTsMs <= lastUnixTsMs && RAND_B_MASK - randB < count) {
                return false;
            }
            advance(unixTsMs);
            randB += count - 1L;
            return true;
        }

        void fill(long[] dst, int offset, int count, long unixTsMs) {
            if (count == 0) {
                return;
            }
            if (tryReserve(count, unixTsMs)) {
                writeBatch(dst, offset, count, msb(), lsb() - (count - 1L));
                return;
            }
            int idx = offset;
            for (int i = 0; i < count; i++) {
                advance(unixTsMs);
                dst[idx] = msb();
                dst[idx + 1] = lsb();
                idx += 2;
            }
        }

        void fill(byte[] dst, int offset, int count, long unixTsMs) {
            if (count == 0) {
                return;
            }
            if (tryReserve(count, unixTsMs)) {
                writeBatch(dst, offset, count, msb(), lsb() - (count - 1L));
                return;
            }
            int idx = offset;
            for (int i = 0; i < count; i++) {
                advance(unixTsMs);
                LONG_BE.set(dst, idx, msb());
                LONG_BE.set(dst, idx + 8, lsb());
                idx += 16;
            }
        }

        /**
         * No masking needed: the class invariants guarantee every field is
         * already within its RFC 9562 range.
         */
        long msb() {
            return (lastUnixTsMs << 16) | VERSION_BITS | randA;
        }

        long lsb() {
            return VARIANT_BITS | randB;
        }

        /**
         * Reinitialize the random portion for a new logical timestamp.
         *
         * <p>The fast generator uses SplitMix64 output as a per-thread source of
         * high-quality mixed bits. This is a throughput-oriented choice rather
         * than a cryptographic one. The counter seed keeps its two most
         * significant bits clear per RFC 9562 Section 6.2 so that same-millisecond
         * headroom is always at least 2^60.</p>
         */
        private void reseed() {
            randA = (int) nextLong() & RAND_A_MASK;
            randB = nextLong() & RAND_B_SEED_MASK;
        }

        /**
         * Advance the same-timestamp monotonic state.
         *
         * <p>The seeded headroom makes this branch unreachable in practice; it
         * is kept for rigor. On overflow, the generator moves to the next
         * logical millisecond instead of wrapping {@code randB}, which would
         * knowingly violate the monotonicity and uniqueness assumptions for
         * values produced by this state instance.</p>
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
     * <p>This state machine mirrors {@link GeneratorState} but draws its bits
     * from {@link SecureRandom}, both to seed a new timestamp and to choose a
     * positive random increment while the timestamp remains unchanged. That
     * follows the RFC 9562 "monotonic random" style more closely than
     * incrementing by one.</p>
     *
     * <p>Entropy is fetched from the provider in 512-byte blocks and buffered,
     * which amortizes the provider cost and its internal lock over dozens of
     * UUIDs; the earlier design paid a full {@code nextLong()} round-trip per
     * UUID and discarded 54 of the 64 bits on the increment path.</p>
     */
    static final class SecureGeneratorState {

        private static final int BUFFER_SIZE = 512;

        private final SecureRandom secureRandom;
        private final byte[] entropy = new byte[BUFFER_SIZE];
        private int position = BUFFER_SIZE;

        private long lastUnixTsMs = -1L;
        private int randA;
        private long randB;

        SecureGeneratorState() {
            this(new SecureRandom());
        }

        SecureGeneratorState(SecureRandom secureRandom) {
            this.secureRandom = Objects.requireNonNull(secureRandom);
        }

        SecureGeneratorState(SecureRandom secureRandom, long lastUnixTsMs, int randA, long randB) {
            this(secureRandom);
            this.lastUnixTsMs = lastUnixTsMs;
            this.randA = randA & RAND_A_MASK;
            this.randB = randB & RAND_B_MASK;
        }

        UUID nextUnordered(long unixTsMs) {
            int randomA = (int) nextSecureLong() & RAND_A_MASK;
            long randomB = nextSecureLong() & RAND_B_MASK;
            return new UUID((unixTsMs << 16) | VERSION_BITS | randomA,
                    VARIANT_BITS | randomB);
        }

        /**
         * Produce the next UUID for the supplied (already normalized)
         * wall-clock millisecond using a {@link SecureRandom}-backed state
         * transition.
         */
        UUID next(long unixTsMs) {
            advance(unixTsMs);
            return new UUID(msb(), lsb());
        }

        void advance(long unixTsMs) {
            if (unixTsMs > lastUnixTsMs) {
                lastUnixTsMs = unixTsMs;
                reseed();
            } else {
                incrementCounter();
            }
        }

        long msb() {
            return (lastUnixTsMs << 16) | VERSION_BITS | randA;
        }

        long lsb() {
            return VARIANT_BITS | randB;
        }

        /**
         * Reinitialize the UUIDv7 random fields for a new logical timestamp.
         * The counter seed keeps its two most significant bits clear per
         * RFC 9562 Section 6.2.
         */
        private void reseed() {
            randA = (int) nextSecureLong() & RAND_A_MASK;
            randB = nextSecureLong() & RAND_B_SEED_MASK;
        }

        /**
         * Advance the secure monotonic state.
         *
         * <p>The increment is a positive random value instead of a fixed step.
         * This keeps same-millisecond UUIDs ordered, but limits the next value
         * to 1024 candidates for consecutive outputs from the same state and
         * logical timestamp, unless the counter overflows.
         * If the increment would
         * overflow {@code randB}, the generator rolls forward to the next logical
         * millisecond and reseeds.</p>
         */
        private void incrementCounter() {
            long increment = nextSecure10Bits() + 1L;

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

        private long nextSecureLong() {
            if (position + 8 > BUFFER_SIZE) {
                refill();
            }
            long value = (long) LONG_BE.get(entropy, position);
            position += 8;
            return value;
        }

        private int nextSecure10Bits() {
            if (position + 2 > BUFFER_SIZE) {
                refill();
            }
            int value = (((entropy[position] & 0xFF) << 8) | (entropy[position + 1] & 0xFF)) & 0x03FF;
            position += 2;
            return value;
        }

        private void refill() {
            secureRandom.nextBytes(entropy);
            position = 0;
        }
    }
}
