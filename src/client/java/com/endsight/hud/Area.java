package com.endsight.hud;

import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Where you are, so readouts about somewhere else stay off the screen.
 *
 * Read off the server's own sidebar, which carries a "◎ Dragon's Den" / "◎ Village"
 * line wherever you are - once a second, since it hardly changes and the sidebar is
 * a walk over team prefixes. The proxy's move lines ("Moving you to Crypts #2...")
 * are kept as a fallback for the moment between a switch and the new sidebar.
 *
 * The End readouts - dragon, protector, zealots - ask {@link #end()} before drawing:
 * a dragon timer in the village is clutter, and "not sure yet" counts as the End so
 * a readout is never hidden by ignorance.
 */
public final class Area {

    private Area() {
    }

    public enum Where { END, CRYPTS, HUB, UNKNOWN }

    private static Where where = Where.UNKNOWN;
    private static long lastRead;

    public static Where where() {
        return where;
    }

    /** The End, or nobody has said otherwise yet. */
    public static boolean end() {
        return where == Where.END || where == Where.UNKNOWN;
    }

    public static boolean crypts() {
        return where == Where.CRYPTS;
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String line = Zealots.strip(message.getString()).trim().toLowerCase();
            if (line.startsWith("moving you to") || line.startsWith("sending you to")
                    || line.startsWith("warping to") || line.startsWith("teleporting to")) {
                where = classify(line);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == null) {
                where = Where.UNKNOWN;
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastRead < 1_000) return;
            lastRead = now;
            String place = sidebarPlace(mc);
            if (place != null) where = classify(place.toLowerCase());
        });
    }

    private static Where classify(String s) {
        if (s.contains("crypt")) return Where.CRYPTS;
        if (s.contains("dragon") || s.contains("the end") || s.contains("end…") || s.endsWith(" end")) return Where.END;
        if (s.contains("village") || s.contains("spawn") || s.contains("hub")) return Where.HUB;
        return Where.UNKNOWN;
    }

    /** The sidebar line that names the place, without its marker; null if there is none. */
    private static String sidebarPlace(Minecraft mc) {
        try {
            Scoreboard sb = mc.level.getScoreboard();
            Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (obj == null) return null;
            for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
                PlayerTeam team = sb.getPlayersTeam(e.owner());
                String line = Zealots.strip(PlayerTeam.formatNameForTeam(team, e.ownerName()).getString()).trim();
                if (line.startsWith("◎")) return line.substring(1).trim();
            }
        } catch (RuntimeException ignored) {
            // A sidebar mid-update is not worth a crash; next second will do.
        }
        return null;
    }
}
