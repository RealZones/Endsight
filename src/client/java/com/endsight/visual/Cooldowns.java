package com.endsight.visual;

import com.endsight.hud.HudLayout;
import com.endsight.storage.Recipes;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Giant's Sword and the tuba, each as its item in a circle with a ring that
 * fills while its cooldown runs. Two readouts, each with its own toggle and place.
 *
 * The server says an ability fired in the action bar - "-100 Mana (Giant's Slam)",
 * "-150 Mana (Howl)". A server sets the action bar with its own packet, which never
 * passes the chat listener, so no message event sees it; the text is read straight
 * off the Gui every tick, and its timer resetting is how a repeat of the same text is
 * told from the old one still up. The tuba's "HOWL!" chat line counts too. The length
 * is the item's own "Cooldown: 5s" lore line, and "This ability is on cooldown for
 * 3.7s" - the early press - corrects the ring to what the server says is left.
 *
 * The circle and the ring are drawn pixel by pixel with coverage at the edges, the
 * way a real renderer would anti-alias them, because a 28-pixel circle built from
 * hard rows looks like a coin from a bad arcade port. The ring's colour is the
 * palette's accent, dim at the start and full at the end; ready is the full ring with
 * a thin light edge, and the readout stays up so ready is something you see.
 */
public final class Cooldowns {

    private Cooldowns() {
    }

    private static boolean enabled = true;

    private static final Pattern MANA = Pattern.compile("-[\\d,]+ Mana \\((.+?)\\)");
    private static final Pattern COOLDOWN = Pattern.compile("^Cooldown: ([\\d.]+)s");
    private static final Pattern WAIT = Pattern.compile("on cooldown for ([\\d.]+)s");
    /** Outer radius; the ring is the outer RING pixels of it, the item sits inside. */
    private static final int R = 14;
    private static final int RING = 3;

    /** One of the two: what it matches, its readout, and where its ring is right now. */
    private static final class Timer {
        final String id, label, item, ability;
        final double fallback;
        boolean on = true;
        ItemStack icon;
        long start, until;

        Timer(String id, String label, String item, String ability, double fallback) {
            this.id = id;
            this.label = label;
            this.item = item;
            this.ability = ability;
            this.fallback = fallback;
        }

        boolean is(ItemStack s) {
            return !s.isEmpty() && s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(item);
        }
    }

    private static final Timer SWORD = new Timer("cooldown.sword", "Giant's Sword", "giant's sword", "slam", 5);
    private static final Timer TUBA = new Timer("cooldown.tuba", "Tuba", "tuba", "howl", 20);
    private static final List<Timer> TIMERS = List.of(SWORD, TUBA);

    private static String lastBar = "";
    private static int lastBarTime;

