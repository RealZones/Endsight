package com.endsight.slayers;

import com.endsight.hud.Alert;
import com.endsight.hud.Alerts;
import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.hud.Readout;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slayer quests, read from the server's chat: a miniboss alert and a kill timer.
 *
 * Both modules live here because they read the same lines. Splitting them into two
 * files would mean two parsers matching the same six patterns, and the day the server
 * reworded one of them only half the mod would notice.
 *
 * Patterns verified against real logs rather than guessed - three complete quests with
 * every stage present, and the minibosses that actually announced themselves were
 * Revenant Champion, Deformed Revenant and Revenant Sycophant. Kill time ran 10-27s,
 * which is why the timer shows seconds and not minutes. Checked again on a Voidgloom
 * night: the quest lines are the same words, only the target reads "Endermen" - and
 * dying to the boss prints SLAYER QUEST FAILED, which the first version did not know
 * about, so the boss clock ran on until the next quest started.
 */
public final class Slayer {

    private Slayer() {
    }

    // Matched on text with section codes already stripped.
    //
    // MINIBOSS is anchored to the server's warning sign, which the others are not. The
    // first version matched "has appeared!" anywhere on the reasoning that a symbol is
    // the likeliest thing to change in a reword - but player chat arrives through the
    // same event, so that made anyone typing the phrase set off an alert and a sound.
    // Here the symbol is the only thing separating an announcement from a sentence.
    private static final Pattern MINIBOSS = Pattern.compile("^⚠[ ]*(.+?) has appeared!");
    private static final Pattern TARGET = Pattern.compile("Slay ([\\d,]+) Combat XP worth of (\\w+)");
    private static final Pattern LEVEL = Pattern.compile("(\\w+) Slayer LVL (\\d+)");

    private static final String QUEST_START = "SLAYER QUEST STARTED";
    private static final String BOSS_SPAWN = "SLAYER BOSS SPAWNING";
    private static final String BOSS_SLAIN = "SLAYER BOSS SLAIN";
    private static final String QUEST_DONE = "SLAYER QUEST COMPLETE";
    private static final String QUEST_FAILED = "SLAYER QUEST FAILED";

    // ── module state ──────────────────────────────────────────────────────────

    private static boolean timerOn = true;
    private static boolean killTimeInChat = true;
    private static double hideAfterMin = 3;

    // ── tracked ───────────────────────────────────────────────────────────────
    private static long bossSpawnedAt;

    /** Whether a slayer boss is up right now - spawned and not yet slain. */
    public static boolean bossUp() {
        return bossUp;
    }
    private static boolean bossUp;
    private static long lastKillMs;
    /**
     * Every kill this session added up, for the average.
     *
     * The last kill on its own is the least useful number available - one bad spawn or
     * one lucky burst and it says something that is not true of your night. An average
     * over the session is what "how fast am I actually killing these" means.
     */
    private static long totalKillMs;
    private static int bossesKilled;
    /**
     * When this session's slaying began - 0 until the first slayer line arrives.
     *
     * Not the game launch. "Elapsed" has to mean time spent on slayers, or the rate is
     * quietly divided by however long you spent doing dragons first and reads low for
     * the rest of the night.
     */
    private static long sessionStart;

    /**
     * When a slayer line was last seen, for pausing and hiding.
     *
     * Elapsed has to stop while you are doing something else, or an afk break silently
     * halves your rate for the rest of the night - the number would still be arithmetic
     * but it would stop describing anything. Idle stretches are subtracted from the
     * clock instead, so elapsed means time actually spent on slayers.
     */
    private static long lastActivity;
    private static String questMob;
    private static String slayerLevel;


    public static Module killTimerModule() {
        return new Module("slayer.timer", "Slayer Tracker",
                "Kills, time spent and rate for the session.", "Trackers",
                () -> timerOn, v -> timerOn = v,
                List.of(
                        new Setting.Action("Move readout",
                                "Drag it, and every other readout, where you want.",
                                "Move", HudPlacementScreen::open),
                        new Setting.Slider("Hide when idle",
                                "Fade out after this long with no slayer activity. 0 keeps it up.",
                                0, 15, 1, () -> hideAfterMin, v -> hideAfterMin = v, "m"),
                        new Setting.Toggle("Kill time in chat",
                                "Print each boss's own time when it dies.",
                                () -> killTimeInChat, v -> killTimeInChat = v),
                        new Setting.Action("Reset session",
                                "Zero the kill count and the clock.",
                                "Reset", Slayer::resetSession)));
    }

