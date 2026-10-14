package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.endsight.dungeon.TerminalLogic.Button.LEFT;
import static com.endsight.dungeon.TerminalLogic.Button.RIGHT;
import static com.endsight.dungeon.TerminalLogic.Type;

public final class TerminalLogicChecks {
    private static int checks, rubixBoards;
    private static final String[] CYCLE = {"red", "orange", "yellow", "green", "blue"};
    private static final int[] RUBIX = {12, 13, 14, 21, 22, 23, 30, 31, 32};

    public static void main(String[] args) {
        List<TerminalLogic.Cell> numbers = rectangle(36, 2, 5, "red_stained_glass_pane");
        int number = 10;
        // Reverse values ensure ordering follows numbers rather than slot position.
        for (int row = 1; row <= 2; row++) for (int col = 2; col <= 6; col++) {
            int slot = row * 9 + col;
            put(numbers, slot, "red_stained_glass_pane", "§c" + number, number--, false);
        }
        TerminalLogic.Plan plan = TerminalLogic.solve("Click in order!", 36, numbers);
        check(plan.type() == Type.ORDER && plan.hints().size() == 10 && plan.hints().getFirst().slot() == 24, "numbers sorted by remaining value, not native slot");
        put(numbers, 24, "lime_stained_glass_pane", "§a1", 1, false);
        check(TerminalLogic.solve("Click in order!", 36, numbers).hints().getFirst().slot() == 23, "completed first number skipped");
        put(numbers, 22, "lime_stained_glass_pane", "§a3", 3, false);
        check(TerminalLogic.solve("Click in order!", 36, numbers).hints().isEmpty(), "non-prefix green update suppresses order hints");
        numbers = rectangle(36, 2, 7, "red_stained_glass_pane");
        number = 1;
        for (int row = 1; row <= 2; row++) for (int col = 1; col <= 7; col++) {
            put(numbers, row * 9 + col, "red_stained_glass_pane", "" + number, number++, false);
        }
        check(TerminalLogic.solve("Click in order!", 36, numbers).hints().size() == 14, "complete seven-column numbers recognized");
        put(numbers, 11, "red_stained_glass_pane", "1", 1, false);
        check(TerminalLogic.solve("Click in order!", 36, numbers).hints().isEmpty(), "duplicate number suppresses ambiguous ordering");

        List<TerminalLogic.Cell> panes = rectangle(45, 3, 5, "lime_stained_glass_pane");
        put(panes, 12, "red_stained_glass_pane", "", 1, false);
        put(panes, 31, "red_stained_glass_pane", "", 1, false);
        plan = TerminalLogic.solve("Correct all the panes!", 45, panes);
        check(plan.hints().equals(List.of(new TerminalLogic.Hint(12, 1, LEFT), new TerminalLogic.Hint(31, 1, LEFT))), "blank-named panes use material colors");
        put(panes, 12, "lime_stained_glass_pane", "", 1, false);
        check(TerminalLogic.solve("Correct all the panes!", 45, panes).hints().size() == 1, "green pane no longer suggested");
        put(panes, 14, "air", "", 0, false);
        check(TerminalLogic.solve("Correct all the panes!", 45, panes).hints().isEmpty(), "missing pane suppresses incomplete menu");

        List<TerminalLogic.Cell> items = rectangle(45, 3, 7, "stone");
        put(items, 10, "wooden_sword", "§eWooden Sword", 1, false);
        put(items, 11, "wheat", "Wheat", 1, true);
        put(items, 12, "written_book", "Written Book", 1, true);
        plan = TerminalLogic.solve("What starts with: 'W'?", 45, items);
        check(plan.hints().stream().map(TerminalLogic.Hint::slot).toList().equals(List.of(10, 12)), "initial matching strips colors and preserves naturally glowing item");
        check(TerminalLogic.solve("What starts with: 'W'?", 45, items, Set.of(12)).hints().size() == 1, "confirmed natural-glint selection no longer suggested");
        put(items, 13, "nether_star", "Nether Star", 1, true);
        check(TerminalLogic.solve("What starts with: 'N'?", 45, items).hints().getFirst().slot() == 13, "naturally glowing Nether Star initially selectable");
        put(items, 14, "experience_bottle", "Experience Bottle", 1, true);
        check(TerminalLogic.solve("What starts with: 'E'?", 45, items).hints().getFirst().slot() == 14, "naturally glowing Experience Bottle initially selectable");
        check(TerminalLogic.type("§eWhat starts with: ‘W’?") == Type.STARTS_WITH, "curly title quotes normalize");

        items = rectangle(54, 4, 7, "stone");
        put(items, 10, "purple_wool", "Purple Wool", 1, false);
        put(items, 11, "purple_dye", "Purple Dye", 1, true);
        put(items, 12, "magenta_wool", "Magenta Wool", 1, false);
        check(TerminalLogic.solve("Select all the PURPLE items!", 54, items).hints().stream().map(TerminalLogic.Hint::slot).toList().equals(List.of(10)), "color matches omit selected glint and neighboring dye color");
        put(items, 13, "lapis_lazuli", "Lapis Lazuli", 1, false);
        put(items, 14, "blue_orchid", "Blue Orchid", 1, false);
        check(TerminalLogic.solve("Select all the BLUE items!", 54, items).hints().size() == 2, "blue aliases include Lapis and Blue Orchid");
        put(items, 15, "light_gray_wool", "Silver Wool", 1, false);
        check(TerminalLogic.solve("Select all the SILVER items!", 54, items).hints().size() == 1, "silver title and name aliases match light gray");
        check(TerminalLogic.solve("Select all the RAINBOW items!", 54, items).hints().isEmpty(), "unknown color gives no guessed hints");
        put(items, 16, "air", "", 0, false);
        check(TerminalLogic.solve("Select all the PURPLE items!", 54, items).hints().isEmpty(), "partial item grid suppresses matches");

        List<TerminalLogic.Cell> melody = melody(2, 2, 1);
        plan = TerminalLogic.solve("Click the button on time!", 54, melody);
        check(plan.type() == Type.MELODY && plan.hints().equals(List.of(new TerminalLogic.Hint(16, 1, LEFT))), "aligned first row points at native first button");
        melody = melody(4, 4, 3);
        check(TerminalLogic.solve("Click the button on time!", 54, melody).hints().getFirst().slot() == 34, "aligned third row uses third button");
        melody = melody(4, 2, 2);
        plan = TerminalLogic.solve("Click the button on time!", 54, melody);
        check(plan.hints().isEmpty() && plan.ready(), "waiting melody keeps validated compact schema without timing click");
        put(melody, 11, "lime_stained_glass_pane", "", 1, false);
        check(TerminalLogic.solve("Click the button on time!", 54, melody).hints().isEmpty(), "two moving markers suppress transient updates");
        melody = melody(4, 4, 2);
        put(melody, 3, "magenta_stained_glass_pane", "", 1, false);
        check(TerminalLogic.solve("Click the button on time!", 54, melody).hints().isEmpty(), "two targets suppress guessed alignment");
        melody = melody(2, 2, 2);
        put(melody, 25, "stone", "", 1, false);
        plan = TerminalLogic.solve("Click the button on time!", 54, melody);
        check(plan.hints().isEmpty() && !plan.ready(), "unknown button material keeps native fallback");

        check(TerminalLogic.solve("Storage", 54, background(54)).type() == Type.UNKNOWN, "ordinary storage has no hints");
        check(TerminalLogic.solve("QUICK CHECK: Click in order!", 36, background(36)).type() == Type.UNKNOWN, "prefixed security prompt is not a dungeon terminal title");
        check(TerminalLogic.solve("Correct all the panes!", 54, background(54)).hints().isEmpty(), "wrong native menu size suppressed");
        List<TerminalLogic.Cell> partial = background(45); partial.removeLast();
        check(TerminalLogic.solve("Correct all the panes!", 45, partial).hints().isEmpty(), "missing slot packet prevents hints");
        partial = background(45); partial.set(1, partial.getFirst());
        check(TerminalLogic.solve("Correct all the panes!", 45, partial).hints().isEmpty(), "duplicate native slot IDs rejected");
        plan = new TerminalLogic.Plan(Type.PANES, new ArrayList<>(List.of(new TerminalLogic.Hint(12, 1, LEFT))), "test");
        boolean immutable = false;
        try { plan.hints().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "plan hints cannot be mutated by rendering code");

        // Every distribution of nine cells across five cycle colors (715 boards)
        // must both reach one common color and use the global minimum click count.
        distributions(new int[5], 0, 9);
        check(rubixBoards == 715, "all five-color nine-cell distributions exercised");
        System.out.println(checks + " terminal logic checks passed across " + rubixBoards + " Rubix color distributions");
    }

