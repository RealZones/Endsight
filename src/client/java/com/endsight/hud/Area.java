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

import java.util.ArrayList;
import java.util.List;

/**
 * Where you are, so readouts about somewhere else stay off the screen.
 *
 * Read off the server's own sidebar, which carries a line naming the place - "Dragon's
 * Den", "Crypts", "Village" - wherever you are. Every sidebar slot is walked, not just
 * the plain one: a server can hang the sidebar on a team-colour slot, and the first
 * version read only the plain slot, found nothing, and let the zealot tracker sit in
 * the Crypts. The place is matched by NAME, as a whole line, because the marker glyph
 * in front of it is the server's to change and "Dragon" alone also appears in the pet
 * line. The proxy's move lines ("Warping to crypts…") fill the gap between a switch and
 * the new sidebar.
 *
 * The End readouts - dragon, protector, zealots - ask {@link #end()} before drawing:
 * a dragon timer in the village is clutter, and "not sure yet" counts as the End so a
 * readout is never hidden by ignorance.
 */
public final class Area {

    private Area() {
    }

    public enum Where { END, CRYPTS, VOID_SEPULTURE, HUB, UNKNOWN }

    private static Where where = Where.UNKNOWN;
    private static long lastRead;
    private static String lastPlace = "";

    public static Where where() {
        return where;
    }

    /** The End, or nobody has said otherwise yet. */
    public static boolean end() {
        return where == Where.END || where == Where.VOID_SEPULTURE || where == Where.UNKNOWN;
    }

    public static boolean crypts() {
        return where == Where.CRYPTS;
    }

    public static boolean voidSepulture() {
        return where == Where.VOID_SEPULTURE;
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String line = Zealots.strip(message.getString()).trim().toLowerCase();
            if (line.startsWith("moving you to") || line.startsWith("sending you to")
                    || line.startsWith("warping to") || line.startsWith("teleporting to")) {
                Where w = classify(line);
                if (w != Where.UNKNOWN) where = w;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == null) {
                where = Where.UNKNOWN;
                lastPlace = "";
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastRead < 1_000) return;
            lastRead = now;
            for (String line : sidebar(mc)) {
                Where w = placeLine(line);
                if (w != Where.UNKNOWN) {
                    lastPlace = line;
                    where = w;
                    return;
                }
            }
        });
    }

    /** A sidebar line that IS a place name, once the marker and colour in front of it are gone. */
    private static Where placeLine(String line) {
        String s = line.replaceAll("^[^\\p{L}]+", "").trim().toLowerCase();
        if (s.equals("crypts") || s.startsWith("crypts ")) return Where.CRYPTS;
        if (s.equals("void sepulture") || s.startsWith("void sepulture ")) return Where.VOID_SEPULTURE;
        if (s.equals("dragon's den") || s.equals("dragons den") || s.equals("the end")) return Where.END;
        if (s.equals("village") || s.equals("hub") || s.equals("spawn")) return Where.HUB;
        return Where.UNKNOWN;
    }

    /** A move line from the proxy: "warping to crypts…", "moving you to dragon's den #2...". */
    private static Where classify(String s) {
        if (s.contains("crypt")) return Where.CRYPTS;
        if (s.contains("dragon") || s.contains("the end")) return Where.END;
        if (s.contains("village") || s.contains("spawn") || s.contains("hub")) return Where.HUB;
        return Where.UNKNOWN;
    }

    /** Every line of whichever sidebar is showing, in the game's own order of preference. */
    private static List<String> sidebar(Minecraft mc) {
        List<String> out = new ArrayList<>();
        try {
            Scoreboard sb = mc.level.getScoreboard();
            Objective obj = null;
            if (mc.player != null) {
                PlayerTeam mine = sb.getPlayersTeam(mc.player.getScoreboardName());
                if (mine != null && mine.getColor().isColor()) {
                    obj = sb.getDisplayObjective(DisplaySlot.teamColorToSlot(mine.getColor()));
                }
            }
            if (obj == null) obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (obj == null) {
                for (DisplaySlot slot : DisplaySlot.values()) {
                    if (slot.name().startsWith("TEAM_") && (obj = sb.getDisplayObjective(slot)) != null) break;
                }
            }
            if (obj == null) return out;
            for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
                PlayerTeam team = sb.getPlayersTeam(e.owner());
                out.add(Zealots.strip(PlayerTeam.formatNameForTeam(team, e.ownerName()).getString()).trim());
            }
        } catch (RuntimeException ignored) {
            // A sidebar mid-update is not worth a crash; next second will do.
        }
        return out;
    }
}
