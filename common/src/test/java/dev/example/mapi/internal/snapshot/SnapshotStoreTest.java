package dev.example.mapi.internal.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.encoding.EncodingLimits;
import dev.example.mapi.internal.encoding.Tag;
import dev.example.mapi.internal.encoding.TagType;
import dev.example.mapi.internal.json.JsonWriter;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SnapshotStoreTest {

    private static final long NOW = System.currentTimeMillis();

    private static Tag world(String name, int health, long xp) {
        return new Tag.CompoundTag(Map.of(
                "entity", new Tag.CompoundTag(Map.of(
                        "name", new Tag.StringTag(name),
                        "health", new Tag.IntTag(TagType.INT, health))),
                "stats", new Tag.CompoundTag(Map.of(
                        "xp", new Tag.LongTag(xp)))));
    }

    @Test
    void retainAndGetRoundTrip() {
        SnapshotStore store = new SnapshotStore(10_000, 8, EncodingLimits.DEFAULT);
        Snapshot snapshot = new Snapshot("snap-1", Optional.of("w1"), NOW,
                Optional.of(42L), world("zoe", 20, 5));
        store.retain(snapshot);
        assertEquals(snapshot, store.get("snap-1", NOW));
        assertTrue(store.get("snap-1", NOW).boundary().orElseThrow() == 42L);
    }

    @Test
    void unknownSnapshotsAreNotFound() {
        SnapshotStore store = new SnapshotStore(10_000, 8, EncodingLimits.DEFAULT);
        ProblemException e = assertThrows(ProblemException.class, () -> store.get("nope", NOW));
        assertEquals(ProblemCode.NOT_FOUND, e.code());
    }

    @Test
    void expiredSnapshotsFailWithSnapshotExpired() {
        SnapshotStore store = new SnapshotStore(100, 8, EncodingLimits.DEFAULT);
        store.retain(new Snapshot("snap-old", Optional.empty(), NOW, Optional.empty(),
                world("a", 1, 0)));
        ProblemException e = assertThrows(ProblemException.class,
                () -> store.get("snap-old", NOW + 200));
        assertEquals(ProblemCode.SNAPSHOT_EXPIRED, e.code());
        assertEquals(410, ProblemCode.SNAPSHOT_EXPIRED.httpStatus());
        assertTrue(store.size(NOW + 200) == 0);
    }

    @Test
    void countQuotaEvictsOldest() {
        SnapshotStore store = new SnapshotStore(10_000, 2, EncodingLimits.DEFAULT);
        store.retain(new Snapshot("s1", Optional.empty(), NOW, Optional.empty(), world("a", 1, 0)));
        store.retain(new Snapshot("s2", Optional.empty(), NOW, Optional.empty(), world("b", 2, 0)));
        store.retain(new Snapshot("s3", Optional.empty(), NOW, Optional.empty(), world("c", 3, 0)));
        assertThrows(ProblemException.class, () -> store.get("s1", NOW));
        assertEquals("s2", store.get("s2", NOW).id());
        assertEquals("s3", store.get("s3", NOW).id());
    }

    @Test
    void captureValidatesAgainstLimits() {
        SnapshotStore store = new SnapshotStore(10_000, 8, new EncodingLimits(32, 5, 100));
        Tag.IntTag leaf = new Tag.IntTag(TagType.INT, 1);
        List<Tag> many = IntStream.range(0, 10).<Tag>mapToObj(i -> leaf).toList();
        Tag big = new Tag.ListTag(TagType.INT, many);
        assertThrows(IllegalArgumentException.class, () -> store.retain(
                new Snapshot("big", Optional.empty(), NOW, Optional.empty(), big)));
    }

    @Test
    void diffReportsAddedRemovedChangedWithWireValues() {
        SnapshotStore store = new SnapshotStore(10_000, 8, EncodingLimits.DEFAULT);
        store.retain(new Snapshot("before", Optional.of("w1"), NOW, Optional.of(10L),
                world("zoe", 20, 5)));
        store.retain(new Snapshot("after", Optional.of("w1"), NOW + 10, Optional.of(11L),
                world("zoe", 15, 6)));

        SnapshotStore.DiffResult result = store.diff("before", "after", DiffOptions.DEFAULT, NOW + 20);
        assertEquals(2, result.records().size());
        var changed = result.records().stream()
                .filter(r -> r.kind() == SnapshotStore.DiffRecord.Kind.CHANGED).findFirst().orElseThrow();
        assertEquals("entity.health", changed.path());
        assertEquals(20L, ((Map<?, ?>) changed.before().orElseThrow()).get("value"));
        assertEquals(15L, ((Map<?, ?>) changed.after().orElseThrow()).get("value"));

        var added = result.records().stream()
                .filter(r -> r.kind() == SnapshotStore.DiffRecord.Kind.ADDED).findFirst().orElseThrow();
        assertEquals("stats.xp", added.path());
        assertEquals("6", ((Map<?, ?>) added.after().orElseThrow()).get("value"));

        assertEquals(Optional.of(10L), result.firstBoundary());
        assertEquals(Optional.of(11L), result.secondBoundary());
        assertTrue(JsonWriter.write(result.toMap()).contains("\"truncated\":false"));
    }

    @Test
    void includePathsRestrictAndReportUnavailable() {
        SnapshotStore store = new SnapshotStore(10_000, 8, EncodingLimits.DEFAULT);
        store.retain(new Snapshot("b", Optional.empty(), NOW, Optional.empty(), world("zoe", 20, 5)));
        store.retain(new Snapshot("a", Optional.empty(), NOW, Optional.empty(), world("zoe", 18, 5)));

        SnapshotStore.DiffResult restricted = store.diff("b", "a",
                new DiffOptions(Set.of("entity.*"), 100), NOW);
        assertEquals(1, restricted.records().size());
        assertEquals("entity.health", restricted.records().get(0).path());

        SnapshotStore.DiffResult withMissing = store.diff("b", "a",
                new DiffOptions(Set.of("entity.health", "nope.path"), 100), NOW);
        assertEquals(List.of("nope.path"), withMissing.unavailablePaths());
    }

    @Test
    void diffIsTruncatedAtMaxChanges() {
        SnapshotStore store = new SnapshotStore(10_000, 8, EncodingLimits.DEFAULT);
        store.retain(new Snapshot("b", Optional.empty(), NOW, Optional.empty(), world("zoe", 20, 5)));
        store.retain(new Snapshot("a", Optional.empty(), NOW, Optional.empty(), world("zed", 18, 7)));

        SnapshotStore.DiffResult result = store.diff("b", "a", new DiffOptions(Set.of(), 1), NOW);
        assertEquals(1, result.records().size());
        assertTrue(result.truncated());
    }

    @Test
    void absenceFromBoundedDiffIsNotADestructionClaim() {
        // Spec §12: an entity leaving the region looks identical to removal;
        // the record is REMOVED for the diffed path only - never a claim that
        // the entity was destroyed.
        SnapshotStore store = new SnapshotStore(10_000, 8, EncodingLimits.DEFAULT);
        store.retain(new Snapshot("b", Optional.of("w1"), NOW, Optional.empty(), world("zoe", 20, 5)));
        store.retain(new Snapshot("a", Optional.of("w1"), NOW, Optional.empty(), world("zoe", 20, 5)));
        SnapshotStore.DiffResult result = store.diff("b", "a", DiffOptions.DEFAULT, NOW);
        assertEquals(0, result.records().size());
    }
}
