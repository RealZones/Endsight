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

    public enum Where { END, CRYPTS, VOID_SEPULTURE, HUB, DUNGEON, UNKNOWN }

    private static Where where = Where.UNKNOWN;
    private static long lastRead;
    private static String lastPlace = "";
    private static final java.util.regex.Pattern PLACE_PREFIX = java.util.regex.Pattern.compile("^[^\\p{L}]+");
    private static final net.minecraft.ChatFormatting[] COLOURS = java.util.Arrays.stream(net.minecraft.ChatFormatting.values())
            .filter(f -> f.isColor() && f.getColor() != null).toArray(net.minecraft.ChatFormatting[]::new);

    /** One synchronous read, shared by callers asking several questions about the same sidebar. */
    public record Sidebar(List<String> raw) {
        public String line(String prefix) {
            for (String text : raw) if (Zealots.strip(text).trim().startsWith(prefix)) return text;
            return null;
        }

        public boolean shows(Where place) {
            if (place == Where.UNKNOWN) return false;
            for (String text : raw) if (placeLine(Zealots.strip(text).trim()) == place) return true;
            return false;
        }
    }

    public static Sidebar readSidebar(Minecraft mc) { return new Sidebar(sidebarRaw(mc)); }

    private static Sidebar recent;
    private static Object recentLevel;
    private static long recentAt;

    /**
     * A read at most a tick old, for HUDs that decide every frame whether to show. The
     * Slayer and Zealot Tracker HUDs each read the whole scoreboard every frame in the End,
     * a few hundred times a second, for lines that change when a packet arrives. Anything
     * that acts on what it reads, like the quest reminder, still reads it fresh.
     */
    public static Sidebar recentSidebar(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (recent == null || recentLevel != mc.level || now - recentAt >= 50 || now < recentAt) {
            recent = readSidebar(mc);
            recentLevel = mc.level;
            recentAt = now;
        }
        return recent;
    }

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

    public static boolean dungeon() { return where == Where.DUNGEON; }

    public static Where placeOf(String line) { return placeLine(Zealots.strip(line)); }

    /** Shared live sidebar evidence for modes which must not trust a stale warp hint. */
    public static List<String> sidebarLines(Minecraft mc) { return sidebar(mc); }

    /** A place line in the current sidebar, not just the last proxy warp hint. */
    public static boolean sidebarShows(Minecraft mc, Where place) {
        if (mc.level == null || place == Where.UNKNOWN) return false;
        return readSidebar(mc).shows(place);
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
        String s = PLACE_PREFIX.matcher(line).replaceFirst("").trim().toLowerCase();
        if (s.equals("the catacombs") || s.startsWith("the catacombs ")
                || s.equals("catacombs") || s.startsWith("catacombs ")) return Where.DUNGEON;
        if (s.equals("crypts") || s.startsWith("crypts ")) return Where.CRYPTS;
        if (s.equals("void sepulture") || s.startsWith("void sepulture ")) return Where.VOID_SEPULTURE;
        if (s.equals("dragon's den") || s.equals("dragons den") || s.equals("the end")) return Where.END;
        if (s.equals("village") || s.equals("hub") || s.equals("spawn")) return Where.HUB;
        return Where.UNKNOWN;
    }

    /** A move line from the proxy: "warping to crypts…", "moving you to dragon's den #2...". */
    private static Where classify(String s) {
        if (s.contains("catacombs")) return Where.DUNGEON;
        if (s.contains("void sepulture")) return Where.VOID_SEPULTURE;
        if (s.contains("crypt")) return Where.CRYPTS;
        if (s.contains("dragon") || s.contains("the end")) return Where.END;
        if (s.contains("village") || s.contains("spawn") || s.contains("hub")) return Where.HUB;
        return Where.UNKNOWN;
    }

    /** Every line of whichever sidebar is showing, in the game's own order of preference. */
    private static List<String> sidebar(Minecraft mc) {
        List<String> out = new ArrayList<>();
        for (String raw : sidebarRaw(mc)) out.add(Zealots.strip(raw).trim());
        return out;
    }

    /**
     * A line as text with its colours written in as section codes, whichever way the
     * server sent them. Some servers put the codes in the text itself; others send a
     * style on each piece, and getString() drops styles - the pet's rarity is its
     * colour, and read that way it would silently vanish. Each coloured piece is given
     * its code back, so the reader sees the same thing either way.
     */
    public static String legacy(net.minecraft.network.chat.Component c) {
        StringBuilder sb = new StringBuilder();
        c.visit((style, text) -> {
            net.minecraft.network.chat.TextColor col = style.getColor();
            if (col != null && !text.contains("§")) {
                for (net.minecraft.ChatFormatting f : COLOURS) {
                    if (f.getColor() == col.getValue()) {
                        sb.append('§').append(f.getChar());
                        break;
                    }
                }
            }
            sb.append(text);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return sb.toString();
    }

    /** The sidebar line that starts with this, colour codes and all, or null. The codes say a pet's rarity. */
    public static String sidebarLine(Minecraft mc, String prefix) {
        if (mc.level == null) return null;
        return readSidebar(mc).line(prefix);
    }

    private static List<String> sidebarRaw(Minecraft mc) {
        List<String> out = new ArrayList<>();
        if (mc.level == null) return out;
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
                out.add(legacy(PlayerTeam.formatNameForTeam(team, e.ownerName())));
            }
        } catch (RuntimeException ignored) {
            // A sidebar mid-update is not worth a crash; next second will do.
        }
        return out;
    }
}
