package com.endsight.dungeon;

import com.endsight.dungeon.IcePathLogic.Cell;
import com.endsight.dungeon.IcePathLogic.Direction;
import com.endsight.dungeon.IcePathLogic.Sheet;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class IcePathChecks {
    private static int checks;

    /**
     * The real room, read off a /record taken standing in it: a 17x16 sheet of packed
     * ice with fifteen polished andesite blocks on top. '#' is a block, '.' is ice.
     * Rows run z+1 upward, columns x-8 to x+8.
     */
    private static final String[] ROOM = {
            "...#..........#..",
            "......#...#......",
            ".................",
            ".................",
            ".................",
            ".....#........#..",
            ".#....#........#.",
            ".#...............",
            ".................",
            ".................",
            ".................",
            ".................",
            ".................",
            "#...............#",
            "....#.......#....",
            "...............#.",
    };

    public static void main(String[] args) {
        Set<Cell> ice = new HashSet<>(), blocked = new HashSet<>();
        for (int row = 0; row < ROOM.length; row++) {
            for (int col = 0; col < ROOM[row].length(); col++) {
                Cell cell = new Cell(col - 8, row + 1);
                ice.add(cell);
                if (ROOM[row].charAt(col) == '#') blocked.add(cell);
            }
        }
        check(ice.size() == 17 * 16, "the recorded sheet is 17x16");
        check(blocked.size() == 15, "fifteen blocks stand on it, as recorded");

        // -- sliding ------------------------------------------------------------
        Sheet plain = new Sheet(ice, blocked, new Cell(99, 99));   // goal out of the way
        Cell corner = new Cell(-8, 1);                              // where the silverfish was
        check(IcePathLogic.slide(plain, corner, Direction.WEST).equals(corner),
                "a hit into the wall does not move it");
        check(IcePathLogic.slide(plain, corner, Direction.NORTH).equals(corner),
                "nor does a hit off the near edge");
        Cell east = IcePathLogic.slide(plain, corner, Direction.EAST);
        check(east.equals(new Cell(-6, 1)), "sliding east stops beside the block at x-5");
        Cell south = IcePathLogic.slide(plain, corner, Direction.SOUTH);
        check(south.equals(new Cell(-8, 13)), "sliding south runs to the block at z+14");

        // -- solving ------------------------------------------------------------
        Sheet toMiddle = new Sheet(ice, blocked, new Cell(0, 16));  // "back mid"
        List<Direction> route = IcePathLogic.solve(toMiddle, corner);
        check(!route.isEmpty(), "the recorded room is solvable from the fish's corner");
        check(IcePathLogic.next(toMiddle, corner) == route.get(0), "next() is the first hit of the route");
        // walk the route and confirm it really lands on the goal
        Cell at = corner;
        for (Direction d : route) at = IcePathLogic.slide(toMiddle, at, d);
        check(at.equals(new Cell(0, 16)), "following the route lands the fish on the goal");
        System.out.println("   route from the corner: " + route);

        // shortest: no shorter sequence exists
        check(route.size() <= shortestByBruteForce(toMiddle, corner, route.size()),
                "the route found is as short as any");

        // -- it must be able to say 'impossible' --------------------------------
        Set<Cell> walled = new HashSet<>(blocked);
        for (int x = -8; x <= 8; x++) walled.add(new Cell(x, 15));   // fence off the back
        Sheet sealed = new Sheet(ice, walled, new Cell(0, 16));
        check(IcePathLogic.solve(sealed, corner).isEmpty(), "an unreachable goal returns no route");
        check(IcePathLogic.next(sealed, corner) == null, "and no next hit");
        check(IcePathLogic.solve(toMiddle, new Cell(0, 16)).isEmpty(), "already home needs no hits");

        // Complete /record 20261014-024244: the chest is beyond the main ice sheet.
        // Stone in the approach is supported floor, not a wall or a missing tile.
        String[] complete = {
            ".............#...    ",
            "......#........#.    ",
            "#....#...........    ",
            ".................    ",
            "..............#..    ",
            "................##.  ",
            ".#...............##  ",
            "................... .",
            "....................G",
            ".....................",
            ".#....#..........##  ",
            ".....#...........#.  ",
            "..............#..    ",
            "#................    ",
            ".................    ",
            "......##.........    ",
            ".............#...    ",
        };
        Set<Cell> floor = new HashSet<>(), obstacles = new HashSet<>();
        for (int z = 0; z < complete.length; z++) {
            for (int x = 0; x < complete[z].length(); x++) {
                char tile = complete[z].charAt(x);
                Cell cell = new Cell(x - 5, z - 10);
                if (tile != ' ') floor.add(cell);
                if (tile == '#') obstacles.add(cell);
            }
        }
        Sheet recorded = new Sheet(floor, obstacles, new Cell(15, -2));
        List<Direction> full = IcePathLogic.solve(recorded, new Cell(-5, 6));
        check(!full.isEmpty(), "complete recorded room reaches the chest over its stone approach");
        Cell cursor = new Cell(-5, 6);
        for (Direction direction : full) {
            Cell landed = IcePathLogic.slide(recorded, cursor, direction);
            check(!landed.equals(cursor), "every drawn slide advances the fish");
            cursor = landed;
        }
        check(cursor.equals(recorded.goal()), "full recorded route ends at the actual chest");
        Set<Cell> iceOnly = new HashSet<>(floor);
        iceOnly.remove(new Cell(12, -2));
        iceOnly.remove(new Cell(12, -1));
        iceOnly.remove(new Cell(13, -1));
        check(IcePathLogic.solve(new Sheet(iceOnly, obstacles, recorded.goal()), new Cell(-5, 6)).isEmpty(),
                "excluding the recorded stone approach reproduces the missing route");

        System.out.println(checks + " ice path checks passed");
    }

    /** Independent breadth-first count, to confirm the route is genuinely shortest. */
    private static int shortestByBruteForce(Sheet sheet, Cell from, int cap) {
        Set<Cell> seen = new HashSet<>(Set.of(from));
        List<Cell> frontier = List.of(from);
        for (int depth = 1; depth <= cap; depth++) {
            List<Cell> next = new java.util.ArrayList<>();
            for (Cell cell : frontier) {
                for (Direction d : Direction.values()) {
                    Cell landed = IcePathLogic.slide(sheet, cell, d);
                    if (landed.equals(sheet.goal())) return depth;
                    if (seen.add(landed)) next.add(landed);
                }
            }
            frontier = next;
        }
        return cap;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        checks++;
    }
}
