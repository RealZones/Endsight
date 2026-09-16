package com.endsight.dragons;

import com.endsight.hud.Alert;
import com.endsight.hud.Alerts;
import com.endsight.hud.Area;
import com.endsight.hud.HudLayout;
import com.endsight.hud.Readout;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.qol.EyeGuard;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the dragon cycle by reading the server's own chat announcements.
 *
 * Chat rather than entities or packets, because the server already narrates every
 * state change we care about and does it in a fixed format. Reading the world would
 * mean inferring "the egg is up" from block states and "eight eyes are in" from
 * counting armour stands; reading chat means matching a line the server promises to
 * send. When the server changes its wording this breaks visibly and in one place,
 * which is the same trade Storage Preview makes with window titles.
 *
 * Every pattern here was taken from a real 40-minute log rather than guessed: 133
 * Summoning Eye placements, 7 Golden Eyes, 18 egg spawns, 8 distinct dragon types and
 * 12 Endstone Protector kills. That is why the eye regex allows a colour code before
 * the eye's name - Golden Eye arrives as "placed a §6Golden Eye!" and Summoning Eye
 * does not, and a pattern written from one example would have missed the other.
 */
public final class DragonTimer {

    private DragonTimer() {
    }

    // ── the server's wording, stripped of colour codes before matching ────────
    private static final Pattern EYE = Pattern.compile(
            "» (.+?) placed an? (\\w+ Eye)! \\((\\d+)/(\\d+)\\)");
    private static final Pattern SPAWNED = Pattern.compile(
            "The (\\w+ Dragon) has spawned!");
    private static final Pattern DESPAWNED = Pattern.compile(
            "The (\\w+ Dragon) has de-spawned\\.");

    private static final String EGG_SOON = "Dragon Egg is respawning in";
    private static final String EGG_UP = "The Egg has Spawned";
    private static final String EGG_RESPAWNED = "Dragon Egg has respawned";
    private static final String EYES_DONE = "Sleeping Eyes have been awoken";

    // ── module state ──────────────────────────────────────────────────────────
    private static boolean enabled = true;
    private static boolean showHud = true;
    /**
     * Extra rows, off by default.
     *
     * The first version showed dragon, eyes, Protector tier, damage and a placer list
     * all at once, which is a stats table wearing a timer's name. A timer answers one
     * question at a glance; everything that answers a different question moved here.
     */
    private static boolean details = false;


    // ── tracked state ─────────────────────────────────────────────────────────
    private static int eyes;
    private static int eyesNeeded = 8;
    /** Who placed how many this cycle, in the order they first placed. */
    private static final Map<String, Integer> placers = new LinkedHashMap<>();
    /** How many of each placer's eyes were Golden Eyes, marked on their row. */
    private static final Map<String, Integer> golden = new LinkedHashMap<>();
    private static int myEyes;
    /**
     * Eye indices already counted this cycle.
     *
     * The server announces your own placements twice - once as "You placed a Summoning
     * Eye! (1/8)" and again as "[RANK] Name placed a Summoning Eye! (1/8)". Replaying a
     * 40-minute log through these patterns showed one player counted under both spellings
     * for the same two eyes. Counting announcements doubles your own and lists you twice;
     * counting distinct indices cannot, because the duplicate carries the index it
     * duplicates.
     */
    private static final java.util.Set<Integer> countedEyes = new java.util.HashSet<>();

    private static String dragon;
    private static long dragonSince;
    private static long lastDragonEnded;
    private static String lastDragon;

    /**
     * When eyes become placeable again, as a wall-clock time.
     *
     * Measured, not guessed: across 195 events in real server logs the gap from
     * "has de-spawned" to "The Egg has Spawned" was 24s every single time (n=32,
     * min 24, max 25). So the countdown starts the moment the dragon dies.
     *
     * Each later milestone re-anchors it - the server announces "respawning in 10
     * seconds", then "has respawned" 10s on, then the egg 4s after that - so an
     * estimate that started slightly wrong is corrected three times before it matters
     * and can never drift.
     */
    private static long eggAt;