    public static Module module() {
        return new Module("visual.cooldowns", "Ability Cooldowns",
                "Cooldown rings for the Giant's Sword and the tuba.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Giant's Sword", "A ring for Giant's Slam.", () -> SWORD.on, v -> SWORD.on = v),
                        new Setting.Toggle("Tuba", "A ring for Howl.", () -> TUBA.on, v -> TUBA.on = v)));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!enabled || mc.player == null) return;
            Component c = mc.gui.overlayMessageString;
            int time = mc.gui.overlayMessageTime;
            String bar = c == null ? "" : Zealots.strip(c.getString());
            // New text, or the same text set again: the timer only ever goes up when set.
            boolean fresh = !bar.equals(lastBar) || time > lastBarTime;
            lastBar = bar;
            lastBarTime = time;
            if (!fresh) return;
            Matcher m = MANA.matcher(bar);
            if (!m.find()) return;
            String ability = m.group(1).toLowerCase(Locale.ROOT);
            for (Timer t : TIMERS) if (ability.contains(t.ability)) start(t);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            String line = Zealots.strip(message.getString()).trim();
            if (line.startsWith("HOWL!")) start(TUBA);
            else sync(line);
        });
        // The early-press line is one Ability Spam hides, so it arrives cancelled.
        ClientReceiveMessageEvents.GAME_CANCELED.register((message, overlay) -> {
            if (enabled && !overlay) sync(Zealots.strip(message.getString()).trim());
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "cooldowns"), (g, delta) -> draw(g));
        HudLayout.register(SWORD.id, "Cooldown: Giant's Sword", 0.44f, 0.72f, (g, font, x, y, sample) -> drawOne(g, font, x, y, sample, SWORD));
        HudLayout.register(TUBA.id, "Cooldown: Tuba", 0.52f, 0.72f, (g, font, x, y, sample) -> drawOne(g, font, x, y, sample, TUBA));
    }

    // ── reading ───────────────────────────────────────────────────────────────

    private static void start(Timer t) {
        ItemStack s = carried(t);
        if (s != null) t.icon = s.copy();
        double secs = s == null ? t.fallback : cooldownOf(s, t.fallback);
        long now = System.currentTimeMillis();
        t.start = now;
        t.until = now + (long) (secs * 1000);
    }

    /** "on cooldown for 3.7s" is about what you are holding: put its ring where the server says. */
    private static void sync(String line) {
        Matcher m = WAIT.matcher(line);
        if (!m.find()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        for (Timer t : TIMERS) {
            if (!t.is(held)) continue;
            long now = System.currentTimeMillis();
            long left = (long) (Double.parseDouble(m.group(1)) * 1000);
            double secs = cooldownOf(held, t.fallback);
            t.icon = held.copy();
            t.until = now + left;
            t.start = Math.min(t.start == 0 ? now : t.start, t.until - (long) (secs * 1000));
        }
    }

    /** The item this timer is for, if you have it on you: hand first, then the inventory. */
    private static ItemStack carried(Timer t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        if (t.is(mc.player.getMainHandItem())) return mc.player.getMainHandItem();
        if (t.is(mc.player.getOffhandItem())) return mc.player.getOffhandItem();
        for (ItemStack s : mc.player.getInventory().getNonEquipmentItems()) if (t.is(s)) return s;
        return null;
    }

    private static double cooldownOf(ItemStack s, double fallback) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || s.isEmpty()) return fallback;
        for (Component c : s.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)) {
            Matcher m = COOLDOWN.matcher(Zealots.strip(c.getString()).trim());
            if (m.find()) return Double.parseDouble(m.group(1));
        }
        return fallback;
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.options.hideGui) return;
        long now = System.currentTimeMillis();
        // Always up, not only while cooling: a full green ring is the "ready" you glance at.
        for (Timer t : TIMERS) if (t.on) HudLayout.draw(t.id, g, mc.font, false);
    }

    private static int[] drawOne(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample, Timer t) {
        long now = System.currentTimeMillis();
        float p;
        double left;
        if (t.icon == null) t.icon = carried(t);
        ItemStack icon = t.icon;
        if (icon == null) icon = Recipes.icon(t.label);
        if (icon == null) icon = new ItemStack(t == TUBA ? Items.GOAT_HORN : Items.DIAMOND_SWORD);
        boolean ready = !sample && now >= t.until;
        if (sample) {
            p = 0.6f;
            left = 2.0;
        } else if (ready) {
            p = 1f;
            left = 0;
        } else {
            long total = Math.max(1, t.until - t.start);
            p = Math.max(0f, Math.min(1f, (now - t.start) / (float) total));
            left = Math.max(0, t.until - now) / 1000.0;
        }
        String text = left <= 0 ? "" : left < 10 ? String.format("%.1fs", left) : Math.round(left) + "s";
        int d = 2 * R + 2, pillH = 11;
        int w = d, h = d + 3 + pillH;
        if (g == null) return new int[]{w, h};

        int cx = x + R + 1, cy = y + R + 1;
        // The face, the track, the ring over it - dim at the start, the full accent at
        // the end - then the item on top.
        int ringColour = Draw.lerp(Draw.alpha(Theme.accent(), 0.55f), Theme.accent() | 0xFF000000, p);
        disc(g, cx, cy, R, Draw.alpha(Theme.surface(), 0.94f));
        ring(g, cx, cy, R - RING, R, 1f, Draw.alpha(Theme.text(), 0.10f));
        ring(g, cx, cy, R - RING, R, p, ringColour);
        // Ready: the full ring gets a thin light edge round it, lit rather than recoloured.
        if (ready) ring(g, cx, cy, R, R + 1, 1f, Draw.alpha(Theme.text(), 0.35f));
        if (icon != null) g.fakeItem(icon, cx - 8, cy - 8);
        if (!text.isEmpty()) {
            int pw = font.width(text) + 8;
            int px = cx - pw / 2, py = y + d + 3;
            Draw.roundedRect(g, px, py, pw, pillH, 3, Draw.alpha(Theme.surface(), 0.94f));
            Draw.textCentered(g, font, text, cx, py + 2, Theme.text());
        }
        return new int[]{w, h};
    }

    /** A filled circle with a soft edge: each pixel's alpha is how much of it the circle covers. */
    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int colour) {
        for (int py = -r; py < r; py++) {
            double dy = py + 0.5;
            int runStart = Integer.MIN_VALUE;
            int runColour = 0;
            for (int px = -r; px <= r; px++) {
                int c = 0;
                if (px < r) {
                    double dx = px + 0.5;
                    double cov = Math.max(0, Math.min(1, r - Math.sqrt(dx * dx + dy * dy) + 0.5));
                    if (cov > 0.02) c = scaleAlpha(colour, cov);
                }
                if (c != runColour) {
                    if (runStart != Integer.MIN_VALUE) Draw.rect(g, cx + runStart, cy + py, px - runStart, 1, runColour);
                    runStart = c == 0 ? Integer.MIN_VALUE : px;
                    runColour = c;
                }
            }
        }
    }

    /**
     * The part of a ring from the top, clockwise, up to {@code turn} of a full turn -
     * both edges soft, drawn as runs of pixels that share a colour.
     */
    private static void ring(GuiGraphicsExtractor g, int cx, int cy, int rIn, int rOut, float turn, int colour) {
        if (turn <= 0) return;
        for (int py = -rOut; py < rOut; py++) {
            double dy = py + 0.5;
            int runStart = Integer.MIN_VALUE;
            int runColour = 0;
            for (int px = -rOut; px <= rOut; px++) {
                int c = 0;
                if (px < rOut) {
                    double dx = px + 0.5;
                    double d = Math.sqrt(dx * dx + dy * dy);
                    double cov = Math.max(0, Math.min(1, Math.min(d - rIn + 0.5, rOut - d + 0.5)));
                    if (cov > 0.02) {
                        double a = (Math.atan2(dx, -dy) / (2 * Math.PI) + 1) % 1;
                        if (a <= turn) c = scaleAlpha(colour, cov);
                    }
                }
                if (c != runColour) {
                    if (runStart != Integer.MIN_VALUE) Draw.rect(g, cx + runStart, cy + py, px - runStart, 1, runColour);
                    runStart = c == 0 ? Integer.MIN_VALUE : px;
                    runColour = c;
                }
            }
        }
    }

    private static int scaleAlpha(int colour, double by) {
        int a = (colour >>> 24) == 0 ? 255 : colour >>> 24;
        return ((int) Math.round(a * by) << 24) | (colour & 0xFFFFFF);
    }
}
