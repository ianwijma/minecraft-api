package dev.example.mapi.runner;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Visual baseline comparison (spec §16: required runner; §18: visual tests
 * use environment-specific baselines and explicit tolerances). The first
 * capture for a scenario becomes the baseline; later captures are compared
 * per-pixel with a normalized RMS delta and an explicit tolerance. A size
 * mismatch is a divergence, never a silent pass.
 */
final class VisualDiff {

    /** Comparison outcome. */
    record Outcome(boolean ok, String detail, double rmsDelta) {

        /** @return the outcome as an ordered map */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", ok);
            map.put("detail", detail);
            map.put("rmsDelta", rmsDelta);
            return map;
        }
    }

    private final MapiClient client;

    VisualDiff(MapiClient client) {
        this.client = client;
    }

    /**
     * Captures a screenshot through the API and compares or baselines it.
     *
     * @param baselinesDir directory holding {@code <scenario>.baseline.png}
     * @param scenario     scenario name, never blank
     * @param tolerance    maximum normalized RMS delta (0.0..1.0) to pass
     * @return the outcome
     * @throws IOException on capture or filesystem failure
     */
    Outcome compareOrBaseline(Path baselinesDir, String scenario, double tolerance)
            throws IOException {
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("scenario is required");
        }
        if (tolerance < 0 || tolerance > 1) {
            throw new IllegalArgumentException("tolerance must be 0..1");
        }
        var capture = client.get("/api/v1/client/screenshots");
        if (!capture.ok()) {
            throw new IOException("screenshot capture failed: HTTP " + capture.status());
        }
        Map<?, ?> body = (Map<?, ?>) capture.body();
        byte[] png = Base64.getDecoder().decode(
                String.valueOf(body.get("pngBase64")));
        Files.createDirectories(baselinesDir);
        Path baseline = baselinesDir.resolve(scenario + ".baseline.png");
        if (!Files.isRegularFile(baseline)) {
            Files.write(baseline, png);
            return new Outcome(true, "baseline created (no comparison this run)", 0);
        }
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(
                Files.readAllBytes(baseline)));
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(png));
        if (expected == null || actual == null) {
            return new Outcome(false, "undecodable image (baseline or capture)", 1);
        }
        if (expected.getWidth() != actual.getWidth()
                || expected.getHeight() != actual.getHeight()) {
            return new Outcome(false, "size mismatch: baseline "
                    + expected.getWidth() + "x" + expected.getHeight() + " vs capture "
                    + actual.getWidth() + "x" + actual.getHeight(), 1);
        }
        double rms = rmsDelta(expected, actual);
        if (rms > tolerance) {
            return new Outcome(false,
                    "visual divergence: rms " + String.format("%.4f", rms)
                            + " > tolerance " + tolerance, rms);
        }
        return new Outcome(true, "within tolerance (rms "
                + String.format("%.4f", rms) + ")", rms);
    }

    /** Normalized per-pixel RMS delta across RGB channels, 0..1. */
    static double rmsDelta(BufferedImage expected, BufferedImage actual) {
        long sum = 0;
        long count = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int e = expected.getRGB(x, y);
                int a = actual.getRGB(x, y);
                for (int shift : new int[] {16, 8, 0}) {
                    int diff = ((e >> shift) & 0xFF) - ((a >> shift) & 0xFF);
                    sum += (long) diff * diff;
                    count++;
                }
            }
        }
        return count == 0 ? 0 : Math.sqrt(sum / (double) count) / 255.0;
    }

    /** Renders a synthetic PNG for tests (no display required). */
    static byte[] renderPng(int width, int height, Color fill) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(fill);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Writes a fake screenshot API response body (tests only). */
    static String fakeScreenshotBody(byte[] png) {
        return "{\"protocolVersion\":1,\"width\":64,\"height\":32,\"frame\":1,"
                + "\"guiScale\":2,\"capturedAtEpochMs\":0,\"pngBase64\":\""
                + Base64.getEncoder().encodeToString(png) + "\"}";
    }

    /** Encodes text for test manifests. */
    static String asText(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
