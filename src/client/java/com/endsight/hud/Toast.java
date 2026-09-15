package com.endsight.hud;

import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * The bottom-right popup that says what a keybind just did.
 *
 * A key that toggles something you cannot see the state of is a key you press twice to
 * find out. This is the answer to "did that work" - the module's name, what it is now,
 * and a bar that fills so the thing does not just vanish on a timer you cannot see.
 *
 * Separate from {@link Alert}, which is the mid-screen call for things happening in the
 * world. This is feedback for something YOU did, and feedback belongs out of the way at
 * the edge - dead centre for "you pressed a key" would be shouting.
 *
 * Several can be up at once, newest at the bottom, older ones sliding up to make room.
 * {@code Alert} replaces rather than stacks because two world events at once are two
 * things competing for one glance; two key presses are a list of what you did, and
 * eating the first one because you were quick is just losing information.
 *
 * <h2>Movement</h2>
 * In from the right, out to the right, both eased rather than linear - a panel that
 * arrives at constant speed and stops dead reads as a slide show. Vertical position is
 * animated separately from the slide, so a toast leaving does not make the ones above
 * it jump down a row: they walk down over the same beat the leaver takes to go.
 */
public final class Toast {

    private Toast() {
    }

    private static final long IN_MS = 190;
    private static final long OUT_MS = 230;
    private static final long HOLD_MS = 2_400;
    private static final int PAD = 10;
    private static final int H = 34;
    private static final int BAR_H = 2;
    /**
     * The tick and the bar are inset from the edges rather than run to them.
     *
     * Not taste - geometry. The corner radius is 10 on a box 34 tall, so the left edge
     * is still curving inwards four pixels up from the bottom; anything drawn flush to
     * the border there sticks out through the curve as a little tab. Inset past where
     * the corner has finished and the problem cannot happen.
     */
    private static final int INSET = 10;
    private static final int GAP = 4;
    private static final int MARGIN = 10;
    /** More than this on screen and the oldest goes early - a column of them is clutter. */
    private static final int MAX = 3;

    /**
     * @param big the update note: half again as tall, the text larger, and up for
     *            four times as long. It happens once a version and is the one toast
     *            that is not an answer to a key you just pressed, so it earns the room.
     */
    private record Entry(String title, String state, int color, long born, boolean big) {

        long age() {
            return System.currentTimeMillis() - born;
        }

        long hold() {
            return big ? HOLD_MS * 4 : HOLD_MS;
        }

        int height() {
            return big ? BIG_H : H;
        }

        boolean done() {
            return age() >= IN_MS + hold() + OUT_MS;
        }
    }

    private static final int BIG_H = 50;
    private static final float BIG_TEXT = 1.3f;

    /** The update note: bigger, longer, and never bumped out by a stack of key toasts. */
    public static void big(String title, String state) {
        live.removeIf(e -> e.title().equals(title));
        live.add(new Entry(title, state, Theme.accent(), System.currentTimeMillis(), true));
        while (live.size() > MAX) live.remove(0);
        while (slot.size() < live.size()) slot.add(Float.NaN);
    }

    private static final List<Entry> live = new ArrayList<>();
    /** Where each row is drawn, chased rather than set, so a gap closes smoothly. */
    private static final List<Float> slot = new ArrayList<>();

    /** A module or setting was switched by a key. */
    public static void toggled(String name, boolean on) {
        show(name, on ? "Enabled" : "Disabled", on ? Theme.pos() : Theme.dim());
    }

    /** A setting was stepped or run by a key - the value itself is the message. */
    public static void changed(String name, String value) {
        show(name, value, Theme.accent());
    }

    /**
     * Something needs a look - a module that could not start, say. In the
     * negative colour rather than the accent so it reads as a warning at the edge of
     * the eye, before the words do.
     */
    public static void warn(String title, String detail) {
        show(title, detail, Theme.neg());
    }

    private static void show(String title, String state, int color) {
        // The same key pressed twice is one toast that starts again, not two stacked -
        // otherwise holding a key you meant to tap builds a column.
        live.removeIf(e -> e.title().equals(title));
        live.add(new Entry(title, state, color, System.currentTimeMillis(), false));
        while (live.size() > MAX) {
            // The first small one goes, never the big one.
            int drop = 0;
            while (drop < live.size() - 1 && live.get(drop).big()) drop++;
            live.remove(drop);
        }
        while (slot.size() < live.size()) slot.add(Float.NaN);
    }

