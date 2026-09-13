package dev.example.mapi.internal.snapshot;

import java.util.Set;

/**
 * Options for a bounded snapshot diff (spec §12). Include paths are dotted
 * leaf selectors into the snapshot tree (for example
 * {@code entities.*, players}, or {@code inventory.slots}); an empty include
 * set diffs everything. At most {@code maxChanges} records are returned; the
 * result reports truncation. Coverage changes (paths present in one snapshot
 * only) surface as added/removed records.
 *
 * @param includePaths dotted leaf paths to include; empty means all
 * @param maxChanges   maximum diff records returned, at least 1
 */
public record DiffOptions(Set<String> includePaths, int maxChanges) {

    /** Default options: everything, up to 1000 records. */
    public static final DiffOptions DEFAULT = new DiffOptions(Set.of(), 1000);

    public DiffOptions {
        includePaths = Set.copyOf(includePaths == null ? Set.of() : includePaths);
        if (maxChanges < 1) {
            throw new IllegalArgumentException("maxChanges must be at least 1");
        }
    }

    boolean includes(String path) {
        if (includePaths.isEmpty()) {
            return true;
        }
        if (includePaths.contains(path)) {
            return true;
        }
        for (String pattern : includePaths) {
            if (pattern.endsWith(".*") && path.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param path a branch (compound) path
     * @return true when the branch itself is included or any include pattern
     *     targets its children
     */
    boolean shouldDescend(String path) {
        if (includePaths.isEmpty() || includes(path)) {
            return true;
        }
        for (String pattern : includePaths) {
            if (pattern.startsWith(path + ".")) {
                return true;
            }
        }
        return false;
    }
}
