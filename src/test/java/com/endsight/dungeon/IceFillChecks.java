package com.endsight.dungeon;

import com.endsight.dungeon.IceFillLogic.Cell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class IceFillChecks {
    private static int checks;

    /*
     * Real walks from a run on 2026-11-09: each cell turned to packed ice in this order as
     * it was stepped on, and each stage was cleared, so the cells of its square never
     * stepped on are the ones something stands on.
     */
    // 5x5 at y70, x -124..-120, z -155..-151, exit (-119,-153). Its reset re-sent polished
    // andesite on exactly the four cells this walk leaves out.
    private static final int[][] FIVE = {{-124, -153}, {-124, -154}, {-124, -155}, {-123, -155}, {-122, -155},
            {-121, -155}, {-121, -154}, {-122, -154}, {-122, -153}, {-123, -153}, {-123, -152}, {-124, -152},
            {-124, -151}, {-123, -151}, {-122, -151}, {-121, -151}, {-120, -151}, {-120, -152}, {-121, -152},
            {-121, -153}, {-120, -153}, {-119, -153}};
    // 7x7 at y71, x -117..-111, z -156..-150, exit (-110,-153).
    private static final int[][] SEVEN = {{-117, -153}, {-117, -154}, {-117, -155}, {-117, -156}, {-116, -156},
            {-116, -155}, {-116, -154}, {-115, -154}, {-115, -155}, {-115, -156}, {-114, -156}, {-113, -156},
            {-112, -156}, {-111, -156}, {-111, -155}, {-112, -155}, {-113, -155}, {-113, -154}, {-112, -154},
            {-112, -153}, {-112, -152}, {-113, -152}, {-113, -151}, {-114, -151}, {-115, -151}, {-115, -152},
            {-116, -152}, {-117, -152}, {-117, -151}, {-117, -150}, {-116, -150}, {-115, -150}, {-114, -150},
            {-113, -150}, {-112, -150}, {-112, -151}, {-111, -151}, {-111, -152}, {-111, -153}, {-110, -153}};
    // 3x3 from the later run in the clip, x -33..-31, z -186..-184, exit (-30,-185).
    private static final int[][] THREE = {{-33, -185}, {-33, -184}, {-32, -184}, {-32, -185}, {-32, -186},
            {-31, -186}, {-31, -185}, {-30, -185}};

    public static void main(String[] args) {
        Set<Cell> andesite = Set.of(new Cell(-122, -152), new Cell(-120, -154), new Cell(-123, -154), new Cell(-120, -155));
        Set<Cell> square = new HashSet<>();
        for (int x = -124; x <= -120; x++) for (int z = -155; z <= -151; z++) square.add(new Cell(x, z));
        square.removeAll(cells(FIVE));
        check(square.equals(andesite), "the cells the 5x5 walk skips are exactly the andesite in that run");

        for (int[][] real : List.of(THREE, FIVE, SEVEN)) {
            List<Cell> walk = cells(real);
            Set<Cell> fresh = new HashSet<>(walk);
            check(valid(walk, fresh, walk.getFirst()), real.length + "-cell real walk is a walk under this model");
            Cell entry = walk.getFirst(), exit = walk.getLast();

            // Standing on the step before the stage, one block short of its entry cell.
            List<Cell> fromOutside = IceFillLogic.plan(fresh, null, entry.x() - .5, entry.z() + .5, IceFillLogic.BUDGET);
            check(!fromOutside.isEmpty() && fromOutside.getFirst().equals(entry), real.length + "-cell stage: route starts at the entry");
            check(valid(fromOutside, fresh, entry), real.length + "-cell stage: route covers every cell once");
            check(fromOutside.getLast().equals(exit), real.length + "-cell stage: route ends on the exit");

            // Halfway through the real walk, standing on a cell that is already packed.
            int k = walk.size() / 2;
            Set<Cell> left = new HashSet<>(walk.subList(k + 1, walk.size()));
            List<Cell> fromMiddle = IceFillLogic.plan(left, walk.get(k), 0, 0, IceFillLogic.BUDGET);
            check(!fromMiddle.isEmpty() && fromMiddle.getFirst().equals(walk.get(k)), real.length + "-cell stage: a route from where you stand mid-walk");
            Set<Cell> withStart = new HashSet<>(left);
            withStart.add(walk.get(k));
            check(valid(fromMiddle, withStart, walk.get(k)), real.length + "-cell stage: mid-walk route covers what is left once");
        }

        // A stage cut in two cannot be walked; it says nothing rather than half a route.
        Set<Cell> split = new HashSet<>(cells(FIVE));
        split.removeAll(Set.of(new Cell(-122, -155), new Cell(-122, -154), new Cell(-122, -153), new Cell(-122, -151)));
        check(IceFillLogic.plan(split, new Cell(-124, -153), 0, 0, IceFillLogic.BUDGET).isEmpty(), "a split stage gives no route");
        check(IceFillLogic.plan(new HashSet<>(cells(SEVEN)), new Cell(-117, -153), 0, 0, 3).isEmpty(), "a spent budget gives no route, not a hang");
        check(IceFillLogic.plan(Set.of(new Cell(-117, -153)), new Cell(-117, -153), 0, 0, IceFillLogic.BUDGET).isEmpty(), "a finished stage draws nothing");

        System.out.println("IceFillChecks: " + checks + " checks passed");
    }

    private static List<Cell> cells(int[][] raw) {
        List<Cell> out = new ArrayList<>();
        for (int[] c : raw) out.add(new Cell(c[0], c[1]));
        return out;
    }

    /** Starts at {@code start}, steps one cell at a time, and visits each of {@code all} exactly once. */
    private static boolean valid(List<Cell> walk, Set<Cell> all, Cell start) {
        if (walk.isEmpty() || !walk.getFirst().equals(start) || walk.size() != all.size()) return false;
        if (!new HashSet<>(walk).equals(all)) return false;
        for (int i = 1; i < walk.size(); i++) if (!walk.get(i - 1).touches(walk.get(i))) return false;
        return true;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
