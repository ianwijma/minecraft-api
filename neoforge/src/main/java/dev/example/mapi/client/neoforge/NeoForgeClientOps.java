package dev.example.mapi.client.neoforge;

import dev.example.mapi.internal.MapiBootstrap;
import dev.example.mapi.internal.client.ClientStatusSnapshot;
import dev.example.mapi.internal.client.MapiClientOps;
import dev.example.mapi.internal.client.ScreenNode;
import java.util.List;
import net.minecraft.client.Minecraft;
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
}