    private static void distributions(int[] counts, int color, int left) {
        if (color == 4) { counts[color] = left; rubix(counts); return; }
        for (int amount = 0; amount <= left; amount++) { counts[color] = amount; distributions(counts, color + 1, left - amount); }
    }

    private static void rubix(int[] counts) {
        rubixBoards++;
        List<TerminalLogic.Cell> board = background(45);
        int[] original = new int[9], result = new int[9];
        int slot = 0;
        for (int color = 0; color < 5; color++) for (int n = 0; n < counts[color]; n++) {
            original[slot] = result[slot] = color;
            put(board, RUBIX[slot++], CYCLE[color] + "_stained_glass_pane", "", 1, false);
        }
        TerminalLogic.Plan plan = TerminalLogic.solve("Change all to same color!", 45, board);
        int actual = 0;
        for (TerminalLogic.Hint hint : plan.hints()) {
            int index = -1; for (int i = 0; i < 9; i++) if (RUBIX[i] == hint.slot()) index = i;
            check(index >= 0 && hint.clicks() >= 1 && hint.clicks() <= 2, "Rubix hint references known cell with at most two clicks");
            result[index] = Math.floorMod(result[index] + (hint.button() == LEFT ? hint.clicks() : -hint.clicks()), 5);
            actual += hint.clicks();
        }
        for (int color : result) check(color == result[0], "applying suggested L/R rotations reaches a common color");
        int minimum = Integer.MAX_VALUE;
        for (int target = 0; target < 5; target++) {
            int cost = 0;
            for (int current : original) { int distance = Math.abs(current - target); cost += Math.min(distance, 5 - distance); }
            minimum = Math.min(minimum, cost);
        }
        check(actual == minimum, "Rubix plan has globally minimal manual clicks");
        put(board, 13, "purple_stained_glass_pane", "", 1, false);
        check(TerminalLogic.solve("Change all to same color!", 45, board).hints().isEmpty(), "unknown cycle color never produces rotations");
    }