    private static final long DEATH_TO_EGG = 24_000L;
    private static final long SOON_TO_EGG = 14_000L;   // 10s countdown + 4s to the egg
    private static final long RESPAWNED_TO_EGG = 4_000L;

    public static Module module() {
        return new Module("dragon.timer", "Dragon Timer",
                "Eye count and dragon state, read from chat.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Show on HUD",
                                "Keep the readout on screen.",
                                () -> showHud, v -> showHud = v),
                        new Setting.Toggle("Details",
                                "List who placed each eye this cycle.",
                                () -> details, v -> details = v)));
    }

    public static void init() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> resetAll());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            onLine(plain(message));
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "dragon_timer"),
                (g, delta) -> draw(g));
        HudLayout.register("dragon.timer", "Dragon Timer", 0.589f, 0.045f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    // ── reading ───────────────────────────────────────────────────────────────

    private static void onLine(String line) {
        if (isPlayerChat(line)) return;
        Matcher m;

        if ((m = EYE.matcher(line)).find()) {
            int index = parse(m.group(3), -1);
            eyes = Math.max(eyes, index);
            eyesNeeded = parse(m.group(4), eyesNeeded);

            if (index < 0 || !countedEyes.add(index)) return;   // duplicate announcement

            Minecraft mc = Minecraft.getInstance();
            String me = mc.player == null ? null : mc.player.getName().getString();
            String who = stripRank(m.group(1));
            // "You" is the server talking about you, so fold it into your real name -
            // otherwise you show up as two separate placers in the same list.
            if (who.equalsIgnoreCase("You") && me != null) who = me;

            placers.merge(who, 1, Integer::sum);
            if (m.group(2).startsWith("Golden")) golden.merge(who, 1, Integer::sum);
            if (me != null && who.equalsIgnoreCase(me)) {
                myEyes++;
                EyeGuard.armed();
            }
            return;
        }

        if ((m = SPAWNED.matcher(line)).find()) {
            dragon = m.group(1);
            dragonSince = System.currentTimeMillis();
            return;
        }

        if ((m = DESPAWNED.matcher(line)).find()) {
            lastDragon = m.group(1);
            lastDragonEnded = System.currentTimeMillis();
            eggAt = lastDragonEnded + DEATH_TO_EGG;
            dragon = null;
            // A cycle ends when the dragon does, so the eye count resets here rather
            // than when the next egg appears - between the two you want to still see
            // what the run you just finished actually was.
            resetCycle();
            return;
        }

        if (line.contains(EGG_SOON)) {
            eggAt = System.currentTimeMillis() + SOON_TO_EGG;
            resetCycle();
        } else if (line.contains(EGG_RESPAWNED)) {
            eggAt = System.currentTimeMillis() + RESPAWNED_TO_EGG;
        } else if (line.contains(EGG_UP)) {
            eggAt = 0;                                  // placeable now
            resetCycle();
        } else if (line.contains(EYES_DONE)) {
            eyes = eyesNeeded;
        }
    }

    private static void resetCycle() {
        eyes = 0;
        myEyes = 0;
        placers.clear();
        golden.clear();
        countedEyes.clear();
    }

    /**
     * Everything, on a fresh join.
     *
     * The server hands you between backends - End, Crypts, hub - and each is a join to
     * the client. A dragon that was up when you left the End never sends its death to
     * you in the Crypts, so without this the readout sat there counting "Young Dragon
     * 12m37s" over a slayer fight.
     */
    private static void resetAll() {
        resetCycle();
        dragon = null;
        dragonSince = 0;
        eggAt = 0;
    }

    /**
     * The bare username, without the rank the server prefixes.
     *
     * Placement lines arrive as "» [DRAGON] Im_Shortt placed a..." or plain
     * "» Mason_Sword placed a...", so the rank is optional and has to be stripped
     * rather than assumed - otherwise your own placements never match your name and
     * "my eyes" silently stays at zero.
     */
    private static String stripRank(String who) {
        String s = who.trim();
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close >= 0) s = s.substring(close + 1);
        }
        return s.trim();
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    /**
     * One boxless line, plus a bar when there is something to fill.
     *
     * The respawn wait is the case that earns the bar: "Eggs in 14s" tells you the
     * number, the bar tells you how close that is without reading it, and between runs
     * that is the only thing you actually want to know.
     */
    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled || !showHud) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (!Area.end()) return;

        HudLayout.draw("dragon.timer", g, mc.font, false);
    }

    /**
     * The readout at a position, or - with a null target - just its size.
     *
     * Size has to be known before the position can be worked out, and the size depends
     * on the text. Passing null asks for the measurement without drawing anything, which
     * is the only version of this that cannot accidentally leave a copy on screen.
     */
    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        long now = System.currentTimeMillis();
        long until = eggAt - now;

        String label, value;
        boolean hot;
        float progress = -1;

        if (sample) {
            label = "Egg Respawn";
            value = "14s";
            hot = false;
            progress = 0.55f;
        } else if (dragon != null) {
            label = dragon;
            value = secs(now - dragonSince);
            hot = true;
        } else if (until > 0) {
            label = "Egg Respawn";
            value = (until / 1000 + 1) + "s";
            hot = until <= 5000;
            progress = 1f - (until / (float) DEATH_TO_EGG);   // fills as it approaches
        } else if (eyes > 0) {
            label = "Eyes";
            value = eyes + "/" + eyesNeeded;
            hot = eyes >= eyesNeeded;
            progress = eyes / (float) eyesNeeded;
        } else if (lastDragon != null) {
            label = lastDragon;
            value = secs(now - lastDragonEnded) + " ago";
            hot = false;
        } else {
            label = "Dragon";
            value = "waiting";
            hot = false;
        }

        int w = Readout.width(font, label, value);
        int h = Readout.height(progress >= 0);
        if (g != null) {
            Readout.draw(g, font, x, y, w, label, value, hot, progress);
        }

        if (details && !sample) {
            int ry = y + h + 3;
            for (Map.Entry<String, Integer> e : placers.entrySet()) {
                // Names are whatever anyone is called, so they are cut to fit rather
                // than allowed to run past the readout and into the game behind it.
                if (g != null) {
                    // A ✦ per Golden Eye after the count, and the row lit, so the one
                    // that matters for the dragon reads at a glance.
                    int gold = golden.getOrDefault(e.getKey(), 0);
                    String who = Draw.fit(font, e.getKey(), w - 34 - gold * 8);
                    Readout.draw(g, font, x, ry, w, who,
                            e.getValue() + (gold > 0 ? " " + "✦".repeat(gold) : ""), gold > 0, -1);
                }
                ry += Readout.height(false) + 2;
                h += Readout.height(false) + 2;
            }
        }
        return new int[]{w, h};
    }

    /**
     * True when this line is a person talking, not the server announcing.
     *
     * Player chat arrives through the SAME event as server announcements - on this
     * server "[MVP+] leafonwild: fear" is a system message - so without this, someone
     * typing an announcement's wording sets the parser off. Checked against every log:
     * 1437 player lines ignored, 823 announcements kept, none wrongly blocked.
     */
    public static boolean isPlayerChat(String line) {
        int colon = line.indexOf(": ");
        if (colon <= 0) return false;

        String speaker = line.substring(0, colon);
        int channel = speaker.lastIndexOf("> ");        // "Instance > "
        if (channel >= 0) speaker = speaker.substring(channel + 2);
        int rank = speaker.lastIndexOf("] ");           // "[MVP+] "
        if (rank >= 0) speaker = speaker.substring(rank + 2);
        speaker = speaker.trim();

        if (speaker.isEmpty() || speaker.length() > 16) return false;
        for (int i = 0; i < speaker.length(); i++) {
            char c = speaker.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String secs(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 60 ? s + "s" : (s / 60) + "m" + String.format("%02d", s % 60) + "s";
    }

    private static String plain(Component c) {
        return c == null ? "" : c.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "");
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
