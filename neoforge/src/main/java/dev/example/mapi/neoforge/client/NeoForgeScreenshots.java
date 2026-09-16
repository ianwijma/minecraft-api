package dev.example.mapi.neoforge.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.example.mapi.internal.client.ClientBridge;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;

/**
 * NeoForge screenshot backend (verified 26.2: {@code Screenshot.takeScreenshot}
 * + {@code GameRenderer.mainRenderTarget()}). Two-phase capture: the GPU
 * readback completes asynchronously (spec §10.2, §20).
 */
class NeoForgeScreenshots implements ClientBridge.ScreenshotBackend {

    private int guiScale() {
        return Minecraft.getInstance().getWindow().getGuiScale();
    }

    @Override
    public PendingCapture beginCapture() {
        Minecraft client = Minecraft.getInstance();
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
                target.height, client.getFrameTimeNs(), guiScale(),
                screen == null ? null : screen.getClass().getSimpleName());
        Consumer<NativeImage> writer = image -> {
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
