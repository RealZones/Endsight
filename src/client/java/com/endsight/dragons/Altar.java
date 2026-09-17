package com.endsight.dragons;

import com.endsight.hud.HudLayout;
import com.endsight.hud.Readout;
import com.endsight.qol.Drops;
import com.endsight.qol.Rarity;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Draconic Altar this session: sacrifices made, essence taken, and what the
 * bonus rolls gave.
 *
 * A sacrifice is the "Confirm Sacrifice" window closing and the server saying
 * "+100 Dragon Essence" straight after; "BONUS! You received 2x Superior Dragon
 * Fragment!" inside the same moment is that sacrifice's bonus. The window is the
 * anchor because essence arrives from dragons and quests too, in the same words.
 *
 * Bonus items by name, rarest first, in their rarity's colour, and the rate under
 * the count - "1 in 6" - since that is the number people argue about.
 */
public final class Altar {

    private Altar() {
    }

    private static final Pattern ESSENCE = Pattern.compile("^\\+([\\d,]+) Dragon Essence$");
    private static final Pattern BONUS = Pattern.compile("^BONUS! You received (\\d+)x (.+?)!$");
    private static final String BUTTON = "Draconic Altar";
    private static final long DRAGON_MS = 15_000;
    private static long dragonAt;

    private static boolean enabled = true;
    private static int sacrifices, bonuses;
    private static long essence;
    private static final Map<String, Integer> items = new LinkedHashMap<>();
    private static long sessionStart, closedAt;
    private static boolean confirmOpen;

    public static Module module() {
        return new Module("dragon.altar", "Altar Tracker",
                "Sacrifices, essence and bonus items from the Draconic Altar.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of(new Setting.Action("Reset session", "Zero the counts and the clock.", "Reset", Altar::reset)));
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> c)) return;
            // The title first; failing that, the "Draconic Altar" button among the slots,
            // which only arrive a tick or two after the window does.
            if (isConfirm(c)) confirmOpen = true;
            else ScreenEvents.afterTick(screen).register(s -> {
                if (!confirmOpen && isConfirm(c)) confirmOpen = true;
            });
            ScreenEvents.remove(screen).register(s -> {
                if (!confirmOpen) return;
                confirmOpen = false;
                closedAt = System.currentTimeMillis();
            });
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            long now = System.currentTimeMillis();
            String line = Zealots.strip(message.getString()).trim();
            // Essence also comes off a dragon, seconds after it dies, and in a quest's box
            // - the box is many lines in one message, which the anchored match refuses.
            if (line.contains("has de-spawned")) {
                dragonAt = now;
                return;
            }
            if (now - dragonAt < DRAGON_MS) return;
            Matcher m = ESSENCE.matcher(line);
            if (m.find()) {
                if (sessionStart == 0) sessionStart = now;
                sacrifices++;
                essence += Long.parseLong(m.group(1).replace(",", ""));
                return;
            }
            m = BONUS.matcher(line);
            if (m.find()) {
                bonuses++;
                items.merge(m.group(2).trim(), Integer.parseInt(m.group(1)), Integer::sum);
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "altar"), (g, delta) -> draw(g));
        HudLayout.register("altar.tracker", "Altar Tracker", 0.006f, 0.62f, (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    private static boolean isConfirm(AbstractContainerScreen<?> c) {
        if (Zealots.strip(c.getTitle().getString()).toLowerCase(Locale.ROOT).contains("sacrifice")) return true;
        for (Slot s : c.getMenu().slots) {
            if (!s.getItem().isEmpty() && Zealots.strip(s.getItem().getHoverName().getString()).trim().equals(BUTTON)) return true;
        }
        return false;
    }

    /** 2600 as is, 12,400 as 12.4k, the way the readouts write big numbers. */
    private static String compact(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1e6).replace(".0M", "M");
        if (n >= 10_000) return String.format("%.1fk", n / 1e3).replace(".0k", "k");
        return String.format("%,d", n);
    }

    private static void reset() {
        sacrifices = bonuses = 0;
        essence = 0;
        items.clear();
        sessionStart = 0;
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (sacrifices == 0) return;          // nothing to say until the first one
        HudLayout.draw("altar.tracker", g, mc.font, false);
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        int n = sample ? 26 : sacrifices, b = sample ? 4 : bonuses;
        long e = sample ? 2_600 : essence;
        Map<String, Integer> got = items;
        if (sample) {
            got = new LinkedHashMap<>();
            got.put("Superior Dragon Fragment", 3);
            got.put("Ritual Residue", 5);
        }
        long elapsed = sample ? 1_800_000 : (sessionStart == 0 ? 0 : System.currentTimeMillis() - sessionStart);
        String perHour = elapsed < 60_000 ? "" : compact(Math.round(e * 3_600_000.0 / elapsed)) + "/h";
        String rate = b == 0 ? "-" : "1 in " + String.format("%.1f", n / (double) b);

        String[][] rows = {
                {"Sacrifices", "", String.valueOf(n)},
                {"Essence", perHour, compact(e)},
                {"Bonus", "", rate}};
        List<Map.Entry<String, Integer>> list = new ArrayList<>(got.entrySet());
        list.sort((p, q) -> Integer.compare(Rarity.rank(q.getKey()), Rarity.rank(p.getKey())));

        int w = font.width("ALTAR") + 20;
        for (String[] r : rows) w = Math.max(w, 8 + font.width(r[0]) + 14 + font.width(r[1]) + 10 + font.width(r[2]));
        for (Map.Entry<String, Integer> it : list) w = Math.max(w, 8 + font.width(it.getKey()) + 12 + font.width(String.valueOf(it.getValue())));
        int h = Readout.ROW_H + 3 + (rows.length + list.size()) * (Readout.ROW_H + 2);
        if (g == null) return new int[]{w, h};

        Readout.tick(g, x, y, w, Theme.accent());
        Draw.text(g, font, "ALTAR", Readout.left(x), y, Theme.muted());
        int ry = y + Readout.ROW_H + 3;
        for (String[] r : rows) {
            Draw.text(g, font, r[0], Readout.left(x), ry, Theme.dim());
            if (!r[1].isEmpty()) Draw.textRight(g, font, r[1], Readout.right(x, w) - font.width(r[2]) - 10, ry, Theme.dim());
            Draw.textRight(g, font, r[2], Readout.right(x, w), ry, Theme.text());
            ry += Readout.ROW_H + 2;
        }
        for (Map.Entry<String, Integer> it : list) {
            int colour = Rarity.colour(it.getKey(), Drops.colour(Drops.tierOf(it.getKey())));
            Draw.text(g, font, it.getKey(), Readout.left(x), ry, colour | 0xFF000000);
            Draw.textRight(g, font, String.valueOf(it.getValue()), Readout.right(x, w), ry, Theme.text());
            ry += Readout.ROW_H + 2;
        }
        return new int[]{w, h};
    }
}
