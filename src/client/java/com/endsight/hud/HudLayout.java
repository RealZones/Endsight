package com.endsight.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each HUD readout sits, and how to draw a sample of it.
 *
 * Replaces the four-corner Anchor choice every readout used to carry. Corners were fine
 * with one element and wrong with three: two things anchored to the same corner had to
 * know about each other to avoid overlapping, which is exactly the coupling a layout
 * system exists to remove.
 *
 * Positions are stored as a FRACTION of the screen, not pixels. A HUD placed at the
 * right-hand edge on a 1080p window should still be at the right-hand edge in a small
 * window or at a different GUI scale, and pixels do not survive either.
 *
 * Every element also registers how to draw a sample of itself. The placement screen uses
 * that rather than inventing its own preview, so what you drag is drawn by the same code
 * that draws it in game - a separate "preview" would drift from the real thing, and you
 * would be positioning something that no longer matches.
 */
public final class HudLayout {

    private HudLayout() {
    }

    /** Draws the element at a position and returns {width, height} actually used. */
    public interface Renderer {
        int[] draw(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample);
    }

    private record Element(String id, String label, float defX, float defY, Renderer renderer) {
    }

    private static final Map<String, Element> ELEMENTS = new LinkedHashMap<>();
    private static final Map<String, float[]> POS = new LinkedHashMap<>();
    /** Size per element, 1 being drawn as designed; the wheel over one in the placement screen. */
    private static final Map<String, Float> SCALE = new LinkedHashMap<>();
    private static final Map<String, Long> LOGGED = new LinkedHashMap<>();
    /** Where each element was last drawn, {x, y, w, h}, for anything that wants to be clicked. */
    private static final Map<String, int[]> BOUNDS = new LinkedHashMap<>();

    public static int[] bounds(String id) {
        return BOUNDS.get(id);
    }
    public static final float MIN_SCALE = 0.5f, MAX_SCALE = 2f;

    public static float scale(String id) {
        return SCALE.getOrDefault(id, 1f);
    }

    public static void setScale(String id, float s) {
        s = Math.round(Math.max(MIN_SCALE, Math.min(MAX_SCALE, s)) * 10) / 10f;
        if (Math.abs(s - 1f) < 0.01f) SCALE.remove(id);
        else SCALE.put(id, s);
    }

    /**
     * Draw an element where it lives, at its size.
     *
     * The one place every readout goes through, so scale and side are decided once:
     * measured first (a null target draws nothing), placed by its scaled size, then
     * drawn under a transform so the element itself never knows it is not 1:1. Before
     * the draw, {@link Readout#mirror} is set for whichever half of the screen the
     * element sits in, so its accent tick lands on the outside edge.
     */
    public static void draw(String id, GuiGraphicsExtractor g, Font font, boolean sample) {
        draw(id, g, font, sample, false);
    }

    /**
     * @param overMenus draw even while an inventory-type window is open. Off for
     *                  everything but the readout that belongs to a window: trackers
     *                  showing through the recipe panel read as clutter, not context.
     */
    public static void draw(String id, GuiGraphicsExtractor g, Font font, boolean sample, boolean overMenus) {
        Element e = ELEMENTS.get(id);
        if (e == null) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (!sample && !overMenus
                && mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) return;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        float s = scale(id);
        int[] size = e.renderer().draw(null, font, 0, 0, sample);
        int w = Math.round(size[0] * s), h = Math.round(size[1] * s);
        int x = x(id, w, sw), y = y(id, h, sh);
        Readout.mirror = x + w / 2 > sw / 2;
        BOUNDS.put(id, new int[]{x, y, w, h});
        // A line every half minute per readout saying where it went, so "it is not
        // showing" can be read off the log instead of argued about.
        long now = System.currentTimeMillis();
        if (now - LOGGED.getOrDefault(id, 0L) > 30_000) {
            LOGGED.put(id, now);
            System.out.println("[Endsight] hud " + id + " at " + x + "," + y + " size " + w + "x" + h + " scale " + s);
        }
        if (Math.abs(s - 1f) < 0.01f) {
            // As designed: drawn straight at its place, the way every readout always was.
            e.renderer().draw(g, font, x, y, sample);
        } else {
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate((float) x, (float) y);
            pose.scale(s, s);
            e.renderer().draw(g, font, 0, 0, sample);
            pose.popMatrix();
        }
        Readout.mirror = false;
    }

    /** Scaled size of an element, for hit-testing in the placement screen. */
    public static int[] size(String id, Font font) {
        Element e = ELEMENTS.get(id);
        if (e == null) return new int[]{8, 8};
        float s = scale(id);
        int[] size = e.renderer().draw(null, font, 0, 0, true);
        return new int[]{Math.max(8, Math.round(size[0] * s)), Math.max(8, Math.round(size[1] * s))};
    }

    /**
     * @param defX 0 is the left edge, 1 the right; the element's own width is kept on
     *             screen when it is resolved, so 1 means "flush right" rather than
     *             "starting off the edge".
     */
    public static void register(String id, String label, float defX, float defY, Renderer r) {
        ELEMENTS.put(id, new Element(id, label, defX, defY, r));
    }

    public static List<String> ids() {
        return new ArrayList<>(ELEMENTS.keySet());
    }

    public static String label(String id) {
        Element e = ELEMENTS.get(id);
        return e == null ? id : e.label();
    }

    public static Renderer renderer(String id) {
        Element e = ELEMENTS.get(id);
        return e == null ? null : e.renderer();
    }

    // ── position ──────────────────────────────────────────────────────────────

    private static float[] fractions(String id) {
        float[] f = POS.get(id);
        if (f != null) return f;
        Element e = ELEMENTS.get(id);
        return e == null ? new float[]{0, 0} : new float[]{e.defX(), e.defY()};
    }

    /**
     * Top-left corner in pixels, clamped so the element cannot be dragged off screen.
     *
     * The clamp is applied on resolve rather than on drag: a window resize can strand an
     * element outside the viewport without anyone touching it, and a readout you cannot
     * see is indistinguishable from a broken one.
     */
    public static int x(String id, int w, int sw) {
        int px = Math.round(fractions(id)[0] * Math.max(0, sw - w));
        return Math.max(0, Math.min(Math.max(0, sw - w), px));
    }

    public static int y(String id, int h, int sh) {
        int py = Math.round(fractions(id)[1] * Math.max(0, sh - h));
        return Math.max(0, Math.min(Math.max(0, sh - h), py));
    }

    public static void setPixels(String id, int px, int py, int w, int h, int sw, int sh) {
        float fx = sw - w <= 0 ? 0 : px / (float) (sw - w);
        float fy = sh - h <= 0 ? 0 : py / (float) (sh - h);
        POS.put(id, new float[]{clamp01(fx), clamp01(fy)});
    }

    public static void reset(String id) {
        POS.remove(id);
        SCALE.remove(id);
    }

    // ── persistence, driven by Config ─────────────────────────────────────────

    public static float[] saved(String id) {
        return POS.get(id);
    }

    public static void restore(String id, float fx, float fy) {
        POS.put(id, new float[]{clamp01(fx), clamp01(fy)});
    }

    public static Map<String, Float> scales() {
        return Map.copyOf(SCALE);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