    public static void clear() {
        live.clear();
        slot.clear();
    }

    public static void draw(GuiGraphicsExtractor g) {
        if (live.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        live.removeIf(Entry::done);
        while (slot.size() > live.size()) slot.remove(slot.size() - 1);
        while (slot.size() < live.size()) slot.add(Float.NaN);
        if (live.isEmpty()) return;

        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Newest at the bottom, so a new one appears where your eye already is rather
        // than pushing the one you are reading somewhere else.
        int stack = 0;
        for (int i = live.size() - 1; i >= 0; i--) {
            Entry e = live.get(i);
            float wantY = screenH - MARGIN - e.height() - stack;
            stack += e.height() + GAP;

            float y = slot.get(i);
            if (Float.isNaN(y)) y = wantY;                 // first frame: no slide from nowhere
            else y += (wantY - y) * 0.25f;                 // then chased, so gaps close smoothly
            slot.set(i, y);

            draw(g, font, e, screenW, Math.round(y));
        }
    }

    private static void draw(GuiGraphicsExtractor g, Font font, Entry e, int screenW, int y) {
        float ts = e.big() ? BIG_TEXT : 1f;
        int H = e.height();
        int w = Math.max(104, PAD + Math.round(Math.max(font.width(e.title()), font.width(e.state())) * ts) + PAD);
        long age = e.age();
        long HOLD_MS = e.hold();

        // 0 fully on screen, 1 fully off to the right. Eased at both ends: out is a hair
        // quicker than in, because arriving wants to be noticed and leaving does not.
        float slide;
        if (age < IN_MS) {
            slide = 1 - ease(age / (float) IN_MS);
        } else if (age < IN_MS + HOLD_MS) {
            slide = 0;
        } else {
            slide = ease((age - IN_MS - HOLD_MS) / (float) OUT_MS);
        }
        float alpha = 1 - slide;
        if (alpha <= 0.01f) return;

        int x = Math.round(screenW - MARGIN - w + slide * (w + MARGIN));

        Draw.roundedRect(g, x, y, w, H, Theme.RADIUS, Draw.alpha(Theme.line(), 0.9f * alpha));
        Draw.roundedRect(g, x + 1, y + 1, w - 2, H - 2, Theme.RADIUS - 1,
                Draw.alpha(Theme.surface(), 0.97f * alpha));
        // The accent tick every readout in the mod carries, so this is recognisably ours.
        Draw.roundedRect(g, x + 4, y + 11, 2, H - 22, 1, Draw.alpha(e.color(), alpha));

        if (e.big()) {
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate((float) (x + PAD), (float) (y + 9));
            pose.scale(ts, ts);
            Draw.text(g, font, e.title(), 0, 0, Draw.alpha(Theme.text(), alpha));
            Draw.text(g, font, e.state(), 0, 13, Draw.alpha(e.color(), alpha));
            pose.popMatrix();
        } else {
            Draw.text(g, font, e.title(), x + PAD, y + 7, Draw.alpha(Theme.text(), alpha));
            Draw.text(g, font, e.state(), x + PAD, y + 18, Draw.alpha(e.color(), alpha));
        }

        // The bar fills over the hold, so it is full at the moment the toast leaves. Its
        // track is drawn under it at a fraction of the colour, because a bar with nothing
        // behind it reads as a stray line until it is long enough to read as a bar.
        int trackX = x + INSET;
        int trackW = w - INSET * 2;
        int barY = y + H - 5 - BAR_H;
        Draw.roundedRect(g, trackX, barY, trackW, BAR_H, 1,
                Draw.alpha(Theme.line(), 0.55f * alpha));
        float filled = Math.max(0, Math.min(1, (age - IN_MS) / (float) HOLD_MS));
        int barW = Math.round(trackW * filled);
        if (barW > 0) {
            Draw.roundedRect(g, trackX, barY, barW, BAR_H, 1, Draw.alpha(e.color(), 0.9f * alpha));
        }
    }

    /** Ease in and out. A slide at constant speed is the thing that looks cheap. */
    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }
}
