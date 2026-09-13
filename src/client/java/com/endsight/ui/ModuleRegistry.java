package com.endsight.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every module the UI knows about, grouped by category in the order registered.
 *
 * Insertion order rather than alphabetical on purpose: whoever writes the registry
 * is expressing an opinion about what matters most, and sorting throws that away.
 */
public final class ModuleRegistry {

    private final List<Module> modules = new ArrayList<>();

    public ModuleRegistry add(Module module) {
        modules.add(module);
        return this;
    }

    /**
     * Swap in a module with the same id, keeping its place in the order.
     *
     * A feature that becomes real replaces its placeholder rather than being appended,
     * so the card does not jump to the end of its category the day it starts working.
     */
    public ModuleRegistry replace(Module module) {
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i).id().equals(module.id())) {
                modules.set(i, module);
                return this;
            }
        }
        return add(module);
    }

    /** Take a module out by id. The browser rebuilds its categories from what is left. */
    public ModuleRegistry remove(String id) {
        modules.removeIf(m -> m.id().equals(id));
        return this;
    }

    public List<Module> all() {
        return List.copyOf(modules);
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /** Categories in the order they first appear, so the sidebar needs no config. */
    public List<String> categories() {
        List<String> out = new ArrayList<>();
        for (Module m : modules) {
            if (!out.contains(m.category())) out.add(m.category());
        }
        return out;
    }

    /**
     * Modules grouped for rendering, after a category filter and a search.
     *
     * Returns a map rather than a flat list because the screen draws a header per
     * category, and an empty category must not produce a header with nothing under
     * it - so the filtering has to happen before the grouping, not during layout.
     */
    public Map<String, List<Module>> grouped(String category, String query) {
        Map<String, List<Module>> out = new LinkedHashMap<>();
        for (Module m : modules) {
            if (category != null && !category.equals(m.category())) continue;
            if (!m.matches(query)) continue;
            out.computeIfAbsent(m.category(), k -> new ArrayList<>()).add(m);
        }
        return out;
    }

    public int count(String category, String query) {
        int n = 0;
        for (List<Module> group : grouped(category, query).values()) n += group.size();
        return n;
    }
}
