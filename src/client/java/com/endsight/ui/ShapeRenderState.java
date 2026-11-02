package com.endsight.ui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/** The existing banded shapes, submitted once instead of once for every one-pixel strip. */
record ShapeRenderState(Kind kind, Matrix3x2fc pose, int x, int y, int w, int h, int radius,
                        int corners, int colour, ScreenRectangle scissorArea, ScreenRectangle bounds)
        implements GuiElementRenderState {
    enum Kind { ROUNDED, RING, FRAME, CORNERS, BADGE }

    static void submit(GuiGraphicsExtractor g, Kind kind, int x, int y, int w, int h,
                       int radius, int corners, int colour) {
        if (w <= 0 || h <= 0 || colour >>> 24 == 0) return;
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        ScreenRectangle scissor = g.scissorStack.peek();
        ScreenRectangle bounds = new ScreenRectangle(x, y, w, h).transformMaxBounds(pose);
        if (scissor != null) bounds = bounds.intersection(scissor);
        // Conservative whole-shape bounds preserve painter order, including the
        // empty middle of rings. The vertices/alpha and vanilla GUI pipeline are unchanged.
        g.guiRenderState.addGuiElement(new ShapeRenderState(kind, pose, x, y, w, h,
                radius, corners, colour, scissor, bounds));
    }

    @Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
    @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }

    @Override public void buildVertices(VertexConsumer out) {
        switch (kind) {
            case ROUNDED -> {
                rect(out, x, y + radius, w, h - 2 * radius);
                for (int i = 0; i < radius; i++) {
                    int inset = Draw.capInset(radius, i);
                    int left = (corners & 1) != 0 ? inset : 0, right = (corners & 2) != 0 ? inset : 0;
                    rect(out, x + left, y + i, w - left - right, 1);
                    left = (corners & 4) != 0 ? inset : 0;
                    right = (corners & 8) != 0 ? inset : 0;
                    rect(out, x + left, y + h - i - 1, w - left - right, 1);
                }
            }
            case RING -> {
                rect(out, x + radius, y, w - 2 * radius, 1);
                rect(out, x + radius, y + h - 1, w - 2 * radius, 1);
                rect(out, x, y + radius, 1, h - 2 * radius);
                rect(out, x + w - 1, y + radius, 1, h - 2 * radius);
                for (int i = 0; i < radius; i++) {
                    int inset = Draw.capInset(radius, i);
                    rect(out, x + inset, y + i, 1, 1);
                    rect(out, x + w - inset - 1, y + i, 1, 1);
                    rect(out, x + inset, y + h - i - 1, 1, 1);
                    rect(out, x + w - inset - 1, y + h - i - 1, 1, 1);
                }
            }
            case FRAME -> {
                rect(out, x + 1, y, w - 2, 1);
                rect(out, x + 1, y + h - 1, w - 2, 1);
                rect(out, x, y + 1, 1, h - 2);
                rect(out, x + w - 1, y + 1, 1, h - 2);
            }
            case CORNERS -> {
                rect(out, x + 1, y, radius, 1);
                rect(out, x, y + 1, 1, radius);
                rect(out, x + w - 1 - radius, y, radius, 1);
                rect(out, x + w - 1, y + 1, 1, radius);
                rect(out, x + 1, y + h - 1, radius, 1);
                rect(out, x, y + h - 1 - radius, 1, radius);
                rect(out, x + w - 1 - radius, y + h - 1, radius, 1);
                rect(out, x + w - 1, y + h - 1 - radius, 1, radius);
            }
            case BADGE -> {
                rect(out, x + 4, y, w - 8, 2);
                rect(out, x + 2, y + 2, w - 4, 2);
                rect(out, x, y + 4, w, h - 8);
                rect(out, x + 2, y + h - 4, w - 4, 2);
                rect(out, x + 4, y + h - 2, w - 8, 2);
            }
        }
    }

    private void rect(VertexConsumer out, int left, int top, int width, int height) {
        if (width <= 0 || height <= 0) return;
        // Match ColoredRectangleRenderState's winding and ARGB conversion exactly.
        out.addVertexWith2DPose(pose, left, top).setColor(colour);
        out.addVertexWith2DPose(pose, left, top + height).setColor(colour);
        out.addVertexWith2DPose(pose, left + width, top + height).setColor(colour);
        out.addVertexWith2DPose(pose, left + width, top).setColor(colour);
    }
}
