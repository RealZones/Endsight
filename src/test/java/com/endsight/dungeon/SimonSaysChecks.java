package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Board orientation and the light/press transitions seen on the world puzzle. */
public final class SimonSaysChecks {
    private SimonSaysChecks() { }

    public static void main(String[] args) {
        int checks = 0;
        for (SimonSaysBoard.Facing facing : SimonSaysBoard.Facing.values()) {
            SimonSaysBoard.Grid grid = new SimonSaysBoard.Grid(new SimonSaysBoard.Point(100, 70, 200), facing);
            Map<SimonSaysBoard.Point, Integer> board = new HashMap<>();
            List<SimonSaysBoard.Seed> seeds = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                board.put(grid.support(i), i == 11 ? 1 : i == 7 ? 2 : 0);
                seeds.add(new SimonSaysBoard.Seed(grid.button(i), facing));
            }
            SimonSaysBoard.Probe probe = (point, direction) -> board.getOrDefault(point, -1);
            SimonSaysBoard.Snapshot found = SimonSaysBoard.find(seeds, probe);
            if (found == null || !found.grid().equals(grid)
                    || found.lights() != 1 << 11 || found.pressed() != 1 << 7)
                throw new AssertionError("4x4 board orientation failed: " + facing);
            board.remove(grid.support(15));
            if (SimonSaysBoard.find(seeds, probe) != null)
                throw new AssertionError("Incomplete board accepted: " + facing);
            checks += 2;
        }

        SimonSaysBoard.Sequence sequence = new SimonSaysBoard.Sequence();
        sequence.observe(1 << 5, 0);
        for (int i = 0; i < 12; i++) sequence.observe(1 << 5, 0);
        sequence.observe(0, 0);
        if (sequence.target() != -1) throw new AssertionError("Long lamp flash skipped the dark settle time");
        quiet(sequence, 7);
        if (sequence.target() != 5) throw new AssertionError("First flash did not point to its button");
        sequence.observe(0, 1 << 5);
        if (sequence.target() != -1) throw new AssertionError("Completed stage kept an old hint");
        checks += 3;

        // Full-prefix replay: A, then A-B. A alone must not be offered as a
        // complete new round while the next flash is still on its way.
        sequence.observe(1 << 5, 0);
        quiet(sequence, 10);
        if (sequence.target() != -1) throw new AssertionError("Prefix replay offered a premature hint");
        sequence.observe(1 << 9, 0);
        quiet(sequence, 7);
        if (sequence.target() != 5 || sequence.count() != 2)
            throw new AssertionError("Replay extension lost the first button");
        sequence.observe(0, 1 << 5);
        if (sequence.target() != 9) throw new AssertionError("First click did not advance hint");
        sequence.observe(0, 0);
        sequence.observe(0, 1 << 9);
        if (sequence.target() != -1) throw new AssertionError("Last click did not finish stage");
        checks += 4;

        // A board that only shows its newly added light should extend the
        // sequence without discarding earlier rounds.
        sequence.observe(1 << 12, 0);
        quiet(sequence, 7);
        if (sequence.target() != 5 || sequence.count() != 3)
            throw new AssertionError("New-only stage did not extend sequence");
        checks++;
        System.out.println(checks + " Simon Says checks passed");
    }

    private static void quiet(SimonSaysBoard.Sequence sequence, int ticks) {
        for (int i = 0; i < ticks; i++) sequence.observe(0, 0);
    }
}
