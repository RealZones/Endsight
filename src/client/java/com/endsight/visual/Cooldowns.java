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

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Giant's Sword and the tuba, each as its item with a fading overlay while its
 * cooldown runs. Two readouts, each with its own toggle and place.
 *
 * The server says an ability fired in the action bar - "-100 Mana (Giant's Slam)",
 * "-150 Mana (Howl)". A server sets the action bar with its own packet, which never
 * passes the chat listener, so no message event sees it; the text is read straight
 * off the Gui every tick, and its timer resetting is how a repeat of the same text is
 * told from the old one still up. The tuba's "HOWL!" chat line counts too. The length
 * is the item's own "Cooldown: 5s" lore line, and "This ability is on cooldown for
 * 3.7s" - the early press - corrects the overlay to what the server says is left.
 */
public final class Cooldowns {

    private Cooldowns() {
    }

    private static boolean enabled = true;

    private static final Pattern MANA = Pattern.compile("-[\\d,]+ Mana \\((.+?)\\)");
    private static final Pattern COOLDOWN = Pattern.compile("^Cooldown: ([\\d.]+)s");
    private static final Pattern WAIT = Pattern.compile("on cooldown for ([\\d.]+)s");
    /** One of the two: what it matches, its readout, and where its cooldown is right now. */
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
                "Fading cooldown icons for the Giant's Sword and the tuba.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Giant's Sword", "A cooldown icon for Giant's Slam.", () -> SWORD.on, v -> SWORD.on = v),
                        new Setting.Toggle("Tuba", "A cooldown icon for Howl.", () -> TUBA.on, v -> TUBA.on = v)));
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
        for (Timer t : TIMERS) if (t.on && now < t.until) HudLayout.draw(t.id, g, mc.font, false);
    }

    private static int[] drawOne(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample, Timer t) {
        long now = System.currentTimeMillis();
        if (t.icon == null) t.icon = carried(t);
        ItemStack icon = t.icon;
        if (icon == null) icon = Recipes.icon(t.label);
        if (icon == null) icon = new ItemStack(t == TUBA ? Items.GOAT_HORN : Items.DIAMOND_SWORD);

        float left = sample ? 0.55f : remaining(t, now);
        int w = 27, h = 38;
        if (g == null) return new int[]{w, h};

        Draw.roundedRect(g, x, y, 27, 27, 5, Draw.alpha(Theme.surface(), 0.78f));
        Draw.roundedOutline(g, x, y, 27, 27, 5, Draw.alpha(Theme.text(), 0.10f), Theme.surface());
        if (icon != null) g.fakeItem(icon, x + 5, y + 5);

        if (left > 0) {
            Draw.roundedRect(g, x + 2, y + 2, 23, 23, 4, Draw.alpha(Theme.bg(), 0.62f * left));
            int bar = Math.round(23 * (1f - left));
            Draw.roundedRect(g, x + 2, y + 23, Math.max(2, bar), 2, 1, Theme.accent());
            double seconds = sample ? t.fallback * left : Math.max(0, t.until - now) / 1000.0;
            String text = seconds < 10 ? String.format("%.1fs", seconds) : Math.round(seconds) + "s";
            Draw.textCentered(g, font, text, x + 13, y + 31, Theme.text());
        }
        return new int[]{w, h};
    }

    private static float remaining(Timer t, long now) {
        if (now >= t.until || t.until <= t.start) return 0;
        return Math.max(0f, Math.min(1f, (t.until - now) / (float) (t.until - t.start)));
    }
}
