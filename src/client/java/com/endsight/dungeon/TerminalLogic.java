package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure suggestions for recognized terminal menus. Native slot clicks remain the
 * player's job; this class has no inventory packets or interaction predictions. */
public final class TerminalLogic {
    public enum Type { ORDER, PANES, STARTS_WITH, COLORS, RUBIX, MELODY, UNKNOWN }
    public enum Button { LEFT, RIGHT }
    public record Cell(int slot, String itemId, String name, int count, boolean enchanted) { }
    public record Hint(int slot, int clicks, Button button) { }
    public record Plan(Type type, List<Hint> hints, String status, boolean ready) {
        public Plan { hints = List.copyOf(hints); }
        public Plan(Type type, List<Hint> hints, String status) { this(type, hints, status, type != Type.UNKNOWN); }
    }

    private static final Pattern STARTS = Pattern.compile("^What starts with: '([A-Za-z0-9_])'\\?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLORS = Pattern.compile("^Select all the ([A-Za-z ]+) items!$", Pattern.CASE_INSENSITIVE);
    private static final List<String> CYCLE = List.of("red", "orange", "yellow", "green", "blue");
    private static final List<Integer> RUBIX_SLOTS = List.of(12, 13, 14, 21, 22, 23, 30, 31, 32);
    // Noamm's exception is default item glint components plus Golden Apple. These
    // seven default-component items were also verified against 26.1.2 Items bytecode.
    private static final Set<String> NATURAL_GLINT = Set.of("enchanted_golden_apple", "experience_bottle", "written_book",
            "nether_star", "enchanted_book", "end_crystal", "debug_stick", "golden_apple");
    private static final Map<String, List<String>> COLOR_PREFIXES = Map.ofEntries(
            Map.entry("white", List.of("white", "bone", "wool")), Map.entry("orange", List.of("orange")),
            Map.entry("magenta", List.of("magenta")), Map.entry("light blue", List.of("light blue")),
            Map.entry("yellow", List.of("yellow", "dandelion")), Map.entry("lime", List.of("lime")),
            Map.entry("pink", List.of("pink")), Map.entry("gray", List.of("gray")),
            Map.entry("light gray", List.of("light gray", "silver")), Map.entry("cyan", List.of("cyan")),
            Map.entry("purple", List.of("purple")), Map.entry("blue", List.of("blue", "lapis")),
            Map.entry("brown", List.of("brown", "cocoa")), Map.entry("green", List.of("green", "cactus")),
            Map.entry("red", List.of("red", "rose")), Map.entry("black", List.of("black", "ink")));

    private TerminalLogic() { }

    public static Plan solve(String title, int containerSlots, List<Cell> cells) {
        return solve(title, containerSlots, cells, Set.of());
    }

    /** confirmedSelected contains only native clicks acknowledged by a server slot
     * update, needed where an item's natural glint cannot show selection progress. */
    public static Plan solve(String rawTitle, int containerSlots, List<Cell> cells, Set<Integer> confirmedSelected) {
        String title = plain(rawTitle);
        Type type = type(title);
        if (type == Type.UNKNOWN) return empty(type, "Unrecognized terminal");
        int required = switch (type) { case ORDER -> 36; case COLORS, MELODY -> 54; default -> 45; };
        if (containerSlots != required) return empty(type, "Unrecognized menu size");
        Map<Integer, Cell> board = snapshot(containerSlots, cells);
        if (board == null) return empty(type, "Waiting for complete menu");
        Set<Integer> selected = confirmedSelected == null ? Set.of() : confirmedSelected;
        return switch (type) {
            case ORDER -> order(board);
            case PANES -> panes(board);
            case STARTS_WITH -> matching(type, title, board, selected);
            case COLORS -> matching(type, title, board, selected);
            case RUBIX -> rubix(board);
            case MELODY -> melody(board);
            default -> empty(Type.UNKNOWN, "Unrecognized terminal");
        };
    }

    public static Type type(String rawTitle) {
        String title = plain(rawTitle);
        if (title.equals("Click in order!")) return Type.ORDER;
        if (title.equals("Correct all the panes!")) return Type.PANES;
        if (STARTS.matcher(title).matches()) return Type.STARTS_WITH;
        if (COLORS.matcher(title).matches()) return Type.COLORS;
        if (title.equals("Change all to same color!")) return Type.RUBIX;
        if (title.equals("Click the button on time!")) return Type.MELODY;
        return Type.UNKNOWN;
    }

    private static Map<Integer, Cell> snapshot(int size, List<Cell> cells) {
        if (cells == null || cells.size() != size) return null;
        Map<Integer, Cell> result = new HashMap<>();
        for (Cell cell : cells) {
            if (cell == null || cell.slot() < 0 || cell.slot() >= size || cell.count() < 0
                    || cell.itemId() == null || cell.itemId().isBlank()
                    || ((cell.count() == 0) != id(cell).equals("air"))
                    || result.put(cell.slot(), cell) != null) return null;
        }
        return result;
    }

    private static Plan order(Map<Integer, Cell> board) {
        List<Cell> tiles = board.values().stream().filter(cell -> red(cell) || lime(cell)).toList();
        if (!rectangle(tiles, 2, 5) && !rectangle(tiles, 2, 7)) return empty(Type.ORDER, "Waiting for numbered panes");
        Set<Integer> numbers = new HashSet<>();
        for (Cell cell : tiles) {
            if (cell.count() < 1 || cell.count() > tiles.size() || !numbers.add(cell.count())) return empty(Type.ORDER, "Waiting for complete numbers");
            String name = plain(cell.name());
            if (name.matches("\\d+") && !name.equals(Integer.toString(cell.count()))) return empty(Type.ORDER, "Waiting for matching numbers");
        }
        int done = (int) tiles.stream().filter(TerminalLogic::lime).count();
        // A lime pane after an unfinished lower number is a partial menu update,
        // rather than permission to click a later item out of order.
        for (Cell cell : tiles) if (lime(cell) != (cell.count() <= done)) return empty(Type.ORDER, "Waiting for number update");
        List<Cell> remaining = tiles.stream().filter(TerminalLogic::red).sorted(Comparator.comparingInt(Cell::count)).toList();
        List<Hint> hints = remaining.stream().map(cell -> new Hint(cell.slot(), 1, Button.LEFT)).toList();
        return new Plan(Type.ORDER, hints, hints.isEmpty() ? "Complete" : "Next: " + remaining.getFirst().count());
    }

    private static Plan panes(Map<Integer, Cell> board) {
        List<Cell> tiles = board.values().stream().filter(cell -> red(cell) || lime(cell)).toList();
        if (!rectangle(tiles, 3, 5) && !rectangle(tiles, 3, 7)) return empty(Type.PANES, "Waiting for complete panes");
        if (tiles.stream().anyMatch(cell -> cell.count() != 1)) return empty(Type.PANES, "Unrecognized pane count");
        List<Hint> hints = tiles.stream().filter(TerminalLogic::red).sorted(Comparator.comparingInt(Cell::slot))
                .map(cell -> new Hint(cell.slot(), 1, Button.LEFT)).toList();
        return new Plan(Type.PANES, hints, hints.isEmpty() ? "Complete" : "Make red panes green");
    }

    private static Plan matching(Type type, String title, Map<Integer, Cell> board, Set<Integer> selected) {
        int rows = type == Type.COLORS ? 4 : 3;
        List<Cell> tiles = interior(board, rows, 7);
        if (tiles.stream().anyMatch(cell -> id(cell).equals("air") || cell.count() < 1 || (!padding(cell) && plain(cell.name()).isBlank())))
            return empty(type, "Waiting for item names");
        String initial = null;
        List<String> prefixes = null;
        if (type == Type.STARTS_WITH) {
            Matcher m = STARTS.matcher(title); m.matches();
            initial = m.group(1).toLowerCase(Locale.ROOT);
        } else {
            Matcher m = COLORS.matcher(title); m.matches();
            String color = m.group(1).toLowerCase(Locale.ROOT);
            if (color.equals("silver")) color = "light gray";
            prefixes = COLOR_PREFIXES.get(color);
            if (prefixes == null) return empty(type, "Unrecognized color");
        }
        List<Hint> hints = new ArrayList<>();
        for (Cell cell : tiles) {
            if (padding(cell) || selected.contains(cell.slot())) continue;
            if (cell.enchanted() && (type == Type.COLORS || !NATURAL_GLINT.contains(id(cell)))) continue;
            String name = plain(cell.name()).toLowerCase(Locale.ROOT);
            boolean match = type == Type.STARTS_WITH ? name.startsWith(initial) : prefixes.stream().anyMatch(name::startsWith);
            if (match) hints.add(new Hint(cell.slot(), 1, Button.LEFT));
        }
        return new Plan(type, hints, hints.isEmpty() ? "No remaining matches" : type == Type.STARTS_WITH ? "Select matching initials" : "Select matching colors");
    }

    private static Plan rubix(Map<Integer, Cell> board) {
        int[] colors = new int[9];
        for (int i = 0; i < RUBIX_SLOTS.size(); i++) {
            Cell cell = board.get(RUBIX_SLOTS.get(i));
            String item = id(cell);
            colors[i] = -1;
            for (int c = 0; c < CYCLE.size(); c++) if (item.equals(CYCLE.get(c) + "_stained_glass_pane")) colors[i] = c;
            if (colors[i] < 0 || cell.count() != 1) return empty(Type.RUBIX, "Waiting for all nine colors");
        }
        int target = 0, bestCost = Integer.MAX_VALUE;
        for (int c = 0; c < 5; c++) {
            int cost = 0;
            for (int current : colors) cost += Math.abs(rotation(current, c));
            if (cost < bestCost) { bestCost = cost; target = c; }
        }
        List<Hint> hints = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int amount = rotation(colors[i], target);
            if (amount != 0) hints.add(new Hint(RUBIX_SLOTS.get(i), Math.abs(amount), amount > 0 ? Button.LEFT : Button.RIGHT));
        }
        return new Plan(Type.RUBIX, hints, hints.isEmpty() ? "Complete" : "Make all panes " + CYCLE.get(target));
    }

