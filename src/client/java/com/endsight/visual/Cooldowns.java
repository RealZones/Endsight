package com.endsight.visual;

import com.endsight.hud.HudLayout;
import com.endsight.storage.Recipes;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
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
 * Item ability cooldowns, read from the server and shown as one compact list.
 *
 * The server says an ability fired in the action bar - "-100 Mana (Giant's Slam)",
 * "-150 Mana (Howl)". A server sets the action bar with its own packet, which never
 * passes the chat listener, so no message event sees it; the text is read straight
 * off the Gui every tick. Soulcry, the tuba and the drill say so in chat as well. The
 * length is the item's own "Cooldown: 5s" lore line, and "This ability is on cooldown
 * for 3.7s" - the early press - puts the line where the server says it is.
 *
 * <h2>Server ticks, not seconds</h2>
 * The server counts a cooldown down a tick at a time: its refusals come in twentieths
 * of a second - "4.75s", "0.95s", "2.55s" in the old logs - never 4.73. When it lags,
 * a cooldown takes longer than its lore says. Timing a Voidgloom's once-a-second
 * effects across an evening of fights put the server at 18.4 to 20 ticks a second,
 * and at 18.4 the drill's 108s runs 117s. Timed by the wall clock, the line said Ready
 * and the press still bounced "3s". So the timers here run on {@link #ticks}, which
 * follows the server's own count.
 */
public final class Cooldowns {

    private Cooldowns() {
    }

    private static boolean enabled = true;
    private static double backgroundOpacity = 72;

    private static final Pattern MANA = Pattern.compile("-[\\d,]+ Mana \\((.+?)\\)");
    private static final Pattern COOLDOWN = Pattern.compile("^Cooldown: ([\\d.]+)s");
    private static final Pattern WAIT = Pattern.compile("on cooldown for ([\\d.]+)s");
    /**
     * How long after an item leaves the hand a refusal can still be its. The line answers
     * a press a round trip back, and a lag spike stretches that well past the ping.
     */
    private static final long HELD_MS = 750;

    /** What an ability matches and where its cooldown is right now. */
    private static final class Timer {
        final String id, label, item, ability;
        final double fallback;
        boolean on = true;
        ItemStack icon;
        /** In server ticks: when the last use landed, and when the server takes the next. */
        long start, until;
        /** The cooldown the lore gave at the last use, in ticks. */
        long lore;
        /**
         * What the server really ran for that lore number, measured from one of its
         * refusals against a use seen starting. Used in the lore's place while the lore
         * still says the same thing, so a cut the lore leaves out - a Sky Mall buff on
         * the drill - is caught after one early press and not guessed at.
         */
        long learned, learnedFor;
        /** Wall clock: when this item was last in the main hand. */
        long heldAt;
        long carryCheckedAt;
        boolean carrying;

        Timer(String id, String label, String item, String ability, double fallback) {
            this.id = id;
            this.label = label;
            this.item = item;
            this.ability = ability;
            this.fallback = fallback;
        }

        boolean is(ItemStack s) {
            if (s.isEmpty()) return false;
            String n = s.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (this == KATANA) return n.contains("vorpal katana") || n.contains("atomsplit katana");
            // A Drill Motor is a drill's part, not a drill; it has no fuel and no cooldown,
            // and a stack of them in the bag kept the drill line up with no drill about.
            return n.contains(item) && !n.contains("drill motor");
        }
    }

    private static final Timer SWORD = new Timer("cooldown.sword", "Giant's Sword", "giant's sword", "slam", 5);
    private static final Timer TUBA = new Timer("cooldown.tuba", "Tuba", "tuba", "howl", 20);
    private static final Timer KATANA = new Timer("cooldown.katana", "Soulcry", "katana", "soulcry", 4);
    /** The drill's Void Infusion: "You used Void Infusion!" starts it, the lore says 108s. */
    private static final Timer DRILL = new Timer("cooldown.drill", "Drill", "drill", "void infusion", 108);
    private static final long DRILL_LINGER_MS = 15_000;
    private static long drillVisibleUntil;
    private static final List<Timer> ABILITIES = List.of(SWORD, TUBA, KATANA);
    private static final List<Timer> TIMERS = List.of(SWORD, TUBA, KATANA, DRILL);

    /** The Drill CD line's switch is on the Mining Session page with the fuel line's. */
    public static boolean drillOn() {
        return DRILL.on;
    }

    public static void setDrillOn(boolean on) {
        DRILL.on = on;
    }

    /**
     * The drill coming off cooldown, said loudly: a big green line mid-screen and a bell,
     * once per cooldown. The Drill CD line is easy to stop reading mid-mining. The bell is
     * not the drop alert's pling, so a ready drill never sounds like a Void Fragment. Its
     * switch sits on the Mining Session page with the Drill CD line's.
     */
    private static boolean readyAlert;
    private static boolean readySaid = true;

    public static boolean readyAlertOn() {
        return readyAlert;
    }

    public static void setReadyAlert(boolean on) {
        readyAlert = on;
    }

    /** The ability named in the action bar's mana part last tick, "" when there was none. */
    private static String lastUsed = "";

    // ── the clock ─────────────────────────────────────────────────────────────

    /**
     * Server ticks so far. The client counts its world's game time up once a tick and the
     * server's time packet sets it back to the real count once a second, so the steps of
     * that number follow the server, slowing when it lags. A world swap starts a new count
     * and the loading screen has none; those count one a tick, which is what the wall
     * clock gave before. It never runs backwards - a correction that lands behind holds
     * the clock until the server catches up, so no timer ticks back up from Ready.
     */
    private static long ticks, serverTicks;
    private static ClientLevel clockLevel;
    private static long clockLast;

    private static void countTick(Minecraft mc) {
        ClientLevel level = mc.level;
        long step = 1;
        if (level != null) {
            long time = level.getGameTime();
            // Ten seconds either way is a /time set or a hitch, not lag: count it as a tick.
            if (level == clockLevel && Math.abs(time - clockLast) <= 200) step = time - clockLast;
            clockLast = time;
        }
        clockLevel = level;
        serverTicks += step;
        ticks = Math.max(ticks, serverTicks);
    }

    public static Module module() {
        return new Module("visual.cooldowns", "Ability Cooldowns",
                "Ready times for carried abilities.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Section("Abilities"),
                        new Setting.Toggle("Giant's Sword", "Track Giant's Slam.", () -> SWORD.on, v -> SWORD.on = v),
                        new Setting.Toggle("Tuba", "Track Howl.", () -> TUBA.on, v -> TUBA.on = v),
                        new Setting.Toggle("Katanas", "Track Vorpal and Atomsplit Soulcry.", () -> KATANA.on, v -> KATANA.on = v),
                        new Setting.Section("Appearance"),
                        new Setting.Slider("Background opacity", "Dark backdrop behind ability cooldowns.",
                                0, 100, 1, () -> backgroundOpacity, v -> backgroundOpacity = v, "%")));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Before anything returns: cooldowns keep running through a warp's loading screen.
            countTick(mc);
            if (mc.player == null) {
                drillVisibleUntil = 0;
                return;
            }
            if (!enabled) return;
            long now = System.currentTimeMillis();
            ItemStack hand = mc.player.getMainHandItem();
            for (Timer t : TIMERS) if (t.is(hand)) t.heldAt = now;
            if (DRILL.is(hand)) drillVisibleUntil = now + DRILL_LINGER_MS;
            if (readyAlert && !readySaid && DRILL.until > 0 && ticks >= DRILL.until) {
                readySaid = true;
                com.endsight.hud.Alert.show("DRILL READY", "", 0x55FF55, 2.0f, 2.5);
                if (com.endsight.hud.Alerts.sound()) {
                    mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 1.4f);
                }
            }
            // "-100 Mana (Giant's Slam)" stays in the action bar through several of the
            // server's updates to it, and each update used to restart the timer - a use
            // counted from the last time the part showed, not from the press. A use is
            // now the part appearing. Nothing tracked comes round inside the time the part
            // stays up, and Soulcry, the quickest, has its own chat line besides.
            Component c = mc.gui.overlayMessageString;
            Matcher m = MANA.matcher(c == null ? "" : Zealots.strip(c.getString()));
            String used = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
            if (!used.isEmpty() && !used.equals(lastUsed))
                for (Timer t : TIMERS) if (used.contains(t.ability)) start(t);
            lastUsed = used;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            String line = Zealots.strip(message.getString()).trim();
            if (line.startsWith("HOWL!")) start(TUBA);
            // "☠ Soulcry! +250 ⫽ Ferocity against Endermen for 4s." on every use, where
            // the action bar's part can still be up from the last one. No colon: every
            // player's chat line has one, so nobody can start it by typing it.
            else if (line.contains("Soulcry! +") && line.indexOf(':') < 0) start(KATANA);
            else if (line.startsWith("You used ")) {
                // The drill's ability is whichever one is selected in HOTD - Sheer Force,
                // Void Infusion, Tunnel Vision... - so any "You used" while a drill is in
                // hand starts its timer; the length still comes off the drill's own lore.
                String used = line.toLowerCase(Locale.ROOT);
                Minecraft mc = Minecraft.getInstance();
                boolean drillInHand = mc.player != null && DRILL.is(mc.player.getMainHandItem());
                for (Timer t : TIMERS) if (t == DRILL ? drillInHand : used.contains(t.ability)) start(t);
            } else sync(line);
        });
        // The early-press line is one Ability Spam hides, so it arrives cancelled.
        ClientReceiveMessageEvents.GAME_CANCELED.register((message, overlay) -> {
            if (enabled && !overlay) sync(Zealots.strip(message.getString()).trim());
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "cooldowns"), (g, delta) -> draw(g));
        HudLayout.register("cooldown.abilities", "Ability Cooldowns", 0.5f, 0.72f, Cooldowns::drawAbilities);
        HudLayout.register(DRILL.id, "Cooldown: Drill", 0.60f, 0.72f, (g, font, x, y, sample) -> drawOne(g, font, x, y, sample, DRILL));
    }

    // ── reading ───────────────────────────────────────────────────────────────

    private static void start(Timer t) {
        // Soulcry's chat line and its action bar part report one use a tick or two apart.
        if (ticks - t.start < 10 && ticks < t.until) return;
        ItemStack s = carried(t);
        if (s != null) t.icon = s.copy();
        t.lore = Math.round((s == null ? t.fallback : cooldownOf(s, t.fallback)) * 20);
        t.start = ticks;
        t.until = ticks + (t.learnedFor == t.lore ? t.learned : t.lore);
        if (t == DRILL) readySaid = false;
    }

    /**
     * "on cooldown for 3.7s" puts that ability's line where the server says it is.
     *
     * The line answers a press made a round trip earlier, so it belongs to what was in
     * hand then, not to what is in hand when it lands. It used to go to the item in hand,
     * and a swap from the sword to the katana handed the sword's "18s" to Soulcry. The
     * gap was then kept as a standing correction that only ever grew, so every Soulcry
     * after it read 18s against a 4s cooldown - the screenshot that found this. Now a
     * line two recently held items could have said is dropped, a wait longer than an
     * ability's cooldown is never that ability's, and nothing is kept but a length
     * measured against a use seen starting.
     */
    private static void sync(String line) {
        // Someone typing "I'm on cooldown for 4s" in chat: a player's line has a colon.
        if (line.indexOf(':') >= 0) return;
        Matcher m = WAIT.matcher(line);
        if (!m.find()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long left = Math.round(Double.parseDouble(m.group(1)) * 20);
        long now = System.currentTimeMillis();
        Timer to = null;
        for (Timer t : TIMERS) {
            long length = t.lore > 0 ? t.lore : Math.round(t.fallback * 20);
            if (now - t.heldAt > HELD_MS || left > length * 3 / 2 + 20) continue;
            if (to != null) return;
            to = t;
        }
        if (to == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (to.is(held)) to.icon = held.copy();
        long end = ticks + left;
        // From the use seen starting to the end the server gives is the real cooldown -
        // but only if it is one cooldown. After a use we missed, start to end spans two
        // and the gap between, which lands outside half to one and a half of the lore.
        long ran = end - to.start;
        if (to.start > 0 && to.lore > 0 && ran >= to.lore / 2 && ran <= to.lore * 3 / 2) {
            to.learned = ran;
            to.learnedFor = to.lore;
        }
        to.until = end;
        // Refused after the alert already went: it was early, so say it again when it is not.
        if (to == DRILL) readySaid = false;
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
        boolean abilitiesVisible = false;
        for (Timer t : ABILITIES) if (t.on && (ticks < t.until || carrying(mc, t))) abilitiesVisible = true;
        if (abilitiesVisible) HudLayout.draw("cooldown.abilities", g, mc.font, false);
        if (DRILL.on && System.currentTimeMillis() < drillVisibleUntil)
            HudLayout.draw(DRILL.id, g, mc.font, false);
    }

    /** Looked up four times a second, not per frame: it scans the inventory for each visible line. */
    private static boolean carrying(Minecraft mc, Timer t) {
        long now = System.currentTimeMillis();
        if (now - t.carryCheckedAt < 250) return t.carrying;
        t.carryCheckedAt = now;
        ItemStack current = carried(t);
        t.carrying = current != null;
        if (current != null) t.icon = current.copy();
        return t.carrying;
    }

    private static int[] drawAbilities(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        Minecraft mc = Minecraft.getInstance();
        int rows = 0;
        for (Timer t : ABILITIES)
            if (sample || (t.on && (ticks < t.until || (mc.player != null && carrying(mc, t))))) rows++;
        int w = 154, h = Math.max(18, rows * 19 - 1);
        if (g == null) return new int[]{w, h};

        int alpha = (int) Math.round(backgroundOpacity * 255 / 100);
        if (alpha > 0) Draw.roundedRect(g, x, y, w, h, 3, alpha << 24);
        int row = 0;
        for (Timer t : ABILITIES) {
            if (!sample && (!t.on || (ticks >= t.until && !carrying(mc, t)))) continue;
            ItemStack icon = t.icon;
            if (icon == null) icon = Recipes.icon(t.label);
            if (icon == null) icon = new ItemStack(t == TUBA ? Items.GOAT_HORN : Items.DIAMOND_SWORD);
            int top = y + row * 19;
            g.fakeItem(icon, x + 3, top + 1);
            g.text(font, t.label, x + 23, top + 5, 0xFFE8E8E8, true);
            double seconds = sample ? t.fallback * 0.55 : Math.max(0, t.until - ticks) / 20.0;
            String value = seconds <= 0 ? "Ready" : seconds < 10
                    ? String.format(Locale.ROOT, "%.1fs", seconds) : Math.round(seconds) + "s";
            g.text(font, value, x + w - 5 - font.width(value), top + 5,
                    seconds <= 0 ? 0xFF66E8A1 : 0xFFFFFFFF, true);
            row++;
        }
        return new int[]{w, h};
    }

    private static int[] drawOne(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample, Timer t) {
        if (t.icon == null) t.icon = carried(t);
        ItemStack icon = t.icon;
        if (icon == null) icon = Recipes.icon(t.label);
        if (icon == null) icon = new ItemStack(t == TUBA ? Items.GOAT_HORN : Items.DIAMOND_SWORD);

        // Skytils' "Pickaxe CD: Ready", for the drill: unchanged from the mining readout.
        double seconds = sample ? 0 : Math.max(0, t.until - ticks) / 20.0;
        String value = seconds <= 0 ? "Ready" : seconds >= 60 ? (int) (seconds / 60) + "m " + (int) (seconds % 60) + "s" : Math.round(seconds) + "s";
        String label = "Drill CD: ";
        int tw = 18 + font.width(label) + font.width(value), th = 16;
        if (g == null) return new int[]{tw, th};
        if (icon != null) g.fakeItem(icon, x, y);
        Draw.text(g, font, label, x + 18, y + 4, 0xFF55FFFF);
        Draw.text(g, font, value, x + 18 + font.width(label), y + 4, seconds <= 0 ? 0xFF55FF55 : 0xFFFFFF55);
        return new int[]{tw, th};
    }
}
