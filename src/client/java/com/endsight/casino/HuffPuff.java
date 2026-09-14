package com.endsight.casino;

import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.hud.Readout;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Profit and loss at Huff 'n' Puff, the casino slot.
 *
 * The server only speaks when you win - "Huff 'n' Puff » +22,000 Coins (0.22x) |
 * Milk Pail x3" - and says nothing at all for a loss, so a chat-only tracker would
 * think you never lose. The stake is read the moment you pull: the PULL button's
 * own tooltip says "Stakes 100,000 Coins", and the click on it is the round. A win
 * line then adds to the winnings; no line before the next pull is the loss it is.
 * A win with no pull seen (the menu was open before the mod was) still counts, with
 * the stake worked back from the multiplier.
 *
 * Kept as a session and an all-time total, because the honest number at a slot
 * machine is the one you cannot reset.
 */
public final class HuffPuff {

    private HuffPuff() {
    }

    private static final String TITLE = "Huff 'n' Puff";
    private static final Pattern WIN = Pattern.compile("^Huff 'n' Puff » \\+([\\d,]+) Coins \\(([\\d.]+)x\\)");
    private static final Pattern STAKE = Pattern.compile("Stakes?\\s+([\\d,]+)\\s+Coins");
    private static final Pattern BET = Pattern.compile("Bet:\\s+([\\d,]+)\\s+Coins");
    private static final String SESSION = "Session";
    private static final String TOTAL = "Total";

    private static boolean enabled = true;
    private static String mode = SESSION;
    private static double hideAfterMin = 5;

    /** rounds, wagered, won, best - one set for the session, one for all time. */
    private static final long[] session = new long[4];
    private static final long[] total = new long[4];
    private static long lastActivity;
    private static long lastStake;
    private static boolean pulled;

