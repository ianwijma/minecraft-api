package dev.example.mapi.neoforge.client;

import dev.example.mapi.internal.client.ClientBridge;
import dev.example.mapi.internal.command.CommandBackend;
import dev.example.mapi.internal.query.WorldQueryBackend;
import dev.example.mapi.internal.tick.TickControlBackend;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * NeoForge client bridge. Capabilities are added one at a time; a capability
 * is advertised only once implemented (spec §15.3). Client-only code is
 * gated by the FMLClientSetupEvent dist guard.
 */
public final class NeoForgeClientBridge implements ClientBridge {

    private volatile Minecraft current;

    void onServerStarting(Minecraft server) {
        this.current = server;
    }

    void onServerStopped() {
        this.current = null;
    }

    @Override
    public String bridgeId() {
        return "mapi-neoforge-client";
    }

    @Override
    public Set<String> supportedCapabilities() {
        return Set.of("client.window", "client.screenshots", "client.input",
                "client.lan", "client.ui", "client.worlds", "client.connect",
                "client.inventory");
    }

    @Override
    public Optional<ClientBridge.InputBackend> input() {
        return Optional.of(new NeoForgeInputBackend());
    }

    @Override
    public Optional<ClientBridge.ScreenshotBackend> screenshots() {
        return Optional.of(new NeoForgeScreenshots());
    }

    @Override
    public Optional<ClientBridge.WindowBackend> window() {
        return Optional.of(new NeoForgeWindow());
    }

    @Override
    public Optional<ClientBridge.LanBackend> lan() {
        return Optional.of(new NeoForgeLan());
    }

    @Override
    public Optional<ClientBridge.UiBackend> ui() {
        return Optional.of(new NeoForgeUi());
    }

    @Override
    public Optional<ClientBridge.WorldsBackend> worlds() {
        return Optional.of(new NeoForgeWorlds());
    }

    @Override
    public Optional<ClientBridge.ConnectBackend> connect() {
        return Optional.of(new NeoForgeConnect());
    }

    @Override
    public Optional<ClientBridge.InventoryBackend> inventory() {
        return Optional.of(new NeoForgeInventory());
    }

    @Override
    public <T> T onClientThread(java.util.function.Supplier<T> task) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            return task.get();
        }
        var future = new java.util.concurrent.CompletableFuture<T>();
        client.execute(() -> {
            try {
                future.complete(task.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(false);
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.SERVER_BUSY,
                    "client thread busy; work did not complete within 5000 ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.SERVER_BUSY, "interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof dev.example.mapi.internal.problem.ProblemException pe) {
                throw pe;
            }
            throw new dev.example.mapi.internal.problem.ProblemException(
                    dev.example.mapi.internal.problem.ProblemCode.INTERNAL,
                    "client-thread work failed: " + cause);
        }
    }

    /** Window backend (verified 26.2 Window APIs). */
    private final class NeoForgeWindow implements WindowBackend {

        private ClientBridge.WindowBackend.WindowState currentState() {
            com.mojang.blaze3d.platform.Window window =
                    Minecraft.getInstance().getWindow();
            return new ClientBridge.WindowBackend.WindowState(
                    window.getWidth(), window.getHeight(),
                    window.getScreenWidth(), window.getScreenHeight(),
                    window.getGuiScale(), window.isFullscreen(), 0);
        }

        @Override
        public ClientBridge.WindowBackend.WindowState state() {
            return currentState();
        }

        @Override
        public ClientBridge.WindowBackend.WindowState setWindowed(int width, int height) {
            Minecraft.getInstance().getWindow().setWindowed(width, height);
            return currentState();
        }

        @Override
        public ClientBridge.WindowBackend.WindowState setFullscreen(boolean fullscreen) {
            com.mojang.blaze3d.platform.Window window =
                    Minecraft.getInstance().getWindow();
            if (fullscreen != window.isFullscreen()) {
                window.updateFullscreenIfChanged();
            }
            return currentState();
        }

        @Override
        public ClientBridge.WindowBackend.WindowState setGuiScale(int guiScale) {
            Minecraft.getInstance().getWindow().setGuiScale(guiScale);
            return currentState();
        }
    }

    /** LAN publication backend (verified 26.2 IntegratedServer APIs). */
    private final class NeoForgeLan implements LanBackend {

        private net.minecraft.client.server.IntegratedServer server() {
            var server = Minecraft.getInstance().getSingleplayerServer();
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
            var gameType = gamemodeOf(gamemode == null ? "survival" : gamemode);
            if (port > 0) {
                return server().publishServer(scope, gameType, cheats, port);
            }
            return server().publishServer(scope, 0);
        }

        @Override
        public boolean unpublish() {
            return server().unpublishServer();
        }

        private net.minecraft.world.level.GameType gamemodeOf(String name) {
            return switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "creative" -> net.minecraft.world.level.GameType.CREATIVE;
                case "adventure" -> net.minecraft.world.level.GameType.ADVENTURE;
                case "spectator" -> net.minecraft.world.level.GameType.SPECTATOR;
                default -> net.minecraft.world.level.GameType.SURVIVAL;
            };
        }
    }
}
