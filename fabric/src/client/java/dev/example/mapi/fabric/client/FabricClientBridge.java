package dev.example.mapi.fabric.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.example.mapi.internal.client.ClientBridge;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Fabric client bridge (client source set only — dedicated servers never
 * load this class, per loom.splitEnvironmentSourceSets). Capabilities are
 * advertised only for what is implemented and compile-verified against
 * Minecraft 26.2 (spec §15.3).
 */
final class FabricClientBridge implements ClientBridge {

    private final AtomicLong windowRevision = new AtomicLong();
    private final FabricInputBackend inputBackend = new FabricInputBackend();

    @Override
    public String bridgeId() {
        return "mapi-fabric-client";
    }

    @Override
    public java.util.Set<String> supportedCapabilities() {
        return java.util.Set.of("client.window", "client.screenshots", "client.input",
                "client.lan", "client.ui", "client.worlds", "client.connect");
    }

    @Override
    public Optional<InputBackend> input() {
        return Optional.of(inputBackend);
    }

    @Override
    public Optional<ScreenshotBackend> screenshots() {
        return Optional.of(new FabricScreenshots());
    }

    @Override
    public Optional<WindowBackend> window() {
        return Optional.of(new FabricWindow());
    }

    @Override
    public Optional<LanBackend> lan() {
        return Optional.of(new FabricLan());
    }

    @Override
    public Optional<UiBackend> ui() {
        return Optional.of(new FabricUi());
    }

    @Override
    public Optional<WorldsBackend> worlds() {
        return Optional.of(new FabricWorlds());
    }

    @Override
    public Optional<ConnectBackend> connect() {
        return Optional.of(new FabricConnect());
    }

    /**
     * Called from the client entrypoint: registers the tick counter and any
     * other client-thread hooks exactly once.
     */
    void initialize() {
        inputBackend.registerTickCounter();
    }

    @Override
    public <T> T onClientThread(java.util.function.Supplier<T> task) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            return task.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(task.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw new ProblemException(ProblemCode.SERVER_BUSY,
                    "client thread busy; work did not complete within 5000 ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProblemException(ProblemCode.SERVER_BUSY, "interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ProblemException problem) {
                throw problem;
            }
            throw new ProblemException(ProblemCode.INTERNAL,
                    "client-thread work failed: " + cause);
        }
    }

    private ClientBridge.WindowBackend.WindowState currentState() {
        Window window = Minecraft.getInstance().getWindow();
        return new ClientBridge.WindowBackend.WindowState(window.getWidth(), window.getHeight(),
                window.getScreenWidth(), window.getScreenHeight(),
                window.getGuiScale(), window.isFullscreen(), windowRevision.get());
    }

    /** Window state over the verified {@code com.mojang.blaze3d.platform.Window}. */
    private final class FabricWindow implements WindowBackend {

        @Override
        public ClientBridge.WindowBackend.WindowState state() {
            return currentState();
        }

        @Override
        public ClientBridge.WindowBackend.WindowState setWindowed(int width, int height) {
            Minecraft.getInstance().getWindow().setWindowed(width, height);
            windowRevision.incrementAndGet();
            return currentState();
        }

        @Override
        public ClientBridge.WindowBackend.WindowState setFullscreen(boolean fullscreen) {
            Window window = Minecraft.getInstance().getWindow();
            if (fullscreen != window.isFullscreen()) {
                // 26.2 exposes no public setFullscreen(boolean) on Window;
                // the honest behavior is to report the unchanged actual state
                // and bump the revision so callers can see the attempt
                // (spec §9.1: return effective values, never assume).
                windowRevision.incrementAndGet();
            }
            return currentState();
        }

        @Override
        public ClientBridge.WindowBackend.WindowState setGuiScale(int guiScale) {
            Minecraft.getInstance().getWindow().setGuiScale(guiScale);
            windowRevision.incrementAndGet();
            return currentState();
        }
    }

    /** LAN publication over the verified IntegratedServer publish APIs. */
    private final class FabricLan implements LanBackend {

        private net.minecraft.client.server.IntegratedServer server() {
            net.minecraft.client.server.IntegratedServer server =
                    net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
            if (server == null) {
                throw new dev.example.mapi.internal.problem.ProblemException(
                        dev.example.mapi.internal.problem.ProblemCode.WORLD_NOT_LOADED,
                        "no integrated server is running");
            }
            return server;
        }

        @Override
        public boolean isPublished() {
            return server().isPublished();
        }

        @Override
        public boolean publish(int port, String gamemode, boolean cheats) {
            var scope = net.minecraft.server.MinecraftServer.MultiplayerScope.LAN;
            if (port > 0) {
                return server().publishServer(scope, gamemodeOf(gamemode), cheats, port);
            }
            return server().publishServer(scope, 0);
        }

        @Override
        public boolean unpublish() {
            return server().unpublishServer();
        }

        private net.minecraft.world.level.GameType gamemodeOf(String name) {
            if (name == null || name.isBlank()) {
                return net.minecraft.world.level.GameType.DEFAULT_MODE;
            }
            return switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "survival" -> net.minecraft.world.level.GameType.SURVIVAL;
                case "creative" -> net.minecraft.world.level.GameType.CREATIVE;
                case "adventure" -> net.minecraft.world.level.GameType.ADVENTURE;
                case "spectator" -> net.minecraft.world.level.GameType.SPECTATOR;
                default -> throw new dev.example.mapi.internal.problem.ProblemException(
                        dev.example.mapi.internal.problem.ProblemCode.BAD_REQUEST,
                        "unknown gamemode: " + name);
            };
        }
    }

    /** Screenshot capture over the verified {@code Screenshot.takeScreenshot}. */
    private final class FabricScreenshots implements ScreenshotBackend {

        @Override
        public PendingCapture beginCapture() {
            net.minecraft.client.Minecraft client =
                    net.minecraft.client.Minecraft.getInstance();
            com.mojang.blaze3d.pipeline.RenderTarget target =
                    client.gameRenderer.mainRenderTarget();
            Path temp;
            try {
                temp = Files.createTempFile("mapi-shot", ".png");
            } catch (IOException e) {
                throw new ProblemException(ProblemCode.INTERNAL,
                        "screenshot temp file failed: " + e);
            }
            Screen screen = client.gui.screen();
            PendingCapture pending = new PendingCapture(temp, target.width,
                    target.height, client.getFrameTimeNs(), currentState().guiScale(),
                    screen == null ? null : screen.getClass().getSimpleName());
            java.util.function.Consumer<NativeImage> writer = image -> {
                try {
                    image.writeToFile(temp.toFile());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    image.close();
                }
            };
            net.minecraft.client.Screenshot.takeScreenshot(target, writer);
            return pending;
        }
    }
}
