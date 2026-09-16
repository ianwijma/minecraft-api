package dev.example.mapi.internal.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loader-neutral seam for client-side bridge capabilities (spec §3, §9, §10).
 * Implemented once per loader module in client-only code; the common module
 * never compiles against Minecraft (ADR-0001/0002). Dedicated servers never
 * see a client bridge: every accessor is empty and the client endpoints
 * report {@code CAPABILITY_UNAVAILABLE}.
 *
 * <p>All backend methods must run on the client thread; callers dispatch
 * through {@link #onClientThread(java.util.function.Supplier)}.
 */
public interface ClientBridge {

    /** Bridge with no client capabilities (default for dedicated servers/tests). */
    ClientBridge NONE = new ClientBridge() {
        @Override
        public String bridgeId() {
            return "none";
        }

        @Override
        public Set<String> supportedCapabilities() {
            return Set.of();
        }

        @Override
        public Optional<InputBackend> input() {
            return Optional.empty();
        }

        @Override
        public Optional<ScreenshotBackend> screenshots() {
            return Optional.empty();
        }

        @Override
        public Optional<WindowBackend> window() {
            return Optional.empty();
        }

        @Override
        public Optional<LanBackend> lan() {
            return Optional.empty();
        }

        @Override
        public Optional<UiBackend> ui() {
            return Optional.empty();
        }

        @Override
        public Optional<WorldsBackend> worlds() {
            return Optional.empty();
        }

        @Override
        public Optional<ConnectBackend> connect() {
            return Optional.empty();
        }

        @Override
        public Optional<InventoryBackend> inventory() {
            return Optional.empty();
        }

        @Override
        public <T> T onClientThread(java.util.function.Supplier<T> task) {
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.CAPABILITY_UNAVAILABLE,
                    "no client bridge on this process");
        }
    };

    /** @return stable bridge identifier, never blank */
    String bridgeId();

    /**
     * @return capability identifiers supported by this bridge (for example
     *     {@code client.input}, {@code client.screenshots}); unimplemented
     *     capabilities are never advertised (spec §15.3)
     */
    Set<String> supportedCapabilities();

    /** @return the input backend, empty when input is unsupported */
    Optional<InputBackend> input();

    /** @return the screenshot backend, empty when unsupported */
    Optional<ScreenshotBackend> screenshots();

    /** @return the window backend, empty when unsupported */
    Optional<WindowBackend> window();

    /** @return the LAN backend, empty on dedicated servers or when unsupported */
    Optional<LanBackend> lan();

    /** @return the UI backend, empty when unsupported */
    Optional<UiBackend> ui();

    /** @return the worlds backend, empty when unsupported */
    Optional<WorldsBackend> worlds();

    /** @return the inventory backend, empty when unsupported */
    Optional<InventoryBackend> inventory();

    /** @return the direct-connection backend, empty when unsupported */
    Optional<ConnectBackend> connect();

    /**
     * Inventory backend (spec §10.3): client-logic mode — clicks dispatch
     * through the game's own container-input flow (server roundtrip, not
     * client-side prediction), giving server-confirmed postconditions.
     */
    interface InventoryBackend {

        /** One inventory slot observation. */
        record SlotNode(int slot, String itemId, int count) {

            /** @return the slot as an ordered map for JSON serialization */
            public Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("slot", slot);
                map.put("itemId", itemId);
                map.put("count", count);
                return map;
            }
        }

        /** @return the container id (0 for the player inventory) */
        int containerId();

        /**
         * @return non-empty slots of the player's inventory menu (§10.3
         *     cursor observation is the carried-stack state)
         */
        java.util.List<SlotNode> inspect();

        /**
         * Dispatches a container click through the game's server-flow
         * (client-logic mode, §3.1): the server receives the input and
         * confirms the postcondition.
         *
         * @param slot          menu slot index
         * @param button        mouse button (0 left, 1 right)
         * @param containerInput PICKUP | QUICK_MOVE | SWAP | CLONE | THROW |
         *                      QUICK_CRAFT | PICKUP_ALL
         * @return true when the game accepted the input
         * @throws UnsupportedOperationException when the input name is
         *     unknown (declared, never substituted)
         */
        boolean click(int slot, int button, String containerInput);

        /** @return the carried stack size on the cursor (0 when empty) */
        int carriedCount();

        /**
         * Computes tooltip lines for a slot's item without reproducing hover
         * (spec §10.2: computed tooltip data — the rendered-capture path is
         * separate). Lines include the item name, durability, enchantments,
         * and lore as the game would render them.
         *
         * @param slot menu slot index
         * @return the tooltip lines, empty when the slot is empty
         */
        List<String> tooltip(int slot);

        /**
         * Rendered tooltip capture (spec §10.2): opens the inventory,
         * hovers over the slot, waits a render pass, captures a screenshot
         * showing the tooltip as the game actually displayed it, and reads
         * the tooltip lines. Returns both the visual evidence and the
         * semantic data. Returns {@code TOOLTIP_SEMANTICS_UNAVAILABLE}
         * semantics when the slot is empty.
         *
         * @param slot menu slot index (must have an item)
         * @return the rendered capture: screenshot base64 + lines + metadata
         */
        RenderedTooltip renderedCapture(int slot);

        /** Rendered tooltip capture result (§10.2). */
        record RenderedTooltip(String pngBase64, int width, int height,
                int slot, List<String> lines, long frame, int guiScale) {

            /** @return the capture as an ordered map for JSON serialization */
            public Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("slot", slot);
                map.put("lines", lines);
                map.put("frame", frame);
                map.put("guiScale", guiScale);
                map.put("width", width);
                map.put("height", height);
                map.put("pngBase64", pngBase64);
                return map;
            }
        }
    }

    /**
     * Runs a task on the client thread with a bounded wait.
     *
     * @param task the task, never {@code null}
     * @param <T>  result type
     * @return the task result
     * @throws Exception on timeout or failure; implementations translate
     *     these into problem exceptions where possible
     */
    <T> T onClientThread(java.util.function.Supplier<T> task) throws Exception;

    /**
     * Synthetic input backend (spec §3.5). Character input is separate from
     * key presses; raw deltas apply at a defined input/frame boundary.
     */
    interface InputBackend {

        /** Polling-path coverage disclosure (spec §3.5). */
        record Coverage(boolean callbackDispatch, boolean keybindingState,
                boolean helperPolling, boolean screenDispatch,
                java.util.List<String> unsupportedNativePolling) {
        }

        /** @return backend identifier, never blank */
        String backendId();

        /** @return backend version string, never blank */
        String backendVersion();

        /** @return the coverage disclosure, never {@code null} */
        Coverage coverage();

        /** Dispatches a synthetic key press (raw-input mode). */
        void pressKey(int keyCode);

        /** Dispatches a synthetic key release (raw-input mode). */
        void releaseKey(int keyCode);

        /**
         * Dispatches a character input to the ACTIVE SCREEN (chat box, text
         * fields) via its charTyped path. Only works while a screen is
         * open — throws when in-world (declared honestly, spec §3.5: the
         * keybinding-state backend has no direct-to-world character
         * channel).
         *
         * @param c the character to dispatch
         */
        void character(char c);

        /**
         * Dispatches a raw camera delta through the player input path
         * (raw-input mode, spec §4.2). Deltas are final camera units — the
         * game's own mouse handler applies its sensitivity curve before
         * this same entry point, so the curve is NOT re-applied here. The
         * resulting orientation is readable via {@link #cameraOrientation()}.
         *
         * @param yawDelta   yaw delta in camera units (degrees)
         * @param pitchDelta pitch delta in camera units (degrees)
         * @throws IllegalStateException when no player is present (main menu)
         */
        void mouseDelta(double yawDelta, double pitchDelta);

        /** @return the last frame boundary at which a delta was applied */
        long lastAppliedFrame();

        /** @return the current client-tick boundary counter (spec §4.1) */
        long clientTick();

        /**
         * @return {@code [yaw, pitch]} of the local player when in a world,
         *     empty otherwise (receipt orientation readback, spec §4.2)
         */
        Optional<double[]> cameraOrientation();

        /**
         * Resolves the key code currently bound to a movement mapping (for
         * example {@code "key.up"}). Raw-input movement holds this key
         * through the normal keybinding path.
         *
         * @param mappingId config mapping id, never blank
         * @return the bound key code
         * @throws UnsupportedOperationException when the mapping id is
         *     unknown or the bound key type is unsupported (declared via
         *     coverage, never silently substituted, spec §3.3)
         */
        int keyCodeForMapping(String mappingId);

        /**
         * @return {@code [x, y, z]} of the local player when in a world,
         *     empty otherwise (menu screens)
         */
        Optional<double[]> playerPosition();
    }

    /**
     * Screenshot backend with frame/scale metadata (spec §20 client).
     *
     * <p>26.2 screenshot readback is asynchronous at the GPU level
     * ({@code CommandEncoder.copyTextureToBuffer} + completion callback), so
     * capture is two-phase: {@link #beginCapture()} schedules the readback
     * on the client thread and returns immediately; the PNG fills the temp
     * file on a later frame. Callers poll the file from a NON-client thread
     * (blocking the client thread on its own callback deadlocks) with a
     * bounded wait.
     */
    interface ScreenshotBackend {

        /**
         * Schedules a framebuffer readback. Must run on the client thread.
         *
         * @return the pending capture (temp file fills asynchronously)
         * @throws IllegalStateException when the framebuffer is incomplete
         */
        PendingCapture beginCapture();

        /**
         * @param tempPath  temp file the PNG is written to (caller deletes)
         * @param width     framebuffer width
         * @param height    framebuffer height
         * @param frame     render frame counter
         * @param guiScale  effective GUI scale
         * @param screenId  active screen identifier or {@code null}
         */
        record PendingCapture(java.nio.file.Path tempPath, int width, int height, long frame,
                int guiScale, String screenId) {
        }

        /**
         * @param png       PNG bytes
         * @param width     framebuffer width
         * @param height    framebuffer height
         * @param frame     render frame counter
         * @param guiScale  effective GUI scale
         * @param screenId  active screen identifier or {@code null}
         */
        record Screenshot(byte[] png, int width, int height, long frame,
                int guiScale, String screenId, long capturedAtEpochMs) {
        }
    }

    /**
     * UI inspection and screen dispatch (spec §10.1-lite: recognized widget
     * structures + rendered text; full §10 semantic sources land later).
     */
    interface UiBackend {

        /** One inspectable widget on the active screen. */
        record WidgetNode(String kind, String text, int x, int y, int width,
                int height, boolean active, boolean visible) {

            /** @return the node as an ordered map for JSON serialization */
            public Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("kind", kind);
                map.put("text", text);
                map.put("x", x);
                map.put("y", y);
                map.put("width", width);
                map.put("height", height);
                map.put("active", active);
                map.put("visible", visible);
                return map;
            }
        }

        /** @return the active screen identifier (simple class name) */
        String screenId();

        /** @return direct child widgets of the active screen, empty at menu-less states */
        java.util.List<WidgetNode> widgets();

        /**
         * Dispatches a left click at GUI coordinates through the screen's
         * own event routing (screen dispatch path; the active widget under
         * (x, y) handles it).
         *
         * @param x GUI x coordinate
         * @param y GUI y coordinate
         * @return true when a child consumed the click
         */
        boolean click(int x, int y);
    }

    /** World list/load/delete over the level storage (spec §17.2-adjacent). */
    interface WorldsBackend {

        /** One saved world. */
        record WorldEntry(String levelId, String levelName, boolean requiresConversion,
                boolean requiresFileFixing, boolean experimental) {

            /** @return the entry as an ordered map for JSON serialization */
            public Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("levelId", levelId);
                map.put("levelName", levelName);
                map.put("requiresConversion", requiresConversion);
                map.put("requiresFileFixing", requiresFileFixing);
                map.put("experimental", experimental);
                return map;
            }
        }

        /**
         * @return saved worlds (bounded by the caller)
         * @throws Exception when storage reads fail
         */
        java.util.List<WorldEntry> listWorlds() throws Exception;

        /**
         * Loads a saved world (async in vanilla: returns when the load has
         * been started; poll {@code /server/world} for phase ACTIVE).
         *
         * @param levelId the world's directory id
         * @throws Exception when the world cannot be opened
         */
        void loadWorld(String levelId) throws Exception;

        /**
         * Creates a fresh survival world with a random seed and vanilla
         * defaults (async; poll for phase ACTIVE). The world is created
         * even if a world with the same id exists? No: creation fails when
         * the id is taken (reported, never overwritten).
         *
         * @param levelId  directory id, never blank
         * @param gamemode {@code survival} or {@code creative} (default
         *                 survival)
         * @param seed     world seed; {@code null} = random
         * @throws Exception when creation fails or the id already exists
         */
        void createWorld(String levelId, String gamemode, Long seed) throws Exception;

        /**
         * Deletes a saved world.
         *
         * @param levelId the world's directory id
         * @throws Exception when deletion fails
         */
        void deleteWorld(String levelId) throws Exception;
    }

    /** Direct connection (spec §9.2). */
    interface ConnectBackend {

        /**
         * Joins a server through the vanilla connect flow. The HTTP layer
         * enforces the allowlist before calling this.
         *
         * @param address host[:port] address
         */
        void join(String address);
    }

    /** LAN publication backend (spec §9.3; integrated server only). */
    interface LanBackend {

        /** @return true while the integrated server is published to LAN */
        boolean isPublished();

        /**
         * Publishes to LAN.
         *
         * @param port   LAN port, 1..65535 (0 = game-assigned)
         * @param gamemode game type name ({@code survival}, {@code creative},
         *                 {@code adventure}, {@code spectator}) or empty for
         *                 the world default
         * @param cheats allow cheats on the published game
         * @return true when publishing succeeded
         * @throws UnsupportedOperationException when the version cannot
         *     publish (reported, never silent)
         */
        boolean publish(int port, String gamemode, boolean cheats);

        /** @return true when unpublishing succeeded */
        boolean unpublish();
    }

    /** Window state with framebuffer/logical distinction (spec §9.1). */
    interface WindowBackend {

        /** @return the current state, never {@code null} */
        WindowState state();

        /**
         * Sets windowed dimensions. The OS may adjust the request; the
         * returned state reports effective values (spec §9.1: never assume
         * one logical pixel equals one framebuffer pixel).
         *
         * @param width  logical width, 320..3840
         * @param height logical height, 240..2160
         * @return the resulting state
         */
        WindowState setWindowed(int width, int height);

        /**
         * @param fullscreen true for fullscreen, false for windowed
         * @return the resulting state
         */
        WindowState setFullscreen(boolean fullscreen);

        /**
         * @param guiScale GUI scale, 0 (auto) or 1..4
         * @return the resulting state
         */
        WindowState setGuiScale(int guiScale);

        /**
         * @param width            logical width
         * @param height           logical height
         * @param framebufferWidth framebuffer width (never assumed equal to
         *                         logical)
         * @param framebufferHeight framebuffer height
         * @param guiScale         effective GUI scale
         * @param fullscreen       fullscreen state
         * @param revision         window/screen revision counter
         */
        record WindowState(int width, int height, int framebufferWidth,
                int framebufferHeight, int guiScale, boolean fullscreen, long revision) {
        }
    }
}
