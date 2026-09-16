package dev.example.mapi.fabric.client;

import dev.example.mapi.internal.client.ClientBridge;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Fabric UI backend (spec §10.1-lite). */
final class FabricUi implements ClientBridge.UiBackend {

    private Screen screen() {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "no screen is active (in-world state)");
        }
        return screen;
    }

    @Override
    public String screenId() {
        return screen().getClass().getSimpleName();
    }

    @Override
    public List<ClientBridge.UiBackend.WidgetNode> widgets() {
        List<ClientBridge.UiBackend.WidgetNode> nodes = new ArrayList<>();
        for (GuiEventListener child : screen().children()) {
            if (child instanceof AbstractWidget widget) {
                nodes.add(new ClientBridge.UiBackend.WidgetNode(
                        widget.getClass().getSimpleName(),
                        widget.getMessage().getString(),
                        widget.getX(), widget.getY(),
                        widget.getWidth(), widget.getHeight(),
                        widget.active, widget.visible));
            }
        }
        return List.copyOf(nodes);
    }

    @Override
    public boolean click(int x, int y) {
        Screen screen = screen();
        MouseButtonEvent press = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
        boolean consumed = screen.mouseClicked(press, false);
        if (consumed) {
            screen.mouseReleased(press);
        }
        return consumed;
    }
}
