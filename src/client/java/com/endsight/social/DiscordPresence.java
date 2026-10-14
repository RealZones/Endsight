package com.endsight.social;

import com.endsight.hud.Area;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opt-in Rich Presence through the local Discord desktop client. No bot token is used. */
public final class DiscordPresence {
    private DiscordPresence() { }

    /** The public Application ID of the existing Endsight Discord app. */
    private static final String APPLICATION_ID = "1551827988079972432";
    private static final String SERVER_INVITE = "https://discord.gg/jSQDjqfZfV";
    private static final String LOGO = "https://cdn.discordapp.com/app-icons/1551827988079972432/26614fdeb5c28c18c18af1b81585c572.png?size=512";
    private static final Pattern PURSE = Pattern.compile("(?i)\\bPurse:\\s*([\\d,.]+\\s*[kmb]?)");
    private static final long SAMPLE_MS = 5_000, UPDATE_MS = 15_000, REFRESH_MS = 60_000;

    private static volatile boolean enabled;
    private static volatile boolean showIgn = true, showArea = true, showPurse = true;
    private static volatile Snapshot desired;
    private static long lastSample, sessionStarted;

    private record Snapshot(String details, String state, long started) { }

    public static Module module() {
        return new Module("social.discord", "Discord Presence",
                "Show Dragsim, your player name, area and purse on Discord.", "Social",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("Show IGN", "Share your Minecraft username on your Discord activity.",
                                () -> showIgn, v -> showIgn = v),
                        new Setting.Toggle("Show area", "Share your current Dragsim area.",
                                () -> showArea, v -> showArea = v),
                        new Setting.Toggle("Show purse", "Share your purse with people who can see your Discord activity.",
                                () -> showPurse, v -> showPurse = v)));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(DiscordPresence::tick);
        Thread.ofVirtual().name("endsight-discord-presence").start(DiscordPresence::run);
    }

    private static void tick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) {
            desired = null;
            sessionStarted = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (desired != null && now - lastSample < SAMPLE_MS) return;
        lastSample = now;
        if (sessionStarted == 0) sessionStarted = Instant.now().getEpochSecond();
        String place = showArea ? area(Area.where()) : null;
        String purse = showPurse ? purse(Area.sidebarLine(mc, "Purse:")) : null;
        desired = new Snapshot(details(showIgn ? mc.player.getName().getString() : null), context(place, purse), sessionStarted);
    }

    static String details(String ign) {
        return ign == null || ign.isBlank() ? "Playing Dragsim" : "Playing as " + ign;
    }

    static String context(String place, String purse) {
        if (place != null && purse != null) return place + " • Purse: " + purse;
        if (place != null) return "In " + place;
        if (purse != null) return "Purse: " + purse;
        return "In game";
    }

    static String purse(String sidebarLine) {
        if (sidebarLine == null) return null;
        Matcher match = PURSE.matcher(Zealots.strip(sidebarLine));
        return match.find() ? match.group(1).trim() : null;
    }

    private static String area(Area.Where where) {
        return switch (where) {
            case END -> "Dragon's Den";
            case CRYPTS -> "Crypts";
            case VOID_SEPULTURE -> "Void Sepulture";
            case HUB -> "Hub";
            case DUNGEON -> "The Catacombs";
            case UNKNOWN -> null;
        };
    }

    private static void run() {
        DiscordRpc rpc = null;
        Snapshot sent = null;
        long lastSent = 0;
        while (true) {
            Snapshot next = desired;
            if (!enabled || next == null) {
                if (rpc != null) {
                    try { if (rpc.isOpen()) rpc.activity(null, null, 0, null, null); }
                    catch (java.io.IOException ignored) { }
                    rpc.close();
                    rpc = null;
                }
                sent = null;
                pause(2_000);
                continue;
            }
            try {
                if (rpc == null || !rpc.isOpen()) {
                    if (rpc != null) rpc.close();
                    rpc = DiscordRpc.connect(APPLICATION_ID);
                    sent = null;
                    lastSent = 0;
                }
                long now = System.currentTimeMillis();
                // A quiet game still needs a periodic write so a restarted Discord
                // client is detected and the status returns without a zone change.
                if (now - lastSent >= UPDATE_MS && (!next.equals(sent) || now - lastSent >= REFRESH_MS)) {
                    rpc.activity(next.details(), next.state(), next.started(), LOGO, SERVER_INVITE);
                    sent = next;
                    lastSent = now;
                }
                pause(2_000);
            } catch (java.io.IOException e) {
                if (rpc != null) rpc.close();
                rpc = null;
                // Discord may be closed; leave Minecraft alone and reconnect later.
                pause(10_000);
            }
        }
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { }
    }
}
