package com.endsight.dungeon;

import net.minecraft.world.level.material.MapColor;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Finds one room on the server's map, from a point we know is inside it.
 *
 * Only ever asked about the room you are standing in when Dragsim names it ("PUZZLE!",
 * "TRAP ROOM!"). The scrapped version scanned the whole map for purple up front and put a
 * "?" on everything it found; it labelled rooms nobody had identified and sat off-centre.
 */
final class DungeonRooms {
    enum Kind { PUZZLE, TRAP }

    /** A room's box in map pixels. */
    record Room(int x, int y, int width, int height) {
        double centerX() { return x + width / 2.0; }
        double centerY() { return y + height / 2.0; }
        boolean same(Room other) { return Math.abs(centerX() - other.centerX()) < 3 && Math.abs(centerY() - other.centerY()) < 3; }
    }

    /** How far from your marker the room's colour may start: you are announced at the door. */
    private static final int REACH = 6;
    /** Larger than any room Dragsim gives a puzzle or trap; past this the fill has leaked. */
    private static final int LIMIT = 40;

    private DungeonRooms() { }

    /**
     * The room of the given kind nearest the point, by its colour (purple for puzzles,
     * orange for traps). Null when none is in reach yet, so the caller tries again as you
     * walk further in; a door pixel or the next room's brown is never taken for it.
     */
    static Room find(byte[] colors, double px, double py, Kind kind) {
        if (colors == null || colors.length != 128 * 128) return null;
        int cx = (int) Math.floor(px), cy = (int) Math.floor(py), best = -1;
        double nearest = Double.MAX_VALUE;
        for (int y = Math.max(0, cy - REACH); y <= Math.min(127, cy + REACH); y++)
            for (int x = Math.max(0, cx - REACH); x <= Math.min(127, cx + REACH); x++) {
                if (!matches(colors[y * 128 + x], kind)) continue;
                double d = Math.pow(x + .5 - px, 2) + Math.pow(y + .5 - py, 2);
                if (d < nearest) { nearest = d; best = y * 128 + x; }
            }
        return best < 0 ? null : fill(colors, best);
    }

    /**
     * The bounding box of the same-coloured pixels joined to the start. The box, not the
     * pixel count, because the server draws its tick and question mark inside the room in
     * other colours; they leave holes but the room's edges are still all one colour.
     */
    static Room fill(byte[] colors, int start) {
        byte colour = colors[start];
        boolean[] seen = new boolean[colors.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int minX = start % 128, maxX = minX, minY = start / 128, maxY = minY;
        seen[start] = true; queue.add(start);
        while (!queue.isEmpty()) {
            int index = queue.removeFirst(), x = index % 128, y = index / 128;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            if (maxX - minX >= LIMIT || maxY - minY >= LIMIT) return null;
            if (x > 0) step(colors, seen, queue, index - 1, colour);
            if (x < 127) step(colors, seen, queue, index + 1, colour);
            if (y > 0) step(colors, seen, queue, index - 128, colour);
            if (y < 127) step(colors, seen, queue, index + 128, colour);
        }
        // A few stray pixels of the right colour (a door, an icon) are not a room.
        if (maxX - minX < 5 || maxY - minY < 5) return null;
        return new Room(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static void step(byte[] colors, boolean[] seen, ArrayDeque<Integer> queue, int index, byte colour) {
        if (!seen[index] && colors[index] == colour) { seen[index] = true; queue.add(index); }
    }

    static boolean matches(byte packed, Kind kind) {
        if ((packed & 255) < 4) return false; // transparent: the gaps between rooms
        int color = MapColor.getColorFromPackedId(packed & 255);
        int r = (color >>> 16) & 255, g = (color >>> 8) & 255, b = color & 255;
        return switch (kind) {
            case PUZZLE -> r > 55 && b > 70 && r > g * 1.25 && b > g * 1.25 && b > r * .48 && r > b * .48;
            case TRAP -> r > 120 && g > r * .3 && g < r * .75 && b < r * .4;
        };
    }

    /**
     * A name as the reference map writes it: one word on one line, more words split in two
     * where the longer line comes out shortest ("Three / Weirdos", "Tic Tac / Toe").
     */
    static List<String> lines(String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length < 2) return List.of(name.trim());
        String bestTop = null, bestBottom = null;
        for (int cut = 1; cut < words.length; cut++) {
            String top = String.join(" ", java.util.Arrays.copyOfRange(words, 0, cut));
            String bottom = String.join(" ", java.util.Arrays.copyOfRange(words, cut, words.length));
            if (bestTop == null || Math.max(top.length(), bottom.length()) < Math.max(bestTop.length(), bestBottom.length())) {
                bestTop = top; bestBottom = bottom;
            }
        }
        return List.of(bestTop, bestBottom);
    }

    /**
     * The facing a vanilla map gives a player looking along {@code yaw}, in sixteenths of
     * a turn. Lets us pick our own marker out of several unnamed ones: it is the one
     * facing the way we are.
     */
    static int rot(float yaw) {
        double wrapped = ((yaw % 360) + 540) % 360 - 180;
        double d = wrapped < 0 ? wrapped - 8 : wrapped + 8;
        return (int) (d * 16 / 360) & 15;
    }
}
