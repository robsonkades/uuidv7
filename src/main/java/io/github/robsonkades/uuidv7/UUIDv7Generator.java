package io.github.robsonkades.uuidv7;

import java.util.Objects;
import java.util.UUID;

/**
 * Single-threaded UUIDv7 generator instance.
 *
 * <p>This class produces exactly the same RFC 9562-compliant values as
 * {@link UUIDv7#randomUUID()}, but the generator state is owned by the caller
 * instead of being looked up through a {@link ThreadLocal} on every call.
 * For code that already confines work to a single thread — event-loop
 * handlers, actors, partitioned consumers — this removes the per-call
 * thread-local map probe from the hot path.</p>
 *
 * <p><strong>Instances are NOT thread-safe.</strong> Each instance must be
 * confined to a single thread, or all access must be externally synchronized.
 * Sharing an instance across threads without synchronization can produce
 * duplicate or non-monotonic values. When in doubt, use the static
 * {@link UUIDv7#randomUUID()} API, which is always safe.</p>
 *
 * <p>Values produced by one instance are strictly increasing, including when
 * multiple UUIDs are created within the same millisecond and when the wall
 * clock moves backwards.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // one generator per event-loop thread / partition
 * UUIDv7Generator generator = UUIDv7Generator.create();
 *
 * UUID id = generator.next();
 * String key = generator.nextString();
 *
 * long[] batch = new long[2 * 1024];
 * generator.fill(batch, 0, 1024); // zero allocations
 * }</pre>
 *
 * @see UUIDv7
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9562.html">RFC 9562</a>
 */
public final class UUIDv7Generator {

    private final UUIDv7.GeneratorState state;

    private UUIDv7Generator(UUIDv7.GeneratorState state) {
        this.state = state;
    }

    /**
     * Creates a new independently seeded generator.
     *
     * @return a new generator instance; the instance is not thread-safe
     */
    public static UUIDv7Generator create() {
        return new UUIDv7Generator(UUIDv7.newGeneratorState());
    }

    /**
     * Generates the next UUIDv7.
     *
     * @return a new RFC 9562-compliant UUIDv7, strictly greater than the
     *         previous value produced by this instance
     * @throws IllegalStateException if the system time exceeds the representable
     *         48-bit UUIDv7 timestamp range
     */
    public UUID next() {
        return state.next(UUIDv7.currentUnixTimestamp());
    }

    /**
     * Generates the next UUIDv7 and returns its canonical 36-character string
     * form through the JDK UUID formatter.
     *
     * @return the canonical lowercase hexadecimal representation of a new
     *         RFC 9562-compliant UUIDv7
     * @throws IllegalStateException if the system time exceeds the representable
     *         48-bit UUIDv7 timestamp range
     */
    public String nextString() {
        state.advance(UUIDv7.currentUnixTimestamp());
        return UUIDv7.toCanonicalString(state.msb(), state.lsb());
    }

    /**
     * Generates {@code count} UUIDv7 values into {@code dst} as
     * {@code (mostSignificantBits, leastSignificantBits)} pairs, starting at
     * {@code offset}. Writes {@code 2 * count} longs and allocates nothing.
     * The wall clock is read once for the whole batch and values are strictly
     * increasing within the batch.
     *
     * @param dst    destination array
     * @param offset index of the first long written
     * @param count  number of UUIDs to generate
     * @throws IndexOutOfBoundsException if the range
     *         {@code [offset, offset + 2 * count)} is not within {@code dst}
     * @throws IllegalStateException if the system time exceeds the representable
     *         48-bit UUIDv7 timestamp range
     */
    public void fill(long[] dst, int offset, int count) {
        Objects.checkFromIndexSize(offset, Math.multiplyExact(count, 2), dst.length);
        if (count == 0) {
            return;
        }
        state.fill(dst, offset, count, UUIDv7.currentUnixTimestamp());
    }

    /**
     * Generates {@code count} UUIDv7 values into {@code dst} in the RFC 9562
     * big-endian binary layout (16 bytes per UUID), starting at {@code offset}.
     * Writes {@code 16 * count} bytes and allocates nothing. The wall clock is
     * read once for the whole batch and values are strictly increasing within
     * the batch.
     *
     * @param dst    destination array
     * @param offset index of the first byte written
     * @param count  number of UUIDs to generate
     * @throws IndexOutOfBoundsException if the range
     *         {@code [offset, offset + 16 * count)} is not within {@code dst}
     * @throws IllegalStateException if the system time exceeds the representable
     *         48-bit UUIDv7 timestamp range
     */
    public void fill(byte[] dst, int offset, int count) {
        Objects.checkFromIndexSize(offset, Math.multiplyExact(count, 16), dst.length);
        if (count == 0) {
            return;
        }
        state.fill(dst, offset, count, UUIDv7.currentUnixTimestamp());
    }
}
