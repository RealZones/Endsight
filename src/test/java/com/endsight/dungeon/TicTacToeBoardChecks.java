package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TicTacToeBoardChecks {
    private static int checks;
    private TicTacToeBoardChecks() { }

    public static void main(String[] args) {
        for (TicTacToeBoard.Plane plane : TicTacToeBoard.Plane.values()) geometry(plane);
        TicTacToeBoard.Grid floor = grid(TicTacToeBoard.Plane.FLOOR, 10, 70, -4);
        int[] observed = {2, 0, 0, 1, 2, 0, 1, 0, 1};
        Map<TicTacToeBoard.Point, Integer> cells = board(floor, observed);
        TicTacToeBoard.Probe probe = (point, plane) -> cells.getOrDefault(point, -1);
        List<TicTacToeBoard.Seed> seeds = seeds(floor, observed);
        var found = TicTacToeBoard.find(seeds, probe);
        check(found != null && found.grid().equals(floor) && found.cells().equals(List.of(2, 0, 0, 1, 2, 0, 1, 0, 1)),
                "partially played floor board is inferred from remaining buttons");
        check(TicTacToeBoard.matches(found, probe), "unchanged render sample accepts the stable board");
        check(TicTacToeBoard.find(List.of(seeds.getLast()), probe).grid().equals(floor),
                "one remaining seed identifies every occupied cell without nine buttons");
        cells.remove(floor.support(8));
        check(!TicTacToeBoard.matches(found, probe), "unloaded render cell rejects the cached hint");
        check(TicTacToeBoard.find(seeds, probe) == null, "unloaded or mismatched cell invalidates entire board");
        cells.put(floor.support(8), 1);
        cells.put(floor.support(7), 2);
        check(!TicTacToeBoard.matches(found, probe), "changed token rejects a hint before the next tick");
        cells.put(floor.support(7), 0);
        TicTacToeBoard.Grid second = grid(TicTacToeBoard.Plane.FLOOR, 16, 70, -4);
        cells.putAll(board(second, observed));
        List<TicTacToeBoard.Seed> twoBoards = new ArrayList<>(seeds);
        twoBoards.addAll(seeds(second, observed));
        check(TicTacToeBoard.find(twoBoards, probe) == null, "two complete nearby boards never choose arbitrarily");
        var stability = new TicTacToeBoard.Stabilizer();
        stability.observe(found);
        check(stability.stable() == null, "first reading has no hint");
        stability.observe(found);
        check(stability.stable().equals(found), "second identical tick permits a hint");
        var changed = new TicTacToeBoard.Snapshot(floor, List.of(2, 0, 0, 1, 2, 0, 1, 2, 1));
        stability.observe(changed);
        check(stability.stable() == null, "tile update clears the old hint immediately");
        stability.observe(changed);
        check(stability.stable().equals(changed), "settled next turn is accepted");
        stability.observe(null);
        check(stability.stable() == null, "partial server block/button batch suppresses hint");
        stability.observe(changed);
        check(stability.stable() == null, "first recovered reading cannot restore stale hint");
        stability.clear();
        check(stability.stable() == null, "world or room reset clears accepted board");
        check(TicTacToe.entry("§dPUZZLE! Tic Tac Toe - Don't lose to the machine. Right-click a white tile to play."),
                "exact observed system entry activates");
        check(!TicTacToe.entry("[MVP+] Player: PUZZLE! Tic Tac Toe - Don't lose to the machine. Right-click a white tile to play."),
                "player pasted entry never activates");
        check(TicTacToe.endsRoom("PUZZLE FAIL! The machine won Tic Tac Toe"), "observed machine win ends room");
        check(TicTacToe.endsRoom("PUZZLE SOLVED! Tic Tac Toe"), "matching solution ends room");
        check(TicTacToe.endsRoom("PUZZLE! Three Weirdos - One of the three always lies."), "entering a different puzzle clears turn state");
        System.out.println(checks + " tic tac toe board checks passed");
    }

    private static void geometry(TicTacToeBoard.Plane plane) {
        TicTacToeBoard.Grid grid = grid(plane, 10, 70, -4);
        Map<TicTacToeBoard.Point, Integer> cells = board(grid, new int[9]);
        TicTacToeBoard.Probe probe = (point, testedPlane) -> testedPlane == plane ? cells.getOrDefault(point, -1) : -1;
        check(TicTacToeBoard.find(seeds(grid, new int[9]), probe).grid().equals(grid), plane + " uniquely inferred");
        check(grid.button(4).equals(grid.support(4).add(plane.nx, plane.ny, plane.nz)), plane + " button is on outward face");
        check(grid.support(8).equals(grid.origin().add(2 * plane.cx + 2 * plane.rx,
                2 * plane.cy + 2 * plane.ry, 2 * plane.cz + 2 * plane.rz)), plane + " row-major corner");
        if (plane == TicTacToeBoard.Plane.FLOOR)
            check(grid.support(1).x() == 11 && grid.support(3).z() == -3 && grid.button(0).y() == 71,
                    "floor columns +X, rows +Z, buttons above tiles");
        else {
            check(grid.support(3).y() == 69, plane + " wall rows run downward");
            int expectedX = switch (plane) { case NORTH -> -1; case SOUTH -> 1; default -> 0; };
            int expectedZ = switch (plane) { case EAST -> -1; case WEST -> 1; default -> 0; };
            check(grid.support(1).x() - grid.origin().x() == expectedX
                    && grid.support(1).z() - grid.origin().z() == expectedZ, plane + " camera-right from front");
        }
    }

    private static TicTacToeBoard.Grid grid(TicTacToeBoard.Plane plane, int x, int y, int z) {
        return new TicTacToeBoard.Grid(new TicTacToeBoard.Point(x, y, z), plane);
    }

    private static Map<TicTacToeBoard.Point, Integer> board(TicTacToeBoard.Grid grid, int[] values) {
        Map<TicTacToeBoard.Point, Integer> cells = new HashMap<>();
        for (int i = 0; i < 9; i++) cells.put(grid.support(i), values[i]);
        return cells;
    }

    private static List<TicTacToeBoard.Seed> seeds(TicTacToeBoard.Grid grid, int[] values) {
        List<TicTacToeBoard.Seed> seeds = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (values[i] == 0) seeds.add(new TicTacToeBoard.Seed(grid.button(i), grid.plane()));
        return seeds;
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
