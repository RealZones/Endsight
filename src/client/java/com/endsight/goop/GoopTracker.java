package com.endsight.goop;

import com.endsight.hud.HudCard;
import com.endsight.hud.HudLayout;
import com.endsight.hud.TrackerHud;
import com.endsight.qol.Drops;
import com.endsight.qol.NpcPrices;
import com.endsight.qol.Rarity;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Rift's Goop boss: a countdown to each form the server makes you wait for, and the
 * fights you were in - your place and share of the damage, whether you were paid, and
 * what it gave. The reading is {@link GoopFight}; this is where it is shown and kept.
 *
 * The timer is its own HUD element because it is read mid-fight, next to the crosshair,
 * and the totals are not. Chat is read on GAME and GAME_CANCELED both: Ability Spam's
 * Goop option hides the encounter's narration, and a hidden line arrives on
 * GAME_CANCELED instead - once, so nothing is counted twice.
 */
public final class GoopTracker {
    private GoopTracker() {
    }

    private static final String ID = "tracker.goop", TIMER = "goop.timer";
    /** Each fight you place costs one. */
    public static final String COST_ITEM = "Goop";
    /** Fights at the altar come every minute or so; this long without one and you have left. */
    private static final long SHOWN_MS = 90_000;
    private static final long PLACEMENT_COOLDOWN_MS = 6_000;
    private static final long PLACEMENT_READY_MS = 5_000;

    private static final GoopFight fight = new GoopFight();
    private static boolean enabled = true, timer = true, dirty, historySafe = true;
    private static String period = "Session";
    private static Counts session = new Counts(), total = new Counts();
    private static long savedAt, bookedAt;
    private static Object level;

    /** Saved as goop-tracker.json; a session is the same shape, never saved. */
    static final class Counts {
        long fights, paid, placed;
        /** Time between fights at the altar, so a profit per hour leaves out the time you were elsewhere. */
        long activeMs;
        final Map<String, Integer> drops = new LinkedHashMap<>();

        void add(GoopFight.Result r) {
            fights++;
            if (r.paid()) paid++;
            if (r.placed()) placed++;
            r.drops().forEach((item, count) -> drops.merge(item, count, Integer::sum));
        }
    }

    public static Module timerModule() {
        return new Module(TIMER, "Goop Timer", "Counts down to the next Goop form and placement.", "The End",
                () -> timer, v -> timer = v, List.of());
    }

    public static Module module() {
        return new Module(ID, "Goop Tracker", "Your Goop fights, pay and loot; /drops goop lists them.",
                "The End", () -> enabled, v -> enabled = v, List.of(
                new Setting.Choice("Period", "Show this session or saved totals.", List.of("Session", "All Time"),
                        () -> period, v -> period = v),
                new Setting.Action("Drop prices", "Set what each drop and a Goop are worth.", "Edit",
                        com.endsight.dragons.DropPricesScreen::openGoop),
                new Setting.Action("Reset session", "Clear this session without changing saved totals.", "Reset",
                        GoopTracker::resetSession)));
    }

