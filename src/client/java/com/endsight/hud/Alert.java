package com.endsight.hud;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The mid-screen popup, shared by every module that needs to interrupt you.
 *
 * Lives here rather than inside one module because a second module wanted it the day
 * after the first one had it, and two copies of a fade curve drift apart. There is one
 * popup on screen at a time by design - two things shouting at once is the same as
 * neither, so a new alert replaces whatever was showing.
 *
 * Geometry is vanilla's title overlay exactly: dead centre, title above the midline at
 * 4x, subtitle below at 2x, 2 ticks in and 6 out. What is NOT vanilla is the size and
 * opacity, which vanilla hard-locks and which need to be adjustable - so those are
 * settings and everything else is copied.
 */
public final class Alert {

    private Alert() {
    }

    private static String text;
    private static String sub;
    private static int color;
    private static float weight = 1f;
    private static long shown;
    private static long until;

    /** Shared appearance, driven by the sliders on Miniboss Alerts. */
    private static double scalePct = 80;
    private static double opacityPct = 76;

    public static void setScale(double percent) {
        scalePct = percent;
    }

    public static double scale() {
        return scalePct;
    }

    public static void setOpacity(double percent) {
        opacityPct = percent;
    }

    public static double opacity() {
        return opacityPct;
    }

    /**
     * @param weight 1 for an ordinary call, higher for something that outranks it -
     *               scales the text and firms up the colour so the more important
     *               event is visibly heavier rather than merely another flash.
     */
    public static void show(String title, String subtitle, int rgb, float weight, double seconds) {
        text = title;
        sub = subtitle;
        color = rgb;
        Alert.weight = weight;
        shown = System.currentTimeMillis();
        until = shown + (long) (seconds * 1000);
    }

    public static void clear() {
        text = null;
    }

    public static void draw(GuiGraphicsExtractor g) {
        if (text == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        long now = System.currentTimeMillis();
        if (now >= until) {
            text = null;
            return;
        }

        long since = now - shown;
        long left = until - now;
        float fade = Math.min(1f, Math.min(since / 100f, left / 300f));
        float alpha = fade * (float) Math.min(1.0, (opacityPct / 100.0) * weight);
        if (alpha <= 0.01f) return;

        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float s = (float) (scalePct / 100.0) * weight;

        g.nextStratum();
        var pose = g.pose();

        // Scale goes through the pose because Font has no size - text is drawn once and
        // transformed, which is exactly how vanilla gets its 4x title. Title and
        // subtitle take the same multiplier so shrinking does not smudge the subtitle.
        pose.pushMatrix();
        pose.translate(sw / 2f, sh / 2f);
        pose.scale(4f * s, 4f * s);
        Draw.textCentered(g, font, text, 0, -10, Draw.alpha(color, alpha));
        pose.popMatrix();

        if (sub != null && !sub.isEmpty()) {
            pose.pushMatrix();
            pose.translate(sw / 2f, sh / 2f);
            pose.scale(2f * s, 2f * s);
            Draw.textCentered(g, font, sub, 0, 5, Draw.alpha(Theme.muted(), alpha));
            pose.popMatrix();
        }
    }
}
