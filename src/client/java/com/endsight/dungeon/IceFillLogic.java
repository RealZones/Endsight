package com.endsight.dungeon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ice Fill: walk over every ice block of a stage exactly once. Each one turns to packed ice
 * under you, and stepping on packed ice again fails ("Oops! You stepped on the wrong
 * block!") and resets the stage.
 *
 * As a run on 2026-11-09 showed it: three
 * stages one block apart in height, 3x3, 5x5 and 7x7, entered at the middle of the near
 * edge and left over one extra cell sticking out of the middle of the far edge. Polished
 * andesite stands on some cells and nothing can step there. That exit cell has a single
 * neighbour, which is why the search is never told where a stage ends: a walk that covers
 * every cell has to finish on it.
 */
final class IceFillLogic {
    record Cell(int x, int z) {
        Cell step(int dx, int dz) { return new Cell(x + dx, z + dz); }
        boolean touches(Cell other) { return Math.abs(x - other.x) + Math.abs(z - other.z) == 1; }
    }

    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Far above what a 7x7 stage needs with the pruning below; it only stops a pathological board hanging a tick. */
    static final int BUDGET = 200_000;

    private IceFillLogic() { }

    /**
     * The cells to walk, in order, starting with the one you stand on or should step onto
     * first. {@code standing} is your cell when you are on the stage (fresh or already
     * packed), else null, and the walk starts from the nearest cell one can start from.
     * Empty when the stage is done or has no answer from here.
     */
    static List<Cell> plan(Set<Cell> fresh, Cell standing, double px, double pz, int budget) {
        if (standing != null) {
            Set<Cell> left = new HashSet<>(fresh);
            left.remove(standing);
            if (left.isEmpty()) return List.of();
            List<Cell> rest = solve(fresh, standing, budget);
            if (rest.isEmpty()) return List.of();
            List<Cell> out = new ArrayList<>(rest.size() + 1);
            out.add(standing);
            out.addAll(rest);
            return out;
        }
        // Off the stage, the cell nearest you is normally its entrance. A cell a full walk
        // cannot start from (a 7x7 corner of the wrong colour) is skipped for the next.
        List<Cell> order = new ArrayList<>(fresh);
        order.sort(Comparator.comparingDouble(c -> Math.pow(c.x() + .5 - px, 2) + Math.pow(c.z() + .5 - pz, 2)));
        for (Cell first : order.subList(0, Math.min(8, order.size()))) {
            if (fresh.size() == 1) return List.of(first);
            List<Cell> rest = solve(fresh, first, budget);
            if (rest.isEmpty()) continue;
            List<Cell> out = new ArrayList<>(rest.size() + 1);
            out.add(first);
            out.addAll(rest);
            return out;
        }
        return List.of();
    }

    /** A walk from {@code start} over every other cell of {@code fresh} once, start left out; empty if none. */
    static List<Cell> solve(Set<Cell> fresh, Cell start, int budget) {
        Search search = new Search(new HashSet<>(fresh), budget);
        search.left.remove(start);
        if (search.left.isEmpty()) return List.of();
        Deque<Cell> path = new ArrayDeque<>();
        return search.walk(start, path) ? List.copyOf(path) : List.of();
    }

    private static final class Search {
        final Set<Cell> left;
        int budget;

        Search(Set<Cell> left, int budget) { this.left = left; this.budget = budget; }

        boolean walk(Cell at, Deque<Cell> path) {
            if (left.isEmpty()) return true;
            if (--budget < 0 || !viable(at)) return false;
            List<Cell> next = new ArrayList<>(4);
            for (int[] s : STEPS) {
                Cell n = at.step(s[0], s[1]);
                if (left.contains(n)) next.add(n);
            }
            // Fewest onward moves first (Warnsdorff): squares into corners early, which is
            // what finds a covering walk on these boards without backtracking much.
            next.sort(Comparator.comparingInt(this::onward));
            for (Cell n : next) {
                left.remove(n);
                path.addLast(n);
                if (walk(n, path)) return true;
                path.removeLast();
                left.add(n);
                if (budget < 0) return false;
            }
            return false;
        }

        private int onward(Cell cell) {
            int count = 0;
            for (int[] s : STEPS) if (left.contains(cell.step(s[0], s[1]))) count++;
            return count;
        }

        /**
         * Whether a covering walk can still exist from here: nothing left unreachable, and at
         * most one cell with a single way in, since such a cell can only be the last one.
         */
        private boolean viable(Cell at) {
            int ends = 0;
            Cell seed = null;
            for (Cell cell : left) {
                int degree = at.touches(cell) ? 1 : 0;
                if (degree == 1) seed = cell;
                degree += onward(cell);
                if (degree == 0) return false;
                if (degree == 1 && ++ends > 1) return false;
            }
            if (seed == null) return false;
            Set<Cell> seen = new HashSet<>();
            ArrayDeque<Cell> queue = new ArrayDeque<>();
            seen.add(seed);
            queue.add(seed);
            while (!queue.isEmpty()) {
                Cell cell = queue.removeFirst();
                for (int[] s : STEPS) {
                    Cell n = cell.step(s[0], s[1]);
                    if (left.contains(n) && seen.add(n)) queue.add(n);
                }
            }
            return seen.size() == left.size();
        }
    }
}
