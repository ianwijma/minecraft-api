package dev.example.mapi.client.neoforge;

import dev.example.mapi.internal.MapiBootstrap;
import dev.example.mapi.internal.client.ClientStatusSnapshot;
import dev.example.mapi.internal.client.KeyActionResult;
import dev.example.mapi.internal.client.MapiClientOps;
import dev.example.mapi.internal.client.ScreenNode;
import dev.example.mapi.internal.client.ScreenshotResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * NeoForge implementation of the client operations seam. Loaded only on
 * physical clients ({@code @OnlyIn(Dist.CLIENT)} plus the dist-guarded call
 * from the mod constructor); all reads run on the client thread via
 * {@code Minecraft#execute}. APIs verified against the 26.2 jar.
 */
@OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
public final class NeoForgeClientOps implements MapiClientOps {

    private NeoForgeClientOps() {
    }

    public static void register() {
        MapiBootstrap.registerClientOps(new NeoForgeClientOps(), NeoForgeClientOps::schedule);
    }

    private static void schedule(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    @Override
    public ClientStatusSnapshot status() {
        Minecraft client = Minecraft.getInstance();
        Screen screen = client.gui.screen();
        LocalPlayer player = client.player;
        Long gameTime = client.level == null ? null : client.level.getGameTime();
        String dimension = player == null ? null : player.level().dimension().identifier().toString();
        return new ClientStatusSnapshot(
                client.getWindow().getWidth(),
                client.getWindow().getHeight(),
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight(),
                client.getWindow().getGuiScale(),
                screen == null ? null : screen.getClass().getSimpleName(),
                player != null,
                dimension,
                gameTime);
    }

    @Override
    public ScreenNode screenTree() {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null) {
            return new ScreenNode(null, null, null, null, null, null, List.of());
        }
        return walk(screen);
    }

    private static ScreenNode walk(GuiEventListener listener) {
        String label = null;
        Integer x = null;
        Integer y = null;
        Integer width = null;
        Integer height = null;
        if (listener instanceof AbstractWidget widget) {
            label = widget.getMessage() == null || widget.getMessage().getString().isBlank()
                    ? null
                    : widget.getMessage().getString();
            x = widget.getX();
            y = widget.getY();
            width = widget.getWidth();
            height = widget.getHeight();
        }
        List<ScreenNode> children = listener instanceof AbstractContainerEventHandler container
                ? walkAll(container.children())
                : List.of();
        return new ScreenNode(listener.getClass().getSimpleName(), label, x, y, width, height, children);
    }

    private static List<ScreenNode> walkAll(List<? extends GuiEventListener> listeners) {
        return listeners.stream().map(NeoForgeClientOps::walk).toList();
    }

    /**
     * The key mappings the API may drive (spec §5.2 input mode). Names are
     * matched against whatever the game reports at runtime — never guessed.
     */
    private static final List<Function<Options, KeyMapping>> SUPPORTED_MAPPINGS = List.of(
            options -> options.keyUp, options -> options.keyLeft, options -> options.keyDown,
            options -> options.keyRight, options -> options.keyJump, options -> options.keyShift,
            options -> options.keySprint, options -> options.keyInventory, options -> options.keyDrop,
            options -> options.keyChat, options -> options.keyAttack, options -> options.keyUse,
            options -> options.keyPickItem, options -> options.keySwapOffhand,
            options -> options.keyPlayerList, options -> options.keyTogglePerspective);

    private static final java.util.Set<KeyMapping> API_HELD_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public KeyActionResult pressKey(String mapping, String action) {
        Minecraft client = Minecraft.getInstance();
        KeyMapping keyMapping = null;
        for (Function<Options, KeyMapping> candidate : SUPPORTED_MAPPINGS) {
            KeyMapping option = candidate.apply(client.options);
            if (option.getName().equals(mapping)) {
                keyMapping = option;
                break;
            }
        }
        if (keyMapping == null) {
            throw new MapiClientOps.UnknownMappingException(
                    "mapping not reported by this client: " + mapping);
        }
        return switch (action == null ? "" : action) {
            case "press" -> {
                KeyMapping.set(keyMapping.getDefaultKey(), true);
                API_HELD_KEYS.add(keyMapping);
                yield new KeyActionResult(mapping, action, keyMapping.isDown());
            }
            case "release" -> {
                KeyMapping.set(keyMapping.getDefaultKey(), false);
                API_HELD_KEYS.remove(keyMapping);
                yield new KeyActionResult(mapping, action, keyMapping.isDown());
            }
            case "tap" -> {
                KeyMapping.click(keyMapping.getDefaultKey());
                yield new KeyActionResult(mapping, action, null);
            }
            default -> throw new IllegalArgumentException(
                    "action must be press, release, or tap: " + action);
        };
    }

    @Override
    public void releaseAllKeys() {
        for (KeyMapping keyMapping : List.copyOf(API_HELD_KEYS)) {
            KeyMapping.set(keyMapping.getDefaultKey(), false);
        }
        API_HELD_KEYS.clear();
    }

    @Override
    public ScreenshotResult captureScreenshot(long frameId) {
        Minecraft client = Minecraft.getInstance();
        Path dir = client.gameDirectory.toPath().resolve("mcapi").resolve("screenshots");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create screenshot directory " + dir, e);
        }
        Path target = dir.resolve("frame-" + frameId + ".png");
        AtomicReference<ScreenshotResult> result = new AtomicReference<>();
        AtomicReference<IOException> failure = new AtomicReference<>();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try {
                image.writeToFile(target);
                result.set(new ScreenshotResult(frameId,
                        client.gameDirectory.toPath().relativize(target).toString(),
                        image.getWidth(), image.getHeight(), (int) Files.size(target)));
            } catch (IOException e) {
                failure.set(e);
            } finally {
                image.close();
            }
        });
        if (failure.get() != null) {
            throw new IllegalStateException("screenshot write failed", failure.get());
        }
        ScreenshotResult snapshot = result.get();
        if (snapshot == null) {
            throw new IllegalStateException("framebuffer capture did not complete synchronously");
        }
        return snapshot;
    }
}
