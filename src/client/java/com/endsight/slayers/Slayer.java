package com.endsight.slayers;

import com.endsight.hud.Alert;
import com.endsight.hud.Alerts;
import com.endsight.hud.HudLayout;
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
import net.minecraft.network.chat.ClickEvent;
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
    private static final String REVENANT = "Revenant", VOIDGLOOM = "Voidgloom", SLAYER = "Slayer";

    // ── module state ──────────────────────────────────────────────────────────

    private static boolean timerOn = true;
    private static boolean killTimeInChat = true;
    private static boolean questReminder = true;
    private static double hideAfterMin = 3;

    // ── tracked ───────────────────────────────────────────────────────────────
    private static long bossSpawnedAt;

    /** Whether a slayer boss is up right now - spawned and not yet slain. */
    public static boolean bossUp() {
        return bossUp;
    }

    /**
     * Whether a quest is on - started and neither complete nor failed - and the chat has
     * said something slayer in the last quarter hour. The time cap is for a quest that
     * ended some way the chat never showed us, so it cannot pin "in a quest" on forever.
     */
    public static boolean questActive() {
        return (questActive || bossUp) && System.currentTimeMillis() - cur().lastActivity < 15 * 60_000L;
    }
    private static boolean bossUp;
    private static boolean questActive;
    private static String reminderArea = "";
    private static long reminderEnteredAt;
    private static long lastReminderAt;
    private static boolean reminderQueued;
    private static final long REMINDER_DELAY_MS = 1_000;
    private static final long REMINDER_COOLDOWN_MS = 45_000;

    /**
     * One slayer's session: zombies in the Crypts and endermen in the End are two
     * different nights, and one set of numbers across both read as nonsense - a 4s
     * average from revenants dragged into a Voidgloom fight, kills per hour over a
     * clock that had run through the other grind. So each slayer keeps its own, and
     * the readout shows whichever quest you are on.
     *
     * The average, not the last kill: one bad spawn or one lucky burst says nothing
     * about your night. Elapsed is time spent on THIS slayer, starting at its first
     * line rather than at launch, with breaks standing still taken off, so the rate
     * describes something.
     */
    private static final class Run {
        long lastKillMs, totalKillMs;
        int bossesKilled;
        long sessionStart;
        long lastActivity;
        long lastMoved;
        Vec3 lastPos;
    }

    private static final java.util.Map<String, Run> runs = new java.util.LinkedHashMap<>();
    /** "Revenant", "Voidgloom" - from the quest line, or the area until one has been seen. */
    private static String slayer;

    private static Run cur() {
        String key = slayer != null ? slayer
                : com.endsight.hud.Area.crypts() ? REVENANT
                : com.endsight.hud.Area.voidSepulture() ? VOIDGLOOM : SLAYER;
        return runs.computeIfAbsent(key, k -> new Run());
    }
    private static String slayerLevel;


    public static Module killTimerModule() {
        return new Module("slayer.timer", "Slayer Tracker",
                "Kills, average kill time and rate.", "Trackers",
                () -> timerOn, v -> timerOn = v,
                List.of(
                        new Setting.Slider("Hide when idle",
                                "Fade out after this long idle. 0 never.",
                                0, 15, 1, () -> hideAfterMin, v -> hideAfterMin = v, "m"),
                        new Setting.Toggle("Kill time in chat",
                                "Print each boss's own time when it dies.",
                                () -> killTimeInChat, v -> killTimeInChat = v),
                        new Setting.Toggle("Quest reminder",
                                "When you enter a slayer area without a quest, print a clickable /slayer reminder.",
                                () -> questReminder, v -> questReminder = v),
                        new Setting.Action("Reset session",
                                "Zero the kill count and the clock.",
                                "Reset", Slayer::resetSession)));
    }

    private static void resetSession() {
        Run r = cur();
        r.bossesKilled = 0;
        r.lastKillMs = 0;
        r.totalKillMs = 0;
        r.sessionStart = 0;
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
            questActive = true;
            bossUp = true;
            bossSpawnedAt = System.currentTimeMillis();
            if (Alerts.slayerBoss()) fire("SLAYER BOSS", "spawning", true);
            return;
        }
        if (line.contains(BOSS_SLAIN)) {
            if (bossUp) {
                Run r = cur();
                r.lastKillMs = System.currentTimeMillis() - bossSpawnedAt;
                r.totalKillMs += r.lastKillMs;
                r.bossesKilled++;
                reportKill(r.lastKillMs);
            }
            bossUp = false;
            return;
        }
        if (line.contains(QUEST_START) || line.contains(QUEST_DONE) || line.contains(QUEST_FAILED)) {
            questActive = line.contains(QUEST_START);
            bossUp = false;
            if (line.contains(QUEST_FAILED) && reminderSlayer() != null) {
                reminderEnteredAt = System.currentTimeMillis();
                reminderQueued = true;
            }
            return;
        }

        Matcher m = TARGET.matcher(line);
        if (m.find()) {
            questActive = true;
            slayer = slayerName(m.group(2));
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
        Run r = cur();
        if (hideAfterMin > 0 && (r.lastActivity == 0
                || System.currentTimeMillis() - r.lastActivity > idleMs())) return;

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
        Run r = cur();

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
        } else if (r.bossesKilled > 0) {
            topLabel = "Avg kill";
            // One decimal under a minute: revenant kills run 3-5s, and "4s" hides the
            // half second between a good night and a great one.
            long avg = r.totalKillMs / r.bossesKilled;
            topValue = avg < 60_000 ? String.format("%.1fs", avg / 1000.0) : secs(avg);
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
                                 {"Kills", String.valueOf(r.bossesKilled)},
                                 {"Elapsed", r.sessionStart == 0 ? "-" : secs(elapsedMs(r))},
                                 {"Rate", rate < 0 ? "-" : rate + "/h"}};

        String title = sample ? "SLAYER TRACKER" : (slayer != null ? slayer : "Slayer").toUpperCase() + " SLAYER";
        int w = font.width(title) + 20;
        for (String[] row : rows) w = Math.max(w, Readout.width(font, row[0], row[1]));
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
        Run r = cur();
        if (r.sessionStart == 0) r.sessionStart = now;
        r.lastActivity = now;
        r.lastMoved = now;
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

    /** Every tick: note movement, and once a break passes thirty seconds take it off the clock. */
    private static void watchAfk(Minecraft mc) {
        Run r = cur();
        watchQuestReminder(mc);
        if (mc.player == null || r.sessionStart == 0) return;
        Vec3 pos = mc.player.position();
        long now = System.currentTimeMillis();
        if (r.lastPos == null || pos.distanceToSqr(r.lastPos) > 0.01) {
            if (r.lastMoved != 0 && now - r.lastMoved > AFK_MS) r.sessionStart += now - r.lastMoved;
            r.lastMoved = now;
        }
        r.lastPos = pos;
    }

    /**
     * The easy mistake in slayer areas is not forgetting how to start the quest, it is
     * forgetting that one has to be started every time. This is deliberately chat and
     * not a HUD readout: once you click it, the problem is gone.
     */
    private static void watchQuestReminder(Minecraft mc) {
        String area = reminderSlayer();
        long now = System.currentTimeMillis();
        if (!area.equals(reminderArea)) {
            reminderArea = area;
            reminderEnteredAt = now;
            reminderQueued = !area.isEmpty();
        }
        if (area.isEmpty() || !questReminder || questActive || bossUp || mc.player == null) return;
        if (!reminderQueued || now - reminderEnteredAt < REMINDER_DELAY_MS) return;
        if (now - lastReminderAt < REMINDER_COOLDOWN_MS) return;
        reminderQueued = false;
        lastReminderAt = now;
        mc.player.sendSystemMessage(Component.literal("")
                .append(Component.literal("[Endsight] ").withStyle(ChatFormatting.DARK_PURPLE))
                .append(Component.literal("No " + area + " quest detected. ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Open /slayer").withStyle(s -> s
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/slayer")))));
    }

    private static String reminderSlayer() {
        if (com.endsight.hud.Area.crypts()) return REVENANT;
        if (com.endsight.hud.Area.voidSepulture()) return VOIDGLOOM;
        return "";
    }

    private static String slayerName(String target) {
        String s = target.toLowerCase();
        if (s.contains("zombie")) return REVENANT;
        if (s.contains("endermen") || s.contains("enderman")) return VOIDGLOOM;
        return target.endsWith("s") ? target.substring(0, target.length() - 1) : target;
    }

    private static long idleMs() {
        return (long) (Math.max(1, hideAfterMin) * 60_000);
    }

    /** Time on slayers, with the break you are in right now already stopped. */
    private static long elapsedMs(Run r) {
        if (r.sessionStart == 0) return 0;
        long now = System.currentTimeMillis();
        long still = r.lastMoved == 0 ? 0 : now - r.lastMoved;
        return (still > AFK_MS ? r.lastMoved : now) - r.sessionStart;
    }

    /** Kills per hour, or -1 while the sample is too short to mean anything. */
    private static int perHour() {
        Run r = cur();
        long elapsed = elapsedMs(r);
        if (elapsed < 60_000) return -1;
        return (int) Math.round(r.bossesKilled / (elapsed / 3_600_000.0));
    }

    private static String secs(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 60 ? s + "s" : (s / 60) + "m" + String.format("%02d", s % 60) + "s";
    }

    private static String plain(Component c) {
        return c == null ? "" : c.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "").trim();
    }
}
