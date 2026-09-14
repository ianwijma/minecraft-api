package dev.example.mapi.internal.client;

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
         * Dispatches a character input (separate from key presses, spec
         * §3.5).
         */
        void character(char c);

        /**
         * Applies a raw mouse delta through the normal camera input path
         * (raw-input mode; sensitivity remains relevant).
         */
        void mouseDelta(double dx, double dy);

        /** @return the last frame boundary at which a delta was applied */
        long lastAppliedFrame();

        /** @return the current client-tick boundary counter (spec §4.1) */
        long clientTick();
    }

    /** Screenshot backend with frame/scale metadata (spec §20 client). */
    interface ScreenshotBackend {

        /** @return the capture, never {@code null} */
        Screenshot capture();

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

    /** Window state with framebuffer/logical distinction (spec §9.1). */
    interface WindowBackend {

        /** @return the current state, never {@code null} */
        WindowState state();

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
