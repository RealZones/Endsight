package com.endsight.dungeon;

import java.util.Set;

/**
 * Room Glow's grid against a real run, and its room shapes against made-up seams.
 *
 * The doors are the seven that opened in the 2026-11-09 Floor I recording: each is a 3x4x3
 * hole, centred on one gap column and one cell centre line. If the grid were off by even a
 * block, these would miss.
 */
public final class RoomGlowChecks {
    private static int checks;

    public static void main(String[] args) {
        int[][] doors = {{-89, -137}, {-105, -89}, {-105, -185}, {-89, -169}, {-169, -153}, {-169, -57}, {-121, -105}};
        for (int[] d : doors) {
            boolean xGap = RoomGlow.gap(d[0]), zGap = RoomGlow.gap(d[1]);
            yes(xGap != zGap, "door " + d[0] + "," + d[1] + " is in exactly one gap");
            int centre = xGap ? d[1] : d[0];
            yes(Math.floorMod(centre - RoomGlow.ORIGIN, RoomGlow.CELL) == 15, "door " + d[0] + "," + d[1] + " is on a centre line");
        }

        // Every seam apart: a room is its own cell.
        RoomGlow.Seams apart = (i, j, alongX) -> false;
        long one = RoomGlow.around(-185, -185, apart);
        yes(one == bit(0, 0), "a lone cell is its own room");
        // Block -170 is the room's own wall, -169 the gap, -168 the next room's wall.
        yes(RoomGlow.inside(one, -169.5, -185) && !RoomGlow.inside(one, -167.5, -185), "the room ends at the gap");

        // A 1x2 room across x, and an L of three cells.
        Set<String> long2 = Set.of("2,3,x");
        long wide = RoomGlow.around(-185 + 64, -185 + 96, seams(long2));
        yes(wide == (bit(2, 3) | bit(3, 3)), "a 1x2 room is both cells");
        Set<String> ell = Set.of("1,1,x", "2,1,z");
        long l = RoomGlow.around(-185 + 32, -185 + 32, seams(ell));
        yes(l == (bit(1, 1) | bit(2, 1) | bit(2, 2)), "an L room is all three cells");
        yes(!RoomGlow.has(l, 1, 2), "the corner an L leaves out is another room");

        // In a doorway, both rooms count; a mob in a doorway counts for both.
        long door = RoomGlow.around(-168.5, -185, apart);
        yes(door == (bit(0, 0) | bit(1, 0)), "standing in a doorway takes both rooms");
        yes(RoomGlow.inside(bit(1, 0), -168.5, -185), "a mob in the doorway belongs to the room past it");

        // Off the grid nothing is filtered: the boss arena, the old generator's positive coordinates.
        yes(RoomGlow.around(60, 150, apart) == 0, "off the grid there is no room");
        yes(RoomGlow.around(-185, 10, apart) == 0, "past the last row there is no room");

        System.out.println(checks + " Room Glow checks passed");
    }

    private static RoomGlow.Seams seams(Set<String> merged) {
        return (i, j, alongX) -> merged.contains(i + "," + j + "," + (alongX ? "x" : "z"));
    }

    private static long bit(int i, int j) {
        return 1L << (i * RoomGlow.CELLS + j);
    }

    private static void yes(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