    private static int rotation(int from, int to) {
        int forward = Math.floorMod(to - from, 5);
        return forward <= 2 ? forward : forward - 5;
    }

    private static Plan melody(Map<Integer, Cell> board) {
        List<Cell> targets = board.values().stream().filter(cell -> id(cell).equals("magenta_stained_glass_pane")).toList();
        List<Cell> movers = board.values().stream().filter(TerminalLogic::lime).toList();
        if (targets.size() != 1 || movers.size() != 1) return empty(Type.MELODY, "Waiting for unique markers");
        Cell target = targets.getFirst(), mover = movers.getFirst();
        int row = mover.slot() / 9, column = mover.slot() % 9;
        if (target.slot() < 1 || target.slot() > 5 || row < 1 || row > 3 || column < 1 || column > 5)
            return empty(Type.MELODY, "Unrecognized marker layout");
        for (int trackRow = 1; trackRow <= 3; trackRow++) for (int trackColumn = 1; trackColumn <= 5; trackColumn++) {
            Cell cell = board.get(trackRow * 9 + trackColumn);
            if (!id(cell).endsWith("_stained_glass_pane") || cell.count() != 1) return empty(Type.MELODY, "Waiting for complete tracks");
        }
        for (int button : List.of(16, 25, 34)) {
            Cell cell = board.get(button);
            if (!Set.of("red_terracotta", "lime_terracotta", "green_terracotta").contains(id(cell)) || cell.count() != 1)
                return empty(Type.MELODY, "Unrecognized buttons");
        }
        if (column != target.slot()) return new Plan(Type.MELODY, List.of(), "Wait for alignment", true);
        return new Plan(Type.MELODY, List.of(new Hint(row * 9 + 7, 1, Button.LEFT)), "Aligned: click now");
    }

