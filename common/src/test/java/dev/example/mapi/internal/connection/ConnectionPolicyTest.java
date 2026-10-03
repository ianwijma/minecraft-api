package dev.example.mapi.internal.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.operation.Scope;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConnectionPolicyTest {

    @Test
    void requestedTargetsUseExactHostAndOptionalPortRules() {
        List<String> allowlist = List.of("play.example.net", "192.0.2.10:25565");
        assertTrue(ConnectionPolicy.matchesRequestedTarget("PLAY.EXAMPLE.NET.", 25565, allowlist));
        assertTrue(ConnectionPolicy.matchesRequestedTarget("192.0.2.10", 25565, allowlist));
        assertFalse(ConnectionPolicy.matchesRequestedTarget("play.example.net.evil", 25565, allowlist));
        assertFalse(ConnectionPolicy.matchesRequestedTarget("play.example.net", 25566, List.of("play.example.net:25565")));
        assertFalse(ConnectionPolicy.matchesRequestedTarget("sub.example.net", 25565, List.of("*.example.net")));
        assertFalse(ConnectionPolicy.matchesRequestedTarget("play.example.net", 0, allowlist));
    }

    @Test
    void resolvedDestinationNeedsItsOwnLiteralIpAndExactPortApproval() {
        Object screen = new Object();
        List<String> allowlist = List.of("play.example.net", "192.0.2.10:25565");
        ConnectionPolicy.withApiControl(Set.of(Scope.CLIENT_CONNECT), allowlist,
                () -> assertTrue(ConnectionPolicy.captureConnect(screen, "play.example.net", 25565)));

        assertThrows(IllegalStateException.class, () -> ConnectionPolicy.withConnectionResolver(screen, () -> {
            ConnectionPolicy.assertResolverAddress("play.example.net", 25565);
            ConnectionPolicy.verifyResolvedDestination("192.0.2.11", 25565);
            return Optional.empty();
        }));

        Object wrongPortScreen = new Object();
        ConnectionPolicy.withApiControl(Set.of(Scope.CLIENT_CONNECT), allowlist,
                () -> ConnectionPolicy.captureConnect(wrongPortScreen, "play.example.net", 25565));
        assertThrows(IllegalStateException.class, () -> ConnectionPolicy.withConnectionResolver(wrongPortScreen, () -> {
            ConnectionPolicy.assertResolverAddress("play.example.net", 25565);
            ConnectionPolicy.verifyResolvedDestination("192.0.2.10", 25566);
            return Optional.empty();
        }));
    }

    @Test
    void srvTargetMustBeAllowlistedAndMatchPinnedResolvedAddress() {
        Object screen = new Object();
        List<String> allowlist = List.of(
                "play.example.net", "srv.example.net:25570", "198.51.100.8:25570");
        ConnectionPolicy.withApiControl(Set.of(Scope.CLIENT_CONNECT), allowlist,
                () -> ConnectionPolicy.captureConnect(screen, "play.example.net", 25565));

        ConnectionPolicy.withConnectionResolver(screen, () -> {
            ConnectionPolicy.assertResolverAddress("play.example.net", 25565);
            ConnectionPolicy.assertResolverAddress("srv.example.net", 25570);
            ConnectionPolicy.verifyResolvedDestination("198.51.100.8", 25570);
            return Optional.empty();
        });

        Object unapprovedSrv = new Object();
        ConnectionPolicy.withApiControl(Set.of(Scope.CLIENT_CONNECT), allowlist,
                () -> ConnectionPolicy.captureConnect(unapprovedSrv, "play.example.net", 25565));
        assertThrows(IllegalStateException.class, () -> ConnectionPolicy.withConnectionResolver(unapprovedSrv, () -> {
            ConnectionPolicy.assertResolverAddress("play.example.net", 25565);
            ConnectionPolicy.assertResolverAddress("other.example.net", 25570);
            return Optional.empty();
        }));
    }

    @Test
    void ipv6TargetsRequireBracketedIpAndExactPortEntries() {
        Object screen = new Object();
        List<String> allowlist = List.of("[2001:db8::10]:25565");
        assertTrue(ConnectionPolicy.matchesRequestedTarget("2001:db8::10", 25565, allowlist));
        ConnectionPolicy.withApiControl(Set.of(Scope.CLIENT_CONNECT), allowlist,
                () -> ConnectionPolicy.captureConnect(screen, "2001:db8::10", 25565));
        ConnectionPolicy.withConnectionResolver(screen, () -> {
            ConnectionPolicy.assertResolverAddress("2001:db8::10", 25565);
            ConnectionPolicy.verifyResolvedDestination("2001:db8:0:0:0:0:0:10", 25565);
            return Optional.empty();
        });
    }

    @Test
    void missingConnectScopeFailsApiJoinAndManualJoinStaysUnchanged() {
        Object deniedScreen = new Object();
        assertThrows(ProblemException.class, () -> ConnectionPolicy.withApiControl(
                Set.of(Scope.SERVER_TICK_CONTROL), List.of("play.example.net"),
                () -> ConnectionPolicy.captureConnect(deniedScreen, "play.example.net", 25565)));

        Object manualScreen = new Object();
        assertFalse(ConnectionPolicy.captureConnect(manualScreen, "blocked.example.net", 25565));
        ConnectionPolicy.withConnectionResolver(manualScreen, () -> {
            ConnectionPolicy.assertResolverAddress("blocked.example.net", 25565);
            ConnectionPolicy.verifyResolvedDestination("203.0.113.77", 25565);
            return Optional.empty();
        });
    }

    @Test
    void apiContextIsAvailableForDispatcherCaptureAndRestoredAfterWork() {
        assertEquals(null, ConnectionPolicy.currentContext());
        ConnectionPolicy.RequestContext expected = ConnectionPolicy.captureContext(
                Set.of(Scope.CLIENT_CONNECT), List.of("play.example.net", "192.0.2.5:25565"));
        ConnectionPolicy.withContext(expected, () -> assertEquals(expected, ConnectionPolicy.currentContext()));
        assertEquals(null, ConnectionPolicy.currentContext());

        Runnable queued = ConnectionPolicy.wrap(expected,
                () -> assertEquals(expected, ConnectionPolicy.currentContext()));
        queued.run();
        assertEquals(null, ConnectionPolicy.currentContext());
    }

    @Test
    void capturedContextIsRemovedAfterResolverReturnsOrFails() {
        Object screen = new Object();
        ConnectionPolicy.withApiControl(Set.of(Scope.CLIENT_CONNECT),
                List.of("play.example.net", "192.0.2.5:25565"),
                () -> ConnectionPolicy.captureConnect(screen, "play.example.net", 25565));
        ConnectionPolicy.withConnectionResolver(screen, () -> {
            ConnectionPolicy.assertResolverAddress("play.example.net", 25565);
            ConnectionPolicy.verifyResolvedDestination("192.0.2.5", 25565);
            return Optional.empty();
        });
        ConnectionPolicy.withConnectionResolver(screen, () -> {
            ConnectionPolicy.assertResolverAddress("ordinary-manual.example", 25565);
            ConnectionPolicy.verifyResolvedDestination("203.0.113.77", 25565);
            return Optional.empty();
        });
    }

    @Test
    void apiConnectionOwnershipFollowsServerTransfersButManualJoinClearsIt() {
        Object minecraft = new Object();
        Object apiScreen = new Object();
        List<String> allowlist = List.of("play.example.net", "transfer.example.net",
                "192.0.2.15:25565", "192.0.2.16:25565");
        ConnectionPolicy.withApiControl(Set.of(Scope.CLIENT_CONNECT), allowlist,
                () -> ConnectionPolicy.captureConnect(apiScreen, minecraft,
                        "play.example.net", 25565, false));

        Object transferScreen = new Object();
        assertTrue(ConnectionPolicy.captureConnect(transferScreen, minecraft,
                "transfer.example.net", 25565, true));
        ConnectionPolicy.withConnectionResolver(transferScreen, () -> {
            ConnectionPolicy.assertResolverAddress("transfer.example.net", 25565);
            ConnectionPolicy.verifyResolvedDestination("192.0.2.16", 25565);
            return Optional.empty();
        });

        Object deniedTransfer = new Object();
        assertThrows(ProblemException.class, () -> ConnectionPolicy.captureConnect(
                deniedTransfer, minecraft, "unapproved.example.net", 25565, true));

        Object manualScreen = new Object();
        assertFalse(ConnectionPolicy.captureConnect(manualScreen, minecraft,
                "manual.example.net", 25565, false));
        Object transferAfterManualJoin = new Object();
        assertFalse(ConnectionPolicy.captureConnect(transferAfterManualJoin, minecraft,
                "unapproved.example.net", 25565, true));
    }

    @Test
    void connectionAndTransferBoundariesRecheckControlOwnership() {
        Object minecraft = new Object();
        Object screen = new Object();
        AtomicBoolean held = new AtomicBoolean(true);
        Runnable requireControl = () -> {
            if (!held.get()) throw new ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.LEASE_REQUIRED,
                    "input lease is no longer held");
        };
        ConnectionPolicy.withContext(ConnectionPolicy.captureContext(
                Set.of(Scope.CLIENT_CONNECT), List.of("play.example.net", "192.0.2.20:25565"),
                requireControl), () -> ConnectionPolicy.captureConnect(
                        screen, minecraft, "play.example.net", 25565, false));

        held.set(false);
        assertThrows(ProblemException.class, () -> ConnectionPolicy.withConnectionResolver(screen, () -> {
            ConnectionPolicy.assertResolverAddress("play.example.net", 25565);
            return Optional.empty();
        }));

        Object transferScreen = new Object();
        assertThrows(ProblemException.class, () -> ConnectionPolicy.captureConnect(
                transferScreen, minecraft, "play.example.net", 25565, true));
    }
}
