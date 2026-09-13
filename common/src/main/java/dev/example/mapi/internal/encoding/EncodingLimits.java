package dev.example.mapi.internal.encoding;

/**
 * Structural limits for encoded payloads (spec §11.2: reject oversized/deep
 * payloads before game-thread work).
 *
 * @param maxDepth   maximum nesting depth (a bare value is depth 1)
 * @param maxNodes   maximum total number of values in one tree
 * @param maxStringChars maximum characters in one string value
 */
public record EncodingLimits(int maxDepth, int maxNodes, int maxStringChars) {

    /** Default limits. */
    public static final EncodingLimits DEFAULT = new EncodingLimits(32, 100_000, 1_000_000);

    public EncodingLimits {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1");
        }
        if (maxNodes < 1) {
            throw new IllegalArgumentException("maxNodes must be at least 1");
        }
        if (maxStringChars < 0) {
            throw new IllegalArgumentException("maxStringChars must not be negative");
        }
    }
}
