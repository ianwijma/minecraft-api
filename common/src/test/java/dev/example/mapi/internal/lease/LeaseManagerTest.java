package dev.example.mapi.internal.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.lease.LeaseManager.LeaseHeldException;
import dev.example.mapi.internal.lease.LeaseManager.Snapshot;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LeaseManagerTest {

    private static final Logger LOG = LoggerFactory.getLogger(LeaseManagerTest.class);

    @Test
    void acquireGrantRenewAndRelease() {
        LeaseManager manager = new LeaseManager(LOG, null);
        Snapshot lease = manager.acquire("client.input", 5_000L, null, "holder-1");
        assertEquals("held", lease.state());
        Snapshot renewed = manager.renew(lease.id(), 60_000L);
        assertTrue(renewed.expiresAtEpochMs() > lease.expiresAtEpochMs());
        assertEquals("released", manager.release(lease.id()).state());
        assertThrows(IllegalStateException.class, () -> manager.release(lease.id()));
    }

    @Test
    void rejectPolicyThrowsLeaseHeld() {
        LeaseManager manager = new LeaseManager(LOG, null);
        manager.acquire("client.ui", 60_000L, null, "holder-1");
        LeaseHeldException e = assertThrows(LeaseHeldException.class,
                () -> manager.acquire("client.ui", 60_000L, "reject", "holder-2"));
        assertEquals("held", e.held.state());
    }

    @Test
    void preemptRevokesCurrentHolder() {
        List<Snapshot> events = new CopyOnWriteArrayList<>();
        LeaseManager manager = new LeaseManager(LOG, events::add);
        Snapshot first = manager.acquire("client.camera", 60_000L, null, "holder-1");
        Snapshot second = manager.acquire("client.camera", 60_000L, "preempt", "holder-2");
        assertEquals("held", second.state());
        assertEquals("preempted", manager.get(first.id()).state());
        assertTrue(events.stream().anyMatch(s -> s.id().equals(first.id()) && s.state().equals("preempted")));
    }

    @Test
    void queuedLeaseActivatesWhenCurrentReleased() {
        LeaseManager manager = new LeaseManager(LOG, null);
        Snapshot first = manager.acquire("world.bulkEdit", 60_000L, null, "holder-1");
        Snapshot queued = manager.acquire("world.bulkEdit", 60_000L, "queue", "holder-2");
        assertEquals("queued", queued.state());
        manager.release(first.id());
        assertEquals("held", manager.get(queued.id()).state(), "queue head must activate on release");
    }

    @Test
    void expiryRunsHookAndPromotesQueue() throws Exception {
        List<String> hooks = new CopyOnWriteArrayList<>();
        LeaseManager manager = new LeaseManager(LOG, null);
        manager.registerExpiryHook("client.input", () -> hooks.add("released-keys"));
        Snapshot shortLease = manager.acquire("client.input", 1_000L, null, "holder-1");
        Snapshot queued = manager.acquire("client.input", 60_000L, "queue", "holder-2");
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && !"expired".equals(manager.get(shortLease.id()).state())) {
            Thread.sleep(50);
        }
        assertEquals("expired", manager.get(shortLease.id()).state());
        assertEquals("held", manager.get(queued.id()).state(), "queue must activate on expiry");
        assertTrue(hooks.contains("released-keys"), "expiry hook must run");
    }

    @Test
    void unknownTypeAndPolicyAreRejected() {
        LeaseManager manager = new LeaseManager(LOG, null);
        assertThrows(IllegalArgumentException.class,
                () -> manager.acquire("bogus.lease", null, null, "h"));
        manager.acquire("server.tick", null, null, "h");
        assertThrows(IllegalArgumentException.class,
                () -> manager.acquire("server.tick", null, "nuclear", "h"));
    }

    @Test
    void releaseAllReleasesHeldLeases() {
        LeaseManager manager = new LeaseManager(LOG, null);
        manager.acquire("client.input", 60_000L, null, "h");
        manager.acquire("server.tick", 60_000L, null, "h");
        manager.releaseAll();
        assertTrue(manager.list().stream().allMatch(s -> "released".equals(s.state())));
    }
}
