package com.endsight.ui;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One configurable value, as data.
 *
 * A module describes what it has rather than drawing it, so every settings page in
 * the mod is laid out by the same code and cannot drift apart. That also means a new
 * module gets a page that already looks right without anyone designing one.
 *
 * Sealed on purpose: the settings screen switches over these, so adding a kind is a
 * compile error everywhere it needs handling rather than a control that silently
 * renders as nothing.
 */
public sealed interface Setting {

    String label();

    String description();

    /** On or off. Drawn with the same sliding knob as a module card, for consistency. */
    record Toggle(String label, String description,
                  BooleanSupplier get, Consumer<Boolean> set) implements Setting {
    }

    /**
     * A number in a range.
     *
     * {@code step} is what the value snaps to, and it also decides how the number is
     * printed - a step of 1 shows 250, a step of 0.1 shows 2.5. Deriving that rather
     * than carrying a format string means a slider cannot display more precision than
     * it can actually be set to, which is the usual way these end up lying.
     */
    record Slider(String label, String description,
                  double min, double max, double step,
                  DoubleSupplier get, DoubleConsumer set, String unit) implements Setting {

        public double clamp(double v) {
            double snapped = Math.round(v / step) * step;
            return Math.max(min, Math.min(max, snapped));
        }

        /** 0..1 across the range, for drawing. */
        public double fraction() {
            if (max <= min) return 0;
            return (clamp(get.getAsDouble()) - min) / (max - min);
        }

        public String display() {
            double v = clamp(get.getAsDouble());
            String n = step >= 1 ? String.valueOf((long) v) : trimZeros(v);
            return unit == null || unit.isBlank() ? n : n + unit;
        }

        private static String trimZeros(double v) {
            String s = String.format("%.2f", v);
            while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
            return s;
        }
    }

    /** One of a fixed set, cycled by clicking rather than opening a dropdown. */
    record Choice(String label, String description, List<String> options,
                  Supplier<String> get, Consumer<String> set) implements Setting {

        public void next(int direction) {
            if (options.isEmpty()) return;
            int i = options.indexOf(get.get());
            if (i < 0) i = 0;
            set.accept(options.get(Math.floorMod(i + direction, options.size())));
        }
    }

    /**
     * A colour, as 0xFFRRGGBB, or {@link #CHROMA} for one that runs through every hue.
     * Drawn as a swatch with its hex; clicking it opens a picker under the row.
     */
    record Color(String label, String description, IntSupplier get, IntConsumer set) implements Setting {

        /** No real colour is transparent black, so it is free to mean "all of them". */
        public static final int CHROMA = 0;

        /** The colour to draw with this frame: the value, or this moment's hue for chroma. */
        public static int live(int value) {
            if (value != CHROMA) return value;
            return 0xFF000000 | (Mth.hsvToRgb((System.currentTimeMillis() % 4000) / 4000f, 0.8f, 1f) & 0xFFFFFF);
        }

        public static String hex(int value) {
            return value == CHROMA ? "chroma" : String.format("#%06X", value & 0xFFFFFF);
        }

