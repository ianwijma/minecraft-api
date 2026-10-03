package dev.example.mapi.internal.http;

import dev.example.mapi.internal.operation.ExecutionMode;
import dev.example.mapi.internal.operation.OperationDescriptor;
import dev.example.mapi.internal.operation.OperationRegistry;
import dev.example.mapi.internal.operation.Scope;
import dev.example.mapi.internal.operation.SideEffectClass;
import java.util.Set;

/** Central operation contract metadata used by authorization and introspection. */
final class HttpApiOperationMetadata {

    private HttpApiOperationMetadata() {}

    static void register(OperationRegistry operations) {
        Set<ExecutionMode> privileged = Set.of(ExecutionMode.PRIVILEGED);
        Set<ExecutionMode> rawInput = Set.of(ExecutionMode.RAW_INPUT);
        operations.register(new OperationDescriptor("client.control.lease",
                "Acquire the exclusive client input control lease",
                Set.of(), false, SideEffectClass.LOCAL, false, Set.of()));
        operations.register(new OperationDescriptor("server.ticks.lease",
                "Acquire the exclusive tick-control lease",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.LOCAL, false, privileged));
        operations.register(new OperationDescriptor("server.ticks.freeze",
                "Freeze the server tick loop",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.unfreeze",
                "Unfreeze the server tick loop",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.rate",
                "Set the target tick rate within configured bounds",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.step",
                "Step a bounded number of simulation ticks",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.step-and-observe",
                "Step a bounded number of simulation ticks and capture a bounded observation",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.sprint",
                "Sprint a bounded number of simulation ticks",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.ticks.stop",
                "Stop stepping or sprinting",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true, privileged));
        operations.register(new OperationDescriptor("server.snapshots.capture",
                "Capture and retain a bounded observation at the current boundary",
                Set.of(), false, SideEffectClass.READ_ONLY, false, Set.of()));
        operations.register(new OperationDescriptor("server.snapshots.diff",
                "Compare two retained snapshots",
                Set.of(), false, SideEffectClass.READ_ONLY, false, Set.of()));
        operations.register(new OperationDescriptor("server.commands.dispatch",
                "Dispatch a server command in the console context (administrative access)",
                Set.of(Scope.OPERATIONS_UNRESTRICTED), false, SideEffectClass.UNRESTRICTED,
                false, privileged));
        operations.register(new OperationDescriptor("process.shutdown",
                "Request a graceful local shutdown of the process (administrative access)",
                Set.of(Scope.OPERATIONS_UNRESTRICTED), false, SideEffectClass.UNRESTRICTED,
                false, privileged));
        operations.register(new OperationDescriptor("client.actions.hold-key",
                "Hold a key for N client ticks (raw-input)",
                Set.of(), false, SideEffectClass.LOCAL, true, rawInput));
        operations.register(new OperationDescriptor("client.movement.waypoints",
                "Execute straight-line waypoints (raw-input; no teleport fallback)",
                Set.of(), false, SideEffectClass.LOCAL, true, rawInput));
        operations.register(new OperationDescriptor("client.ui.click",
                "Click the active screen at GUI coordinates through client logic",
                Set.of(), false, SideEffectClass.LOCAL, true, Set.of(ExecutionMode.CLIENT_LOGIC)));
        operations.register(new OperationDescriptor("client.window.set-windowed",
                "Set windowed dimensions", Set.of(Scope.CLIENT_SETTINGS), false, SideEffectClass.LOCAL,
                false, Set.of()));
        operations.register(new OperationDescriptor("client.window.set-fullscreen",
                "Set fullscreen state", Set.of(Scope.CLIENT_SETTINGS), false, SideEffectClass.LOCAL,
                false, Set.of()));
        operations.register(new OperationDescriptor("client.window.set-gui-scale",
                "Set GUI scale", Set.of(Scope.CLIENT_SETTINGS), false, SideEffectClass.LOCAL,
                false, Set.of()));
        operations.register(new OperationDescriptor("client.worlds.create",
                "Create a fresh world with vanilla defaults (async; poll /server/world)",
                java.util.Set.of(), false, SideEffectClass.GAME, false, Set.of()));
        operations.register(new OperationDescriptor("client.worlds.load",
                "Load a saved singleplayer world (async; poll /server/world)",
                Set.of(), false, SideEffectClass.GAME, false, Set.of()));
        operations.register(new OperationDescriptor("client.worlds.delete",
                "Delete a saved singleplayer world",
                Set.of(Scope.OPERATIONS_DESTRUCTIVE), true,
                SideEffectClass.GAME, false, Set.of()));
        operations.register(new OperationDescriptor("client.inventory.read",
                "Inspect the player's inventory menu slots (§10.3 read path)",
                java.util.Set.of(), false, SideEffectClass.READ_ONLY, false, Set.of()));
        operations.register(new OperationDescriptor("client.inventory.tooltip-rendered",
                "Capture a rendered inventory tooltip",
                java.util.Set.of(), false, SideEffectClass.LOCAL, true, Set.of()));
        operations.register(new OperationDescriptor("client.inventory.click",
                "Dispatch a container click through client logic (server effect is unverified)",
                java.util.Set.of(), false, SideEffectClass.GAME, true, Set.of(ExecutionMode.CLIENT_LOGIC)));
        operations.register(new OperationDescriptor("client.connect",
                "Join a server through the vanilla connect flow (spec §9.2)",
                Set.of(Scope.CLIENT_CONNECT), false, SideEffectClass.GAME,
                true, privileged));
        operations.register(new OperationDescriptor("server.lan.publish",
                "Publish the integrated server to LAN (network exposure; spec §9.3)",
                Set.of(Scope.SERVER_PUBLISH), false, SideEffectClass.GAME, true,
                privileged));

    }
}
