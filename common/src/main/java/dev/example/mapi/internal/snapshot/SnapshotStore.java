package dev.example.mapi.internal.snapshot;

import dev.example.mapi.internal.encoding.Tag;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Bounded server-side snapshot store and diff engine (spec §12). Retention is
 * enforced by count and TTL; expired comparisons fail with
 * {@code SNAPSHOT_EXPIRED}. Diffing walks the canonical typed trees and
 * produces path-level added/removed/changed records with before/after wire
 * values, truncation info, unavailable include paths, and coverage changes.
 *
 * <p>Absence from a bounded diff does not prove an entity was destroyed —
 * it may have left the diffed region or stopped matching a filter; callers
 * get coverage-change records instead of misleading conclusions.
 */
public final class SnapshotStore {

    /** One diff record. */
    public record DiffRecord(String path, Kind kind, Optional<Object> before, Optional<Object> after) {

        /** Record kind. */
        public enum Kind {
            ADDED("added"),
            REMOVED("removed"),
            CHANGED("changed");

            private final String wireName;

            Kind(String wireName) {
                this.wireName = wireName;
            }

            /** @return the exact string used on the wire */
            public String wireName() {
                return wireName;
            }
        }

        /** @return the record as an ordered map for JSON serialization */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("path", path);
            map.put("kind", kind.wireName());
            before.ifPresent(value -> map.put("before", value));
            after.ifPresent(value -> map.put("after", value));
            return map;
        }
    }

    /** Result of a bounded diff. */
    public record DiffResult(
            String firstId, String secondId, List<DiffRecord> records, boolean truncated,
            List<String> unavailablePaths, Optional<Long> firstBoundary, Optional<Long> secondBoundary) {

        /** @return the result as an ordered map for JSON serialization */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("firstId", firstId);
            map.put("secondId", secondId);
            map.put("records", records.stream().map(DiffRecord::toMap).toList());
            map.put("truncated", truncated);
            if (!unavailablePaths.isEmpty()) {
                map.put("unavailablePaths", unavailablePaths);
            }
            firstBoundary.ifPresent(value -> map.put("firstBoundary", value));
            secondBoundary.ifPresent(value -> map.put("secondBoundary", value));
            return map;
        }
    }

    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final ArrayDeque<String> order = new ArrayDeque<>();
    private final long ttlMs;
    private final int maxCount;
    private final dev.example.mapi.internal.encoding.EncodingLimits limits;

    /**
     * @param ttlMs   retention period per snapshot in milliseconds
     * @param maxCount maximum number of retained snapshots (FIFO beyond this)
     * @param limits  structural limits enforced at capture
     */
    public SnapshotStore(long ttlMs, int maxCount, dev.example.mapi.internal.encoding.EncodingLimits limits) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        if (maxCount < 1) {
            throw new IllegalArgumentException("maxCount must be at least 1");
        }
        this.ttlMs = ttlMs;
        this.maxCount = maxCount;
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    /**
     * Retains a snapshot after validating its content against the limits.
     *
     * @param snapshot the snapshot to retain, never {@code null}
     * @throws IllegalArgumentException when the content exceeds the limits
     */
    public void retain(Snapshot snapshot) {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        dev.example.mapi.internal.encoding.TagJson.validate(snapshot.content(), limits);
        evictExpired(System.currentTimeMillis());
        while (order.size() >= maxCount) {
            snapshots.remove(order.pollFirst());
        }
        snapshots.put(snapshot.id(), snapshot);
        order.addLast(snapshot.id());
    }

    /**
     * @param id snapshot identifier
     * @param now current wall time (injectable for tests)
     * @return the retained snapshot
     * @throws dev.example.mapi.internal.problem.ProblemException with
     *     {@code SNAPSHOT_EXPIRED} when the snapshot existed but aged out, or
     *     {@code NOT_FOUND} when it was never retained (or already evicted by
     *     the count quota)
     */
    public Snapshot get(String id, long now) {
        Snapshot snapshot = snapshots.get(id);
        if (snapshot == null) {
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.NOT_FOUND,
                    "unknown snapshot: " + id);
        }
        if (now - snapshot.capturedAtEpochMs() > ttlMs) {
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.SNAPSHOT_EXPIRED,
                    "snapshot retention period elapsed: " + id);
        }
        return snapshot;
    }

    /**
     * Computes a bounded diff between two retained snapshots.
     *
     * @param firstId  older snapshot id
     * @param secondId newer snapshot id
     * @param options  diff options
     * @param now      current wall time (injectable for tests)
     * @return the diff result
     * @throws dev.example.mapi.internal.problem.ProblemException when either
     *     snapshot is unknown or expired
     */
    public DiffResult diff(String firstId, String secondId, DiffOptions options, long now) {
        Snapshot first = get(firstId, now);
        Snapshot second = get(secondId, now);
        Map<String, Tag> left = flatten(first.content(), options);
        Map<String, Tag> right = flatten(second.content(), options);

        List<DiffRecord> records = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (String path : options.includePaths()) {
            if (!left.containsKey(path) && !right.containsKey(path) && !hasPrefix(left, path)
                    && !hasPrefix(right, path)) {
                unavailable.add(path);
            }
        }
        for (Map.Entry<String, Tag> entry : new TreeMap<>(left).entrySet()) {
            String path = entry.getKey();
            Tag after = right.get(path);
            if (after == null) {
                records.add(new DiffRecord(path, DiffRecord.Kind.REMOVED,
                        Optional.of(dev.example.mapi.internal.encoding.TagJson.toWire(entry.getValue())),
                        Optional.empty()));
            } else if (!after.equals(entry.getValue())) {
                records.add(new DiffRecord(path, DiffRecord.Kind.CHANGED,
                        Optional.of(dev.example.mapi.internal.encoding.TagJson.toWire(entry.getValue())),
                        Optional.of(dev.example.mapi.internal.encoding.TagJson.toWire(after))));
            }
        }
        for (Map.Entry<String, Tag> entry : new TreeMap<>(right).entrySet()) {
            if (!left.containsKey(entry.getKey())) {
                records.add(new DiffRecord(entry.getKey(), DiffRecord.Kind.ADDED,
                        Optional.empty(),
                        Optional.of(dev.example.mapi.internal.encoding.TagJson.toWire(entry.getValue()))));
            }
        }
        boolean truncated = records.size() > options.maxChanges();
        List<DiffRecord> kept = truncated ? records.subList(0, options.maxChanges()) : records;
        return new DiffResult(firstId, secondId, List.copyOf(kept), truncated, unavailable,
                first.boundary(), second.boundary());
    }

    /** @return number of currently retained snapshots (post-eviction) */
    public int size(long now) {
        evictExpired(now);
        return snapshots.size();
    }

    /**
     * Invalidates every retained snapshot scoped to a world (spec §6).
     *
     * @param worldSessionId the world that unloaded
     * @return the number of invalidated snapshots
     */
    public int invalidateWorld(String worldSessionId) {
        java.util.Objects.requireNonNull(worldSessionId, "worldSessionId");
        int removed = 0;
        synchronized (order) {
            for (String id : order.toArray(String[]::new)) {
                Snapshot snapshot = snapshots.get(id);
                if (snapshot != null
                        && worldSessionId.equals(snapshot.worldSessionId().orElse(null))) {
                    snapshots.remove(id);
                    order.remove(id);
                    removed++;
                }
            }
        }
        return removed;
    }

    private boolean hasPrefix(Map<String, Tag> map, String prefixPattern) {
        if (!prefixPattern.endsWith(".*")) {
            return false;
        }
        String prefix = prefixPattern.substring(0, prefixPattern.length() - 1);
        return map.keySet().stream().anyMatch(path -> path.startsWith(prefix));
    }

    private Map<String, Tag> flatten(Tag root, DiffOptions options) {
        Map<String, Tag> leaves = new LinkedHashMap<>();
        if (!(root instanceof Tag.CompoundTag compound)) {
            leaves.put("", root);
            return leaves;
        }
        flattenInto("", compound, leaves, options);
        return leaves;
    }

    private void flattenInto(String prefix, Tag.CompoundTag compound,
            Map<String, Tag> leaves, DiffOptions options) {
        for (Map.Entry<String, Tag> entry : compound.entries().entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Tag value = entry.getValue();
            if (value instanceof Tag.CompoundTag nested) {
                if (options.shouldDescend(path)) {
                    flattenInto(path, nested, leaves, options);
                }
            } else if (options.includes(path)) {
                leaves.put(path, value);
            }
        }
    }

    private void evictExpired(long now) {
        while (!order.isEmpty()) {
            Snapshot oldest = snapshots.get(order.peekFirst());
            if (oldest != null && now - oldest.capturedAtEpochMs() > ttlMs) {
                snapshots.remove(order.pollFirst());
            } else {
                break;
            }
        }
    }
}
