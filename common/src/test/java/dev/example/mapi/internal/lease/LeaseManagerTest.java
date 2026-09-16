package dev.example.mapi.internal.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LeaseManagerTest {

    private LeaseManager manager = new LeaseManager();

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void leaseIsExclusivePerTopic() {
        ControlLease first = manager.acquire("input", "runner-a", 10_000);
        ProblemException e = assertThrows(ProblemException.class,
                () -> manager.acquire("input", "runner-b", 10_000));
        assertEquals(ProblemCode.LEASE_HELD, e.code());
        assertEquals(409, ProblemCode.LEASE_HELD.httpStatus());
        assertEquals("runner-a", e.details().get("owner"));

        // Different topics do not collide.
        ControlLease other = manager.acquire("tick-control", "runner-a", 10_000);
        assertEquals("tick-control", other.topic());
        assertEquals("input", first.topic());
    }

    @Test
    void releaseFreesTheTopicImmediately() {
        ControlLease lease = manager.acquire("input", "runner-a", 10_000);
        manager.release(lease);
        assertTrue(lease.revoked());
        ControlLease next = manager.acquire("input", "runner-b", 10_000);
        assertEquals("runner-b", next.owner());
    }

    @Test
    void renewExtendsAndRejectsRevokedLeases() {
        ControlLease lease = manager.acquire("input", "runner-a", 200);
        long before = lease.expiresAtEpochMs();
        manager.renew(lease, 10_000);
        assertTrue(lease.expiresAtEpochMs() >= before + 9_000);

        manager.revokeAll("test");
        ProblemException e = assertThrows(ProblemException.class,
                () -> manager.renew(lease, 10_000));
        assertEquals(ProblemCode.LEASE_REQUIRED, e.code());
    }

    @Test
    void watchdogRevokesExpiredLeasesAndNotifiesListeners() throws Exception {
        List<ControlLease> expired = new CopyOnWriteArrayList<>();
        manager.onExpiry(expired::add);
        ControlLease lease = manager.acquire("input", "runner-a", 100);
        assertTrue(lease.held(System.currentTimeMillis()));

        long until = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < until && manager.holderOf("input").isPresent()) {
            Thread.sleep(25);
        }
        assertTrue(lease.revoked());
        assertTrue(manager.holderOf("input").isEmpty());
        assertEquals(List.of(lease), expired);
        assertEquals("input", expired.get(0).topic());
    }

    @Test
    void activeLeasesReportsOnlyHeldLeases() throws Exception {
        ControlLease a = manager.acquire("input", "a", 10_000);
        ControlLease b = manager.acquire("tick-control", "b", 60);
        assertEquals(2, manager.activeLeases().size());
        long until = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < until && manager.activeLeases().size() == 2) {
            Thread.sleep(25);
        }
        assertEquals(List.of(a), manager.activeLeases());
        assertFalse(b.held(System.currentTimeMillis()));
    }

    @Test
    void revokeAllRevokesEverythingAndNotifiesOnce() {
        List<ControlLease> notified = new CopyOnWriteArrayList<>();
        manager.onExpiry(notified::add);
        manager.acquire("input", "a", 10_000);
        manager.acquire("tick-control", "b", 10_000);
        assertEquals(2, manager.revokeAll("emergency"));
        assertEquals(2, notified.size());
        assertEquals(0, manager.activeLeases().size());
        assertEquals(0, manager.revokeAll("emergency again"));
    }

    @Test
    void shutdownRevokesHeldLeases() {
        ControlLease lease = manager.acquire("input", "a", 10_000);
        manager.shutdown();
        assertTrue(lease.revoked());
        assertTrue(manager.activeLeases().isEmpty());
    }

    @Test
    void invalidArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> manager.acquire(" ", "a", 100));
        assertThrows(IllegalArgumentException.class, () -> manager.acquire("input", " ", 100));
        assertThrows(IllegalArgumentException.class, () -> manager.acquire("input", "a", 0));
        assertThrows(NullPointerException.class, () -> manager.acquire(null, "a", 100));
    }
}
