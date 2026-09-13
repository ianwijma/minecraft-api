package dev.example.mapi.internal.client;

import java.util.List;

/**
 * Best-effort semantic node of the open screen (spec §5.1: best-effort tree
 * with coverage metadata). Labels are plain-text widget labels when
 * available; widgets that are not positionable carry {@code null} bounds.
 *
 * @param widgetClass simple class name of the widget
 * @param label       plain-text label or {@code null}
 * @param x           GUI-space x or {@code null}
 * @param y           GUI-space y or {@code null}
 * @param width       widget width or {@code null}
 * @param height      widget height or {@code null}
 * @param children    nested nodes (containers), never {@code null}
 */
public record ScreenNode(
        String widgetClass,
        String label,
        Integer x,
        Integer y,
        Integer width,
        Integer height,
        List<ScreenNode> children) {
}