    private static List<TerminalLogic.Cell> melody(int targetColumn, int movingColumn, int row) {
        List<TerminalLogic.Cell> board = background(54);
        put(board, targetColumn, "magenta_stained_glass_pane", "", 1, false);
        for (int r = 1; r <= 3; r++) for (int col = 1; col <= 5; col++) put(board, r * 9 + col, "red_stained_glass_pane", "", 1, false);
        put(board, row * 9 + movingColumn, "lime_stained_glass_pane", "", 1, false);
        for (int button : List.of(16, 25, 34)) put(board, button, "red_terracotta", "", 1, false);
        return board;
    }
    private static List<TerminalLogic.Cell> rectangle(int size, int rows, int columns, String item) {
        List<TerminalLogic.Cell> result = background(size);
        int first = columns == 5 ? 2 : 1;
        for (int row = 1; row <= rows; row++) for (int column = first; column < first + columns; column++) put(result, row * 9 + column, item, item.equals("stone") ? "Stone" : "", 1, false);
        return result;
    }
    private static List<TerminalLogic.Cell> background(int size) {
        List<TerminalLogic.Cell> cells = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) cells.add(new TerminalLogic.Cell(slot, "minecraft:black_stained_glass_pane", "", 1, false));
        return cells;
    }
    private static void put(List<TerminalLogic.Cell> cells, int slot, String item, String name, int count, boolean enchanted) {
        cells.set(slot, new TerminalLogic.Cell(slot, "minecraft:" + item, name, count, enchanted));
    }
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
}
