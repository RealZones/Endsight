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
 * Ordinary alerts follow vanilla's title overlay: dead centre, title above the midline at
 * 4x, subtitle below at 2x, 2 ticks in and 6 out. What is NOT vanilla is the size and
 * opacity, which vanilla hard-locks and which need to be adjustable - so those are
 * settings and everything else is copied. Countdowns put a smaller caption above one
 * large numeral so the remaining time is what you read first.
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
    private static boolean countdown;

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
        countdown = false;
        text = title;
        sub = subtitle;
        color = rgb;
        Alert.weight = weight;
        shown = System.currentTimeMillis();
        until = shown + (long) (seconds * 1000);
    }

    public static void countdown(String caption, int remaining, int rgb, float weight, double seconds) {
        boolean continuing = countdown && caption.equals(text) && active();
        long originalShown = shown;
        show(caption, Integer.toString(remaining), rgb, weight, Math.max(1.5, seconds));
        countdown = true;
        // Changing the numeral must not restart the fade on every second.
        if (continuing) shown = originalShown;
    }

    public static void clear() {
        text = null;
        countdown = false;
    }

    /** A refreshed buff must dismiss its own countdown without hiding another alert. */
    public static void clear(String prefix) {
        if (text != null && text.regionMatches(true, 0, prefix, 0, prefix.length())) clear();
    }

    public static boolean active() {
        return text != null && System.currentTimeMillis() < until;
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

        if (countdown) {
            pose.pushMatrix();
            pose.translate(sw / 2f, sh / 2f);
            pose.scale(2f * s, 2f * s);
            Draw.textCentered(g, font, text, 0, -20, Draw.alpha(Theme.text(), alpha));
            pose.popMatrix();
            pose.pushMatrix();
            pose.translate(sw / 2f, sh / 2f);
            pose.scale(5f * s, 5f * s);
            Draw.textCentered(g, font, sub, 0, 0, Draw.alpha(color, alpha));
            pose.popMatrix();
            return;
        }

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
