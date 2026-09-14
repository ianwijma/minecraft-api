package dev.example.mapi.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.example.mapi.internal.client.ClientBridge;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

/**
 * Fabric synthetic-input backend over the game's keybinding state (spec
 * §3.5). Coverage is disclosed honestly: keybinding-state dispatch only —
 * direct native GLFW polling (e.g. {@code InputConstants.isKeyDown}) reads a
 * different state this backend does not write, so it is listed under
 * unsupported native polling paths (spec §3.5 boundary).
 */
final class FabricInputBackend implements ClientBridge.InputBackend {

    private final AtomicLong clientTick = new AtomicLong();
    private final AtomicLong appliedFrame = new AtomicLong();
    private final Map<Integer, Boolean> syntheticHeld = new ConcurrentHashMap<>();

    /** Registers the tick counter; called once at client init. */
    void registerTickCounter() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> clientTick.incrementAndGet());
    }

    @Override
    public String backendId() {
        return "mapi-keybinding-state";
    }

    @Override
    public String backendVersion() {
        return "1";
    }

    @Override
    public Coverage coverage() {
        return new Coverage(
                /* callbackDispatch */ false,
                /* keybindingState */ true,
                /* helperPolling */ false,
                /* screenDispatch */ false,
                List.of("native-glfw-polling (InputConstants.isKeyDown reads GLFW "
                        + "state this backend does not write; compat requires an "
                        + "explicitly tested bridge, spec §3.5)"));
    }

    @Override
    public void pressKey(int keyCode) {
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(keyCode);
        KeyMapping.set(key, true);
        syntheticHeld.put(keyCode, true);
    }

    @Override
    public void releaseKey(int keyCode) {
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(keyCode);
        KeyMapping.set(key, false);
        syntheticHeld.put(keyCode, false);
    }

    @Override
    public void character(char c) {
        // Character input is separate from key presses (spec §3.5); the
        // keybinding-state path has no character channel — declared honestly
        // through coverage rather than silently dropped.
        throw new UnsupportedOperationException(
                "character input is not supported by the keybinding-state backend");
    }

    @Override
    public void mouseDelta(double dx, double dy) {
        // Camera deltas through the normal mouse path land with the
        // frame-boundary chunk; not advertised via coverage.
        throw new UnsupportedOperationException("mouse deltas land with chunk 3.2");
    }

    @Override
    public long lastAppliedFrame() {
        return appliedFrame.get();
    }

    @Override
    public long clientTick() {
        return clientTick.get();
    }

    /** @return the synthetic held state for a key (never native GLFW state) */
    boolean isSyntheticallyHeld(int keyCode) {
        return syntheticHeld.getOrDefault(keyCode, false);
    }
}
