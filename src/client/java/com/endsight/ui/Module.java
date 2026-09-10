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

    public static Module placeholder(String id, String title, String description, String category,
                                     List<Setting> settings) {
        boolean[] on = {false};
        return new Module(id, title, description, category, () -> on[0], v -> on[0] = v, settings);
    }

    /** Matches a search box query against anything a person would plausibly type. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        return title.toLowerCase().contains(q)
                || description.toLowerCase().contains(q)
                || category.toLowerCase().contains(q);
    }
}