    private static List<Cell> interior(Map<Integer, Cell> board, int rows, int columns) {
        List<Cell> result = new ArrayList<>();
        int first = columns == 5 ? 2 : 1;
        for (int row = 1; row <= rows; row++) for (int column = first; column < first + columns; column++) result.add(board.get(row * 9 + column));
        return result;
    }

    private static boolean rectangle(List<Cell> cells, int rows, int columns) {
        if (cells.size() != rows * columns) return false;
        int first = columns == 5 ? 2 : 1;
        for (Cell cell : cells) {
            int row = cell.slot() / 9, column = cell.slot() % 9;
            if (row < 1 || row > rows || column < first || column >= first + columns) return false;
        }
        return true;
    }

    private static boolean red(Cell cell) { return id(cell).equals("red_stained_glass_pane"); }
    private static boolean lime(Cell cell) { return id(cell).equals("lime_stained_glass_pane"); }
    private static boolean padding(Cell cell) { return id(cell).equals("black_stained_glass_pane"); }
    private static String id(Cell cell) {
        String id = cell.itemId().toLowerCase(Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
    private static Plan empty(Type type, String status) { return new Plan(type, List.of(), status, false); }
    private static String plain(String raw) {
        return raw == null ? "" : raw.replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "").replace('\u2018', '\'').replace('\u2019', '\'').trim();
    }
}
