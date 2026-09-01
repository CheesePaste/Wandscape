package com.wsteam.wandscape.foundation.ui.markdown.ast;

/**
 * AST Node representing an image ![alt](resourceLocation =WIDTHxHEIGHT).
 *
 * @param altText       Alternative text or caption
 * @param resourceLocation Minecraft resource location string (e.g. "wandscape:textures/gui/guide/demo.png")
 * @param width         Target render width in pixels (0 for default auto)
 * @param height        Target render height in pixels (0 for default auto)
 */
public record ImageNode(
        String altText,
        String resourceLocation,
        int width,
        int height
) implements MarkdownNode {
}