    public static void init() {
        load();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) message(message); });
        ClientReceiveMessageEvents.GAME_CANCELED.register((message, overlay) -> { if (!overlay) message(message); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { leave(); save(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> save());
        ClientTickEvents.END_CLIENT_TICK.register(GoopTracker::tick);
        // Same switch as the Cache Tracker's: the underlined [Session] in the header flips the period.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!HudLayout.interactiveScreen(screen)) return;
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (click.button() != 0 || !enabled || mc.options.hideGui || !shown(System.currentTimeMillis())) return true;
                int[] bounds = HudLayout.bounds(ID);
                if (bounds == null) return true;
                float scale = HudLayout.scale(ID);
                int labelW = mc.font.width(state());
                double right = bounds[0] + bounds[2] - TrackerHud.PAD * scale;
                if (click.x() < right - (labelW + 2) * scale || click.x() >= right + 2 * scale
                        || click.y() < bounds[1] || click.y() >= bounds[1] + TrackerHud.HEADER_H * scale) return true;
                period = period.equals("All Time") ? "Session" : "All Time";
                com.endsight.Config.save(com.endsight.EndsightClient.registry());
                return false;
            });
        });
        HudLayout.register(TIMER, "Goop Timer", .44f, .3f, GoopTracker::drawTimer);
        HudLayout.register(ID, "Goop Tracker", .006f, .42f, GoopTracker::drawCard);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "goop"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;
            if (timer && (fight.phase().waiting() || placementCount(fight, System.currentTimeMillis()) != null))
                HudLayout.draw(TIMER, g, mc.font, false);
            if (enabled && shown(System.currentTimeMillis())) HudLayout.draw(ID, g, mc.font, false);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            var goop = ClientCommands.literal("goop").executes(c -> say(true));
            goop.then(ClientCommands.literal("session").executes(c -> say(false)));
            goop.then(ClientCommands.literal("reset").executes(c -> { resetSession(); return say(false); }));
            dispatcher.register(ClientCommands.literal("drops").then(goop));
        });
    }

    private static void message(Component message) {
        if (!enabled && !timer) return;
        Minecraft mc = Minecraft.getInstance();
        String me = mc.player == null ? "" : mc.player.getName().getString();
        long now = System.currentTimeMillis();
        // The end box is one message of many lines; the payout is read a line at a time.
        for (String part : Zealots.strip(message.getString()).split("\\R")) book(fight.line(part, now, me));
    }

    private static void tick(Minecraft mc) {
        if (level != mc.level) {
            leave();
            level = mc.level;
        }
        book(fight.poll(System.currentTimeMillis()));
        if (dirty && System.currentTimeMillis() - savedAt > 2_000) save();
    }

    /** Off the island: a fight that ended is still yours to keep, the one on screen is not. */
    private static void leave() {
        book(fight.poll(Long.MAX_VALUE));
        fight.reset();
    }

    private static void book(GoopFight.Result r) {
        if (r == null || !enabled) return;
        session.add(r);
        total.add(r);
        r.drops().keySet().forEach(NpcPrices::track);
        long now = System.currentTimeMillis();
        if (bookedAt > 0 && now - bookedAt <= SHOWN_MS) {
            session.activeMs += now - bookedAt;
            total.activeMs += now - bookedAt;
        }
        bookedAt = now;
        dirty = true;
    }

    /** Everything the price list shows for this tracker: what you spend, then what you earned. */
    public static Map<String, Integer> dropCounts() {
        total.drops.keySet().forEach(NpcPrices::track);
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put(COST_ITEM, (int) Math.min(Integer.MAX_VALUE, total.placed));
        total.drops.forEach((item, count) -> { if (count > 0 && !item.equals(COST_ITEM)) out.put(item, count); });
        return out;
    }

    private static void resetSession() {
        session = new Counts();
        bookedAt = 0;
    }

    private static boolean shown(long now) {
        return fight.phase() != GoopFight.Phase.NONE || fight.lastSeen() >= 0 && now - fight.lastSeen() < SHOWN_MS;
    }

    private static String state() {
        return period.equals("All Time") ? "[Total]" : "[Session]";
    }

    // ---------------------------------------------------------------- the timer

    /*
     * The count is what gets read mid-fight, so it is the big part and the label only says
     * what it counts. The first version was a tracker row - "Goop", then "Rift Corruptor
     * 0:19" - and Fear's screenshot showed the stage name and fight clock taking the space
     * while the number he wanted was no bigger than the rest. Every wait reads the same
     * now; which form comes next does not change when to be ready.
     */
    private static final String SPAWNS = "Goop spawns in";
    private static final String PLACEMENT = "Goop cooldown", PLACE_GOOP = "Place Goop";
    private static final float BIG = 3f;
    /** Sized for the widest count, so the element does not shift as the digits change. */
    private static final String WIDEST = "00.0s";

    /** The count to the next spawn, or null while a form is up and nothing is waiting. */
    static String count(GoopFight f, long now) {
        if (!f.phase().waiting()) return null;
        long left = f.remaining(now);
        return left > 0 ? GoopFight.seconds(left) : "now";
    }

    static String placementCount(GoopFight f, long now) {
        if (f.finishedAt() < 0) return null;
        long elapsed = now - f.finishedAt();
        if (elapsed < 0 || elapsed >= PLACEMENT_COOLDOWN_MS + PLACEMENT_READY_MS) return null;
        return elapsed < PLACEMENT_COOLDOWN_MS ? GoopFight.seconds(PLACEMENT_COOLDOWN_MS - elapsed) : PLACE_GOOP;
    }

    private static int[] drawTimer(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        int w = Math.max(Math.max(font.width(SPAWNS), font.width(PLACEMENT)),
                Math.round(Math.max(font.width(WIDEST), font.width(PLACE_GOOP)) * BIG));
        int h = font.lineHeight + 2 + Math.round(font.lineHeight * BIG);
        if (g == null) return new int[]{w, h};
        long now = System.currentTimeMillis();
        String placement = !sample ? placementCount(fight, now) : null;
        String count = sample ? "9.2s" : placement != null ? placement : count(fight, now);
        if (count == null) return new int[]{w, h};
        boolean soon = sample || (placement != null
                ? now - fight.finishedAt() >= PLACEMENT_COOLDOWN_MS - 3_000
                : fight.remaining(now) < 3_000);
        Draw.textCentered(g, font, placement != null ? PLACEMENT : SPAWNS, x + w / 2, y,
                Draw.lerp(Theme.muted(), Theme.text(), 0.8f));
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x + w / 2f, y + font.lineHeight + 2f);
        pose.scale(BIG, BIG);
        Draw.textCentered(g, font, count, 0, 0, soon ? Theme.accent() : Theme.text());
        pose.popMatrix();
        return new int[]{w, h};
    }

    // ---------------------------------------------------------------- the totals

    /*
     * Fights, paid and placed share one line of words instead of a row each. As rows they
     * stacked into a column of the same number - a party that takes turns at the altar and
     * is paid every time reads 2, 2, 2 - and Fear's screenshot showed that saying nothing at
     * a glance. The column stays for the drops, where the numbers differ and are the point.
     */
    static List<HudCard.Row> rows(Counts c, int since, int limit) {
        List<HudCard.Row> rows = new ArrayList<>();
        // §r goes back to the row's own label colour, so only the numbers stand out.
        rows.add(new HudCard.Row(stats(c, "§f", "§r"), ""));
        String turn = turn(since);
        if (turn != null) rows.add(new HudCard.Row(turn, ""));
        if (c.fights > 0) {
            var takings = NpcPrices.takings(c.drops, COST_ITEM, c.placed);
            String unknown = takings.unpriced() ? " §7+ ?" : "";
            int colour = takings.profit() < 0 ? Theme.neg() : Theme.pos();
            rows.add(new HudCard.Row("Profit", NpcPrices.coins(takings.profit()) + unknown, colour));
            // Under a minute of fights is one lucky drop scaled up to an hour.
            if (c.activeMs >= 60_000) rows.add(new HudCard.Row("Profit/h",
                    NpcPrices.coins(Math.round(takings.profit() * (3_600_000.0 / c.activeMs))) + unknown, colour));
        }
        List<Map.Entry<String, Integer>> drops = ordered(c);
        for (var drop : drops.stream().limit(limit).toList())
            rows.add(new HudCard.Row("§" + code(Rarity.colour(drop.getKey(), 0xFFFFFF)) + drop.getKey(),
                    String.format(Locale.ROOT, "%,d", drop.getValue())));
        if (drops.size() > limit) rows.add(new HudCard.Row("More items", "+" + (drops.size() - limit) + " §8(/drops goop)"));
        return rows;
    }

    /** "2 fights · 2 paid · 2 placed", the numbers and the words each in their own colour code. */
    static String stats(Counts c, String number, String word) {
        if (c.fights == 0) return word + "No fights yet";
        // Each one you place is a Goop spent, so it is what people set their takings against.
        return number + grouped(c.fights) + word + (c.fights == 1 ? " fight · " : " fights · ")
                + number + grouped(c.paid) + word + " paid · " + number + grouped(c.placed) + word + " placed";
    }

    /** Parties take turns at the altar: whether the last goop was yours, or how many since. Null before your first. */
    static String turn(int since) {
        if (since < 0) return null;
        return since == 0 ? "Last goop was yours" : since + (since == 1 ? " goop" : " goops") + " since yours";
    }

    private static String grouped(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** Your own drop tiers first - a Corrupted Core above the Rift Goo every fight pays - then the most of. */
    static List<Map.Entry<String, Integer>> ordered(Counts c) {
        return c.drops.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> -Drops.listedTierOf(e.getKey()))
                        .thenComparing(e -> -e.getValue()))
                .toList();
    }

    private static int[] drawCard(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        Counts c = period.equals("All Time") ? total : session;
        if (sample) {
            c = new Counts();
            c.fights = 13;
            c.paid = 4;
            c.placed = 3;
            c.drops.put("Rift Goo", 52);
            c.drops.put("Gelatinous Membrane", 8);
            c.drops.put("Crypt Cache", 4);
        }
        return TrackerHud.draw(g, font, x, y, "Goop Tracker", state(), Theme.muted(),
                rows(c, sample ? 2 : fight.sinceMine(), 5), -1);
    }

    private static int say(boolean allTime) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        Counts c = allTime ? total : session;
        mc.player.sendSystemMessage(Component.literal("§5Goop §8(" + (allTime ? "all time" : "session") + ") §7— "
                + stats(c, "§f", "§7")));
        if (c.placed > 0) mc.player.sendSystemMessage(Component.literal(NpcPrices.spentLine(COST_ITEM, c.placed)));
        List<HudCard.Row> rows = rows(c, fight.sinceMine(), Integer.MAX_VALUE);
        for (HudCard.Row row : rows.subList(1, rows.size()))
            mc.player.sendSystemMessage(Component.literal("  §7" + row.label() + (row.value().isEmpty() ? "" : " §f" + row.value())));
        if (c.drops.isEmpty()) mc.player.sendSystemMessage(Component.literal("§8  No drops recorded yet."));
        return 1;
    }

    // ---------------------------------------------------------------- small things

    private static char code(int colour) {
        for (ChatFormatting value : ChatFormatting.values())
            if (value.isColor() && value.getColor() != null && value.getColor() == (colour & 0xFFFFFF)) return value.getChar();
        return 'f';
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight/goop-tracker.json");
    }

    private static void load() {
        if (!Files.isRegularFile(file())) return;
        try {
            Counts saved = new Gson().fromJson(Files.readString(file()), Counts.class);
            if (saved == null || saved.fights < 0 || saved.drops == null
                    || saved.drops.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null || e.getValue() < 0))
                throw new IllegalArgumentException("Invalid Goop totals");
            total = saved;
            saved.drops.keySet().forEach(NpcPrices::track);
        } catch (Exception e) {
            // Counting on into a file that could not be read would overwrite it with this session alone.
            historySafe = false;
            System.err.println("[Endsight] Goop history could not be read; preserving the file and counting this session only: " + e);
        }
    }

    private static void save() {
        if (!dirty || !historySafe) return;
        try {
            Path target = file(), temp = target.resolveSibling("goop-tracker.json.tmp");
            Files.createDirectories(target.getParent());
            Files.writeString(temp, new Gson().toJson(total), StandardCharsets.UTF_8);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            dirty = false;
            savedAt = System.currentTimeMillis();
        } catch (Exception e) {
            savedAt = System.currentTimeMillis();
            System.err.println("[Endsight] Could not save Goop totals: " + e);
        }
    }
}