    private static void resetSession() {
        bossesKilled = 0;
        lastKillMs = 0;
        totalKillMs = 0;
        sessionStart = 0;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(Slayer::watchAfk);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            onLine(plain(message));
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "slayer"),
                (g, delta) -> draw(g));
        HudLayout.register("slayer.timer", "Slayer Tracker", 0f, 0.009f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    // ── reading ───────────────────────────────────────────────────────────────

    private static void onLine(String line) {
        if (isPlayerChat(line)) return;
        if (isSlayerLine(line)) touch();
        if (line.contains(BOSS_SPAWN)) {
            bossUp = true;
            bossSpawnedAt = System.currentTimeMillis();
            if (Alerts.slayerBoss()) fire("SLAYER BOSS", "spawning", true);
            return;
        }
        if (line.contains(BOSS_SLAIN)) {
            if (bossUp) {
                lastKillMs = System.currentTimeMillis() - bossSpawnedAt;
                totalKillMs += lastKillMs;
                bossesKilled++;
                reportKill(lastKillMs);
            }
            bossUp = false;
            return;
        }
        if (line.contains(QUEST_START) || line.contains(QUEST_DONE) || line.contains(QUEST_FAILED)) {
            bossUp = false;
            return;
        }

        Matcher m = TARGET.matcher(line);
        if (m.find()) {
            questMob = m.group(2);
            return;
        }
        m = LEVEL.matcher(line);
        if (m.find()) {
            slayerLevel = m.group(1) + " " + m.group(2);
            return;
        }

        // Last, because "has appeared!" is the loosest pattern here and must not get a
        // chance to swallow a line one of the specific ones owns.
        if (!Alerts.miniboss()) return;
        m = MINIBOSS.matcher(line);
        if (m.find()) {
            String who = m.group(1).trim();
            // The server prefixes a warning sign; strip anything that is not part of
            // the name so the toast does not show a stray glyph.
            who = who.replaceAll("^[^A-Za-z]+", "").trim();
            if (who.isEmpty() || who.length() > 40) return;
            fire(who, "has appeared", false);
        }
    }


    /**
     * True when this line is a person talking, not the server announcing.
     *
     * Player chat arrives through the SAME event as server announcements - on this
     * server "[MVP+] leafonwild: fear" is a system message, not a chat message - so
     * without this anyone typing "the boss has appeared!" fires a miniboss alert, with
     * the sound. Found by checking the log format rather than by it happening in game,
     * which is the cheaper way to find it.
     *
     * Matches an optional channel ("Instance > "), an optional rank in brackets, then a
     * single-word username and a colon. Server lines survive it because their speaker is
     * never one bare word: "[BOSS] Unstable Dragon:" has a space in the name, and
     * announcements have no colon at all.
     */
    private static boolean isPlayerChat(String line) {
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

    /**
     * Why the alert lives dead centre.
     *
     * The first version was a toast above the hotbar, and alerts went past unnoticed -
     * which is what happens to anything drawn at the edge of vision during a fight. Minecraft's own title slot is where every player has been trained to
     * look since they started playing, so this borrows that position exactly rather
     * than inventing a new one.
     */
    /**
     * Raise the alert.
     *
     * Drawn by us rather than handed to Gui.setTitle, which was the first version and
     * was right about placement and wrong about everything adjustable: vanilla's title
     * is locked at 4x scale and full opacity, and neither of those can be changed.
     * So this keeps vanilla's geometry exactly - dead centre, title above the midline,
     * subtitle below - and puts the two knobs vanilla does not expose on a slider.
     *
     * Timings copied from vanilla too (2 ticks in, 6 out) so it still feels like the
     * game's own alert rather than something bolted on beside it.
     */
    /**
     * One line in your own chat with this boss's own time.
     *
     * Chat rather than the HUD because the two answer different questions and want
     * different lifetimes: the tracker shows the average, which is the shape of the
     * session, and this is the single number for the kill you just did. Chat already
     * keeps a scrollable history of individual events, so putting it there means you
     * can compare the last six without the HUD having to hold six rows.
     *
     * Client-side only - nothing is sent to the server.
     *
     * One decimal because revenant kills run 10-27s: whole seconds throw away the
     * difference between two runs that felt different. Past a minute - a Voidgloom
     * fight - the decimal is noise and it reads as minutes and seconds instead.
     */
    private static void reportKill(long ms) {
        if (!killTimeInChat) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        mc.player.sendSystemMessage(Component.literal("")
                .append(Component.literal("Boss killed in ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(ms < 60_000 ? String.format("%.1fs", ms / 1000.0) : secs(ms))
                        .withStyle(ChatFormatting.GREEN)));
    }

    private static void fire(String who, String sub, boolean major) {
        // A boss spawning outranks a miniboss showing up, so it is drawn heavier and in
        // a darker red - deeper reads as weightier beside the miniboss colour, where
        // another bright one would just look like the same event again.
        int color = major ? Draw.lerp(Theme.neg(), 0xFF1A0000, 0.42f) : Theme.neg();
        Alert.show(who, sub, color, major ? 1.25f : 1f, Alerts.seconds());

        Minecraft mc = Minecraft.getInstance();
        if (Alerts.sound() && mc.player != null) {
            mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1.6f);
        }
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        drawTimer(g, mc);
    }

    /**
     * The miniboss toast: centred, just above the hotbar, fading out.
     *
     * Centred rather than cornered because this is the one thing here that is meant to
     * interrupt you - a corner is where you put something to be glanced at later, which
     * is the opposite of "this is about to hit you".
     */
    private static void drawTimer(GuiGraphicsExtractor g, Minecraft mc) {
        if (!timerOn) return;
        // Gone, not frozen: a tracker showing a stale average while you fight a dragon
        // is worse than no tracker, because it looks live.
        if (hideAfterMin > 0 && (lastActivity == 0
                || System.currentTimeMillis() - lastActivity > idleMs())) return;

        HudLayout.draw("slayer.timer", g, mc.font, false);
    }

    /**
     * The tracker block at a given position, returning the size it used.
     *
     * Shared by the live HUD and the placement screen. A null target asks for the size
     * only, since the position cannot be worked out until the size is known.
     *
     * Sample values are plausible rather than zeroes so the block is its real width while
     * being dragged - placing a box that grows once real numbers arrive is how a HUD ends
     * up overlapping something.
     */
    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        long now = System.currentTimeMillis();

        String topLabel, topValue;
        boolean hot;
        if (sample) {
            topLabel = "Avg kill";
            topValue = "14s";
            hot = false;
        } else if (bossUp) {
            topLabel = "Boss";
            topValue = secs(now - bossSpawnedAt);
            hot = true;
        } else if (bossesKilled > 0) {
            topLabel = "Avg kill";
            topValue = secs(totalKillMs / bossesKilled);
            hot = false;
        } else {
            topLabel = "Boss";
            topValue = "waiting";
            hot = false;
        }

        int rate = perHour();
        String[][] rows = sample
                ? new String[][]{{topLabel, topValue}, {"Kills", "12"},
                                 {"Elapsed", "8m32s"}, {"Rate", "84/h"}}
                : new String[][]{{topLabel, topValue},
                                 {"Kills", String.valueOf(bossesKilled)},
                                 {"Elapsed", sessionStart == 0 ? "-" : secs(elapsedMs())},
                                 {"Rate", rate < 0 ? "-" : rate + "/h"}};

        String title = "SLAYER TRACKER";
        int w = font.width(title) + 20;
        for (String[] r : rows) w = Math.max(w, Readout.width(font, r[0], r[1]));
        int h = Readout.ROW_H + 3 + rows.length * (Readout.ROW_H + 2);

        if (g != null) {
            Readout.tick(g, x, y, w, Theme.accent());
            Draw.text(g, font, title, Readout.left(x), y, Theme.muted());

            int ry = y + Readout.ROW_H + 3;
            for (int i = 0; i < rows.length; i++) {
                Draw.text(g, font, rows[i][0], Readout.left(x), ry, Theme.dim());
                Draw.textRight(g, font, rows[i][1], Readout.right(x, w), ry,
                        i == 0 && hot ? Theme.accent() : Theme.text());
                ry += Readout.ROW_H + 2;
            }
        }
        return new int[]{w, h};
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Any line that proves you are still slaying. */
    private static boolean isSlayerLine(String line) {
        return line.contains(QUEST_START) || line.contains(QUEST_DONE) || line.contains(QUEST_FAILED)
                || line.contains(BOSS_SPAWN) || line.contains(BOSS_SLAIN)
                || TARGET.matcher(line).find() || MINIBOSS.matcher(line).find();
    }

    private static void touch() {
        long now = System.currentTimeMillis();
        if (sessionStart == 0) sessionStart = now;
        lastActivity = now;
        lastMoved = now;
    }

    /**
     * Standing still this long is a break, and comes off the clock.
     *
     * Not the "Hide when idle" slider, and not chat: slayer lines are minutes apart in
     * a normal quest, so "no line for thirty seconds" would pause the clock in the
     * middle of killing the mobs for the next boss. Whether you are MOVING is the
     * honest signal - and the same thirty seconds the zealot tracker uses.
     */
    private static final long AFK_MS = 30_000;
    private static long lastMoved;
    private static Vec3 lastPos;

    /** Every tick: note movement, and once a break passes thirty seconds take it off the clock. */
    private static void watchAfk(Minecraft mc) {
        if (mc.player == null || sessionStart == 0) return;
        Vec3 pos = mc.player.position();
        long now = System.currentTimeMillis();
        if (lastPos == null || pos.distanceToSqr(lastPos) > 0.01) {
            if (lastMoved != 0 && now - lastMoved > AFK_MS) sessionStart += now - lastMoved;
            lastMoved = now;
        }
        lastPos = pos;
    }

    private static long idleMs() {
        return (long) (Math.max(1, hideAfterMin) * 60_000);
    }

    /** Time on slayers, with the break you are in right now already stopped. */
    private static long elapsedMs() {
        if (sessionStart == 0) return 0;
        long now = System.currentTimeMillis();
        long still = lastMoved == 0 ? 0 : now - lastMoved;
        return (still > AFK_MS ? lastMoved : now) - sessionStart;
    }

    /** Kills per hour, or -1 while the sample is too short to mean anything. */
    private static int perHour() {
        long elapsed = elapsedMs();
        if (elapsed < 60_000) return -1;
        return (int) Math.round(bossesKilled / (elapsed / 3_600_000.0));
    }

    private static String secs(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 60 ? s + "s" : (s / 60) + "m" + String.format("%02d", s % 60) + "s";
    }

    private static String plain(Component c) {
        return c == null ? "" : c.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "").trim();
    }
}
