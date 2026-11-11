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

        // A teammate presses wrong - unseen here - and the board restarts with one new
        // flash. That is the whole sequence now, not a third tile on the old two.
        sequence.observe(1 << 12, 0);
        quiet(sequence, 7);
        if (sequence.target() != -1) throw new AssertionError("A restart was read as the next tile");
        quiet(sequence, 5);
        if (sequence.target() != 12 || sequence.count() != 1)
            throw new AssertionError("Restart did not replace the sequence: " + sequence.count());
        checks += 2;

        // The same restart, but its first tile happens to be the old first tile.
        SimonSaysBoard.Sequence same = new SimonSaysBoard.Sequence();
        flash(same, 5); quiet(same, 7); same.observe(0, 1 << 5); same.observe(0, 0);
        flash(same, 5); quiet(same, 4); flash(same, 9); quiet(same, 7);
        if (same.count() != 2) throw new AssertionError("second round not read");
        flash(same, 5); quiet(same, 12);
        if (same.target() != 5 || same.count() != 1) throw new AssertionError("Same-start restart kept the old round");
        checks++;

        // A one-tick dark blip inside a flash is the same flash, not the tile twice.
        SimonSaysBoard.Sequence blip = new SimonSaysBoard.Sequence();
        blip.observe(1 << 3, 0); blip.observe(1 << 3, 0); blip.observe(0, 0); blip.observe(1 << 3, 0);
        quiet(blip, 7);
        if (blip.count() != 1) throw new AssertionError("A blip doubled the flash: " + blip.count());
        checks++;

        checks += replay();
        System.out.println(checks + " Simon Says checks passed");
    }

    private static void flash(SimonSaysBoard.Sequence sequence, int lamp) {
        for (int i = 0; i < 8; i++) sequence.observe(1 << lamp, 0);
    }

    /**
     * Dragsim's board in the 2026-11-09 18:12 recording, tick by tick: lamp on and off times
     * in ms from the first flash, and Fear's own presses (the only ones a client sees).
     * Lamps A, B, C are (122,93), (120,94), (121,92); here cells 5, 10 and 8.
     */
    private static int replay() {
        int a = 5, b = 10, c = 8;
        int[][] flashes = {{0, 449, a}, {2320, 2800, a}, {3040, 3343, b},
                {6812, 7188, a}, {7200, 7630, b}, {7818, 8194, c}};
        int[][] presses = {{4752, a}, {5352, b}, {8752, a}, {9401, b}, {10000, c}};
        SimonSaysBoard.Sequence s = new SimonSaysBoard.Sequence();
        List<Integer> hints = new ArrayList<>();
        int last = -2;
        for (int t = 0; t <= 10500; t += 50) {
            int lights = 0, pressed = 0;
            for (int[] f : flashes) if (t >= f[0] && t < f[1]) lights |= 1 << f[2];
            for (int[] p : presses) if (t >= p[0] && t < p[0] + 50) pressed |= 1 << p[1];
            s.observe(lights, pressed);
            int hint = s.target();
            if (hint != last && hint >= 0) hints.add(hint);
            last = hint;
        }
        // The first round (A alone) was pressed by a teammate, unseen; the hints for the
        // rounds Fear pressed are A, B, then A, B, C.
        List<Integer> want = List.of(a, b, a, b, c);
        if (!hints.subList(hints.size() - 5, hints.size()).equals(want))
            throw new AssertionError("Recorded rounds gave hints " + hints);
        return 1;
    }

    private static void quiet(SimonSaysBoard.Sequence sequence, int ticks) {
        for (int i = 0; i < ticks; i++) sequence.observe(0, 0);
    }
}