        /** "#RRGGBB" or "chroma" back to a value, or -1 for anything else. */
        public static int parse(String s) {
            if (s == null) return -1;
            s = s.trim();
            if (s.equalsIgnoreCase("chroma")) return CHROMA;
            if (s.startsWith("#")) s = s.substring(1);
            if (s.length() != 6) return -1;
            try {
                return 0xFF000000 | Integer.parseInt(s, 16);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /**
     * A thing that happens when clicked, rather than a value that is held.
     *
     * Added because placing the storage preview is a verb, and every other kind here
     * is a noun. Doing it with a Toggle meant "on" was a mode you had to switch off
     * again from the same page you had to leave to use it - flip, close, drag, reopen,
     * flip back. A button just does the thing and hands the screen back when it is done.
     *
     * @param button the word on the button, which is not always the label - "Reposition
     *               preview" is what the row is about, "Move" is what the button does.
     */
    record Action(String label, String description, String button, Runnable run) implements Setting {
    }

    /**
     * A verb with its own key chip.
     *
     * Module keys toggle the module itself. Some modules also have small, immediate
     * verbs - use this item, mark this spot - where a whole module per key would make
     * the browser worse. The id is the stable keybind id saved under bind.<id>.
     */
    record KeyAction(String id, String label, String description, String button, Runnable run) implements Setting {
    }

    /**
     * Things the player owns, as pictures with a key each, plus keys that flip between
     * two of them.
     *
     * Pets and outfits were Choice and KeyAction rows: a row to cycle a name, another for
     * its key, nine "Slot N" rows after that. You could not see what you were choosing,
     * and choosing and binding were two jobs. On a tile the picture is the choice and the
     * key sits on it, so there is one thing to do.
     *
     * @param noun    what one tile is, for the words around the grid
     * @param swaps   never empty: the first is always offered
     * @param addSwap adds an empty swap, up to {@link #MAX_SWAPS}
     * @param load    fetches the list from the server's own menu. Nothing else knows it.
     */
    record Loadout(String label, String noun, Supplier<List<Tile>> tiles, Supplier<List<Swap>> swaps,
                   Runnable addSwap, Runnable load) implements Setting {
        /** A few setups. Past this the swaps would push the pets themselves off the page. */
        public static final int MAX_SWAPS = 4;

        public Swap swap() {
            return swaps.get().getFirst();
        }

        @Override
        public String description() {
            return "";
        }

        /** Run whatever was just pressed. */
        public void fire(Predicate<String> pressed) {
            for (Swap swap : swaps.get()) if (pressed.test(swap.key())) swap.run().run();
            for (Tile tile : tiles.get()) if (pressed.test(tile.id())) tile.run().run();
        }
    }

    /**
     * One pet or outfit.
     *
     * @param id    its keybind id, and how the swap names it
     * @param color the name's colour, or 0 for the theme's text
     * @param icons one head or four armour pieces, exactly what the server showed, so a
     *              skinned pet is simply its skin. Empty stacks draw as empty slots.
     */
    record Tile(String id, String name, int color, String detail, List<ItemStack> icons,
                boolean current, Runnable run) {
    }

    /**
     * The two a single key flips between.
     *
     * @param chosen the two tile ids, "" for an empty side
     * @param choose side (0 or 1), then the tile id
     */
    record Swap(String key, Supplier<List<String>> chosen, BiConsumer<Integer, String> choose, Runnable run, Runnable remove) {
    }

    /**
     * Every key in the mod on one page. A listing of keys that belong to other settings, so
     * it holds no values of its own and fires nothing; each key still works from where it lives.
     */
    record Binds(String label, Supplier<List<Bind>> binds) implements Setting {
        @Override
        public String description() {
            return "";
        }
    }

    /**
     * One key on that page.
     *
     * @param where the page it belongs to, or "" when that would only repeat the name
     * @param group the sidebar category it is listed under
     */
    record Bind(String id, String name, String where, String group) {
    }

    /** Not a control - a heading that breaks a long page into groups. */
    record Section(String label) implements Setting {
        @Override
        public String description() {
            return "";
        }
    }

    /**
     * Not a control - a heading and a paragraph, wrapped to as many lines as it needs.
     * For telling the reader something a one-line description cannot, like what a
     * choice on the same page actually contains. The text is supplied live, so a list
     * that changes on disk reads right without reopening the page.
     */
    record Note(String label, java.util.function.Supplier<String> text) implements Setting {
        @Override
        public String description() {
            return text.get();
        }
    }

    /**
     * A command with a key: a text field you type the command into, the key chip
     * beside it, and a remove button. The only setting that is a text field, and the
     * only one with its own key - both because a command bind is nothing without them.
     * {@code id} is what the key is bound under and what the config saves it as.
     */
    final class Command implements Setting {
        private final String id;
        private String command;
        private final Runnable remove;

        public Command(String id, String command, Runnable remove) {
            this.id = id;
            this.command = command;
            this.remove = remove;
        }

        public String id() {
            return id;
        }

        public String command() {
            return command;
        }

        public void command(String value) {
            command = value;
        }

        public Runnable remove() {
            return remove;
        }

        @Override
        public String label() {
            return id;
        }

        @Override
        public String description() {
            return "";
        }
    }

    // ── convenience builders for settings backed by nothing, for layout work ──

    static Setting.Toggle demoToggle(String label, String description, boolean initial) {
        boolean[] v = {initial};
        return new Toggle(label, description, () -> v[0], x -> v[0] = x);
    }

    static Setting.Slider demoSlider(String label, String description,
                                     double min, double max, double step,
                                     double initial, String unit) {
        double[] v = {initial};
        return new Slider(label, description, min, max, step, () -> v[0], x -> v[0] = x, unit);
    }

    static Setting.Choice demoChoice(String label, String description,
                                     List<String> options, String initial) {
        String[] v = {initial};
        return new Choice(label, description, options, () -> v[0], x -> v[0] = x);
    }
}
