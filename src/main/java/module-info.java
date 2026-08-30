/**
 * UUIDv7 generation, as a named JPMS module.
 *
 * <p>The descriptor exists so that consumers on the module path name this library by a stable
 * identifier. Without it the module name is derived from the jar's FILE NAME, which is not a
 * contract: the compiler warns against publishing anything that requires such a module, and a
 * consumer's module graph would break on a rename.
 */
module io.github.robsonkades.uuidv7 {
    exports io.github.robsonkades.uuidv7;
}
