/**
 * UUID version 7 generation utilities.
 *
 * <p>This package provides a compact, dependency-free UUIDv7 generator for
 * Java 17. The public entry point is {@link io.github.robsonkades.uuidv7.UUIDv7},
 * which offers a fast non-cryptographic generator, a secure-entropy monotonic
 * generator, and an unordered generator with 74 fresh random payload bits.</p>
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>full compliance with the UUIDv7 layout defined by RFC 9562</li>
 *   <li>very high throughput under single-threaded and concurrent workloads</li>
 *   <li>minimal allocation pressure beyond the returned {@link java.util.UUID}</li>
 *   <li>defensive handling of same-millisecond generation, clock rollback, and
 *   counter exhaustion</li>
 * </ul>
 *
 * <p>The fast generator is intended for production identifiers such as primary
 * keys, event identifiers, and trace correlation IDs. Secure monotonic values
 * use bounded increments, which limit next-value unpredictability. Use
 * {@link io.github.robsonkades.uuidv7.UUIDv7#secureUnorderedUUID()} for fresh
 * random payloads without same-millisecond ordering. UUIDv7 is not a
 * secret-bearing token format; the embedded timestamp remains observable.</p>
 *
 * @see io.github.robsonkades.uuidv7.UUIDv7
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9562.html">RFC 9562</a>
 */
package io.github.robsonkades.uuidv7;
