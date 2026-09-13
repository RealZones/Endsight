package com.endsight.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
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
