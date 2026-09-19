package com.endsight.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One entry in the module list, as data.
 *
 * The screen never asks what a module DOES - it asks whether it is on and tells it
 * to change. That indirection is the whole point: this package has to drop into a
 * mod whose modules are dragon timers rather than bazaar flippers, and it can only
 * do that if it never learns the difference.
 *
 * @param category free text, not an enum. Adding a category means writing a new
 *                 string on a module - the sidebar builds itself from whatever
 *                 categories are actually present, so there is no second place to
 *                 update and no way for the two to disagree.
 * @param settings what this module lets you change, as data. The UI lays them out
 *                 itself rather than each module drawing its own page, so every
 *                 settings screen matches and a new module gets one for free.
 *                 Empty means the card draws no gear.
 */
public record Module(String id,
                     String title,
                     String description,
                     String category,
                     BooleanSupplier enabled,
                     Consumer<Boolean> setEnabled,
                     List<Setting> settings) {

    public boolean isEnabled() {
        return enabled != null && enabled.getAsBoolean();
    }

    public void toggle() {
        if (setEnabled != null) setEnabled.accept(!isEnabled());
    }

    /**
     * Whether this module actually does anything yet.
     *
     * A module with no setter cannot be switched on, which is already the truth about a
     * feature that has not been written - so the UI reads that rather than carrying a
     * separate flag that could disagree with it. A card people can toggle, that then
     * does nothing, is worse than one that says so.
     */
    public boolean implemented() {
        return setEnabled != null;
    }

    public boolean hasSettings() {
        return settings != null && !settings.isEmpty();
    }

    /**
     * A module that owns its own on/off flag and is wired to nothing.
     *
     * For laying out and demoing a UI before the features behind it exist - which
     * is the normal order, since the menu is how you find out the layout was wrong.
     */
    public static Module placeholder(String id, String title, String description, String category) {
        return placeholder(id, title, description, category, List.of());
    }

    /**
     * A module that is planned but not written.
     *
     * Deliberately given no accessors at all, so it cannot be switched on by the UI, by
     * a saved config, or by anything else that gets hold of it. The greying out is then
     * a description of the module rather than a rule the UI has to remember to apply.
     */
    public static Module unimplemented(String id, String title, String description, String category) {
        return new Module(id, title, description, category, null, null, List.of());
    }

    public static Module placeholder(String id, String title, String description, String category,
                                     List<Setting> settings) {
        boolean[] on = {false};
        return new Module(id, title, description, category, () -> on[0], v -> on[0] = v, settings);
    }

    /** Matches a search box query against anything a person would plausibly type. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        if (contains(title, q) || contains(description, q) || contains(category, q)) return true;
        if (settings == null) return false;
        for (Setting s : settings) {
            if (contains(s.label(), q) || contains(s.description(), q)) return true;
            if (s instanceof Setting.Choice c) {
                for (String option : c.options()) if (contains(option, q)) return true;
            } else if (s instanceof Setting.Action a) {
                if (contains(a.button(), q)) return true;
            } else if (s instanceof Setting.KeyAction a) {
                if (contains(a.button(), q)) return true;
            } else if (s instanceof Setting.Command c) {
                if (contains(c.command(), q)) return true;
            }
        }
        return false;
    }

    private static boolean contains(String text, String query) {
        return text != null && text.toLowerCase().contains(query);
    }
}