    public static Module module() {
        return new Module("casino.huff", "Huff 'n' Puff",
                "Profit and loss at the slot, this session or all time.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Mode",
                                "This session, or every pull since you started keeping count.",
                                List.of(SESSION, TOTAL), () -> mode, v -> mode = v),
                        new Setting.Action("Move readout",
                                "Drag it, and every other readout, where you want.",
                                "Move", HudPlacementScreen::open),
                        new Setting.Slider("Hide when idle",
                                "Fade out after this long with no pull. 0 keeps it up.",
                                0, 15, 1, () -> hideAfterMin, v -> hideAfterMin = v, "m"),
                        new Setting.Action("Reset",
                                "Zero whichever count is showing.",
                                "Reset", HuffPuff::reset)));
    }

    public static void init() {
        load();
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            if (!Zealots.strip(screen.getTitle().getString()).trim().equals(TITLE)) return;
            ScreenMouseEvents.afterMouseClick(screen).register((s, click, handled) -> {
                pulled(container);
                return handled;
            });
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled) return;
            Matcher m = WIN.matcher(Zealots.strip(message.getString()).trim());
            if (m.find()) won(parse(m.group(1)), Double.parseDouble(m.group(2)));
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "huff"),
                (g, delta) -> draw(g));
        HudLayout.register("casino.huff", "Huff 'n' Puff", 0.006f, 0.7f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    // ── reading ───────────────────────────────────────────────────────────────

    /** After any click in the menu: if it landed on PULL, that was a round at the stake PULL names. */
    private static void pulled(AbstractContainerScreen<?> screen) {
        if (!enabled) return;
        Slot slot = screen.hoveredSlot;
        if (slot == null || !slot.hasItem()) return;
        ItemStack stack = slot.getItem();
        if (!Zealots.strip(stack.getHoverName().getString()).trim().equalsIgnoreCase("PULL")) return;
        long stake = stakeOf(screen, stack);
        if (stake <= 0) return;
        lastStake = stake;
        pulled = true;
        for (long[] t : new long[][]{session, total}) {
            t[0]++;
            t[1] += stake;
        }
        lastActivity = System.currentTimeMillis();
        save();
    }

    /** The stake off PULL's own tooltip, or the bet item beside it, or the last one seen. */
    private static long stakeOf(AbstractContainerScreen<?> screen, ItemStack pull) {
        Minecraft mc = Minecraft.getInstance();
        for (Component line : pull.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)) {
            Matcher m = STAKE.matcher(Zealots.strip(line.getString()));
            if (m.find()) return parse(m.group(1));
        }
        for (Slot s : screen.getMenu().slots) {
            if (!s.hasItem()) continue;
            Matcher m = BET.matcher(Zealots.strip(s.getItem().getHoverName().getString()));
            if (m.find()) return parse(m.group(1));
        }
        return lastStake;
    }

    private static void won(long coins, double multiplier) {
        if (!pulled && multiplier > 0) {
            // Never saw the pull: count the round, with the stake the multiplier implies.
            long stake = Math.round(coins / multiplier);
            for (long[] t : new long[][]{session, total}) {
                t[0]++;
                t[1] += stake;
            }
        }
        pulled = false;
        for (long[] t : new long[][]{session, total}) {
            t[2] += coins;
            t[3] = Math.max(t[3], coins);
        }
        lastActivity = System.currentTimeMillis();
        save();
    }

    private static long parse(String n) {
        try {
            return Long.parseLong(n.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void reset() {
        long[] t = TOTAL.equals(mode) ? total : session;
        java.util.Arrays.fill(t, 0);
        save();
    }

    // ── the file ──────────────────────────────────────────────────────────────

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("huff-total.txt");
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), List.of("# rounds wagered won best - all time at Huff 'n' Puff",
                    total[0] + " " + total[1] + " " + total[2] + " " + total[3]), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write huff-total.txt: " + e);
        }
    }

    private static void load() {
        if (!Files.exists(file())) return;
        try {
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.trim().split("\\s+");
                for (int i = 0; i < 4 && i < p.length; i++) total[i] = Long.parseLong(p[i]);
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("[Endsight] could not read huff-total.txt: " + e);
        }
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.level == null || mc.options.hideGui) return;
        boolean open = mc.screen instanceof AbstractContainerScreen<?> s
                && Zealots.strip(s.getTitle().getString()).trim().equals(TITLE);
        if (!open && hideAfterMin > 0 && (lastActivity == 0
                || System.currentTimeMillis() - lastActivity > hideAfterMin * 60_000)) return;
        int[] size = drawAt(null, mc.font, 0, 0, false);
        drawAt(g, mc.font,
                HudLayout.x("casino.huff", size[0], mc.getWindow().getGuiScaledWidth()),
                HudLayout.y("casino.huff", size[1], mc.getWindow().getGuiScaledHeight()), false);
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        long[] t = TOTAL.equals(mode) ? total : session;
        long rounds = sample ? 42 : t[0], wagered = sample ? 4_200_000 : t[1], won = sample ? 3_100_000 : t[2], best = sample ? 2_075_000 : t[3];
        long pl = won - wagered;
        String[][] rows = {
                {"Rounds", String.valueOf(rounds)},
                {"Wagered", coins(wagered)},
                {"Won", coins(won)},
                {"P/L", (pl >= 0 ? "+" : "-") + coins(Math.abs(pl))},
                {"Best", coins(best)}};
        String title = "HUFF 'N' PUFF" + (TOTAL.equals(mode) ? "  TOTAL" : "");
        int w = font.width(title) + 20;
        for (String[] r : rows) w = Math.max(w, Readout.width(font, r[0], r[1]));
        int h = Readout.ROW_H + 3 + rows.length * (Readout.ROW_H + 2);
        if (g != null) {
            Draw.text(g, font, title, x + 8, y, Theme.muted());
            int ry = y + Readout.ROW_H + 3;
            for (String[] r : rows) {
                Draw.text(g, font, r[0], x + 8, ry, Theme.dim());
                int colour = r[0].equals("P/L") ? (pl >= 0 ? Theme.pos() : Theme.neg()) : Theme.text();
                Draw.textRight(g, font, r[1], x + w, ry, colour);
                ry += Readout.ROW_H + 2;
            }
        }
        return new int[]{w, h};
    }

    /** 560k, 2.08M, 1.2B - the way the server writes coins in chat. */
    private static String coins(long n) {
        if (n >= 1_000_000_000L) return trim(n / 1e9) + "B";
        if (n >= 1_000_000L) return trim(n / 1e6) + "M";
        if (n >= 1_000L) return trim(n / 1e3) + "k";
        return String.valueOf(n);
    }

    private static String trim(double v) {
        String s = String.format("%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
