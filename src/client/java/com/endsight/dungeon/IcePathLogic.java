package com.endsight.dungeon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Ice Path": hit the silverfish and it slides until something stops it. Get it home.
 *
 * The room is a flat sheet of packed ice with a scatter of blocks standing on it, and
 * an opening to reach. A hit sends the fish in one of four directions and it keeps
 * going until the next cell is a block or the end of the sheet - so a "move" is not one
 * step, it is a whole slide, and the puzzle is which four-way sequence lands on the
 * opening.
 *
 * That makes it a shortest-path problem over slide destinations rather than over cells,
 * which is why a breadth-first search finds the real answer rather than a plausible
 * one. A recorded room is 272 cells with 15 blocks, so the search is small.
 *
 * Nothing here knows about Minecraft. The client half supplies the sheet, the blocks,
 * the fish and the goal, and gets back the direction to hit first.
 */
public final class IcePathLogic {

    private IcePathLogic() {
    }

    /** A cell on the sheet. Flat, so only two axes matter. */
    public record Cell(int x, int z) {
    }

    /** The four ways a hit can send it. */
    public enum Direction {
        NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);

        public final int dx, dz;

        Direction(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    /** The room: which cells are ice, which carry a block, and where the fish must end. */
    public record Sheet(Set<Cell> ice, Set<Cell> blocked, Cell goal) {
        public boolean open(Cell cell) {
            return ice.contains(cell) && !blocked.contains(cell);
        }
    }

    /**
     * Where a hit in this direction leaves the fish.
     *
     * It travels while the NEXT cell is open and stops on the last one it could reach,
     * which is what "the blocks stop it" means. A hit into something immediately
     * adjacent does not move it at all, and that is returned unchanged rather than
     * treated as illegal - the search simply will not use it.
     */
    public static Cell slide(Sheet sheet, Cell from, Direction direction) {
        Cell at = from;
        while (true) {
            Cell next = new Cell(at.x() + direction.dx, at.z() + direction.dz);
            if (!sheet.open(next)) return at;
            at = next;
            if (at.equals(sheet.goal())) return at;      // the opening catches it
        }
    }

    /**
     * The hits that take the fish home, shortest first, or empty when it cannot.
     *
     * Breadth-first over slide destinations: the first time a cell is reached is by the
     * fewest hits, so the path recorded then is the best one. Returning the whole
     * sequence rather than just the next hit means the caller can show the route, and
     * can tell "no answer" from "one hit away" - a solver that cannot say "impossible"
     * will eventually point somewhere wrong instead.
     */
    public static List<Direction> solve(Sheet sheet, Cell fish) {
        if (sheet == null || fish == null || sheet.goal() == null) return List.of();
        if (fish.equals(sheet.goal())) return List.of();
        Map<Cell, Cell> cameFrom = new HashMap<>();
        Map<Cell, Direction> hitTaken = new HashMap<>();
        Set<Cell> seen = new HashSet<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        seen.add(fish);
        queue.add(fish);
        while (!queue.isEmpty()) {
            Cell at = queue.poll();
            for (Direction direction : Direction.values()) {
                Cell landed = slide(sheet, at, direction);
                if (landed.equals(at) || !seen.add(landed)) continue;
                cameFrom.put(landed, at);
                hitTaken.put(landed, direction);
                if (landed.equals(sheet.goal())) return path(cameFrom, hitTaken, fish, landed);
                queue.add(landed);
            }
        }
        return List.of();
    }

    /** The first hit to make, or null when there is no route. */
    public static Direction next(Sheet sheet, Cell fish) {
        List<Direction> route = solve(sheet, fish);
        return route.isEmpty() ? null : route.get(0);
    }

    private static List<Direction> path(Map<Cell, Cell> cameFrom, Map<Cell, Direction> hitTaken,
                                        Cell start, Cell end) {
        List<Direction> out = new ArrayList<>();
        for (Cell at = end; !at.equals(start); at = cameFrom.get(at)) {
            out.add(hitTaken.get(at));
        }
        java.util.Collections.reverse(out);
        return List.copyOf(out);
    }
}
