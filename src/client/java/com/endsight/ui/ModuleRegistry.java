package com.endsight.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Properties;
import java.util.LinkedHashSet;

/**
 * Every module the UI knows about, grouped by category in the order registered.
 *
 * Insertion order rather than alphabetical on purpose: whoever writes the registry
 * is expressing an opinion about what matters most, and sorting throws that away.
 */
public final class ModuleRegistry {

    private final List<Module> modules = new ArrayList<>();
    /** Registered for config and old keybinds, omitted from the browser after grouping. */
    private final Set<String> hidden = new HashSet<>();
    private List<Module> snapshot;
    private List<String> categorySnapshot;
    private List<Module> presentation;
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Set<String> favorites = new LinkedHashSet<>();
    private String browserView;
    public static final String FAVORITES = "@favorites";
    private record Filter(String category, String query, boolean favorites) { }
    private final Map<Filter, Map<String, List<Module>>> filters = new LinkedHashMap<>();

    private void changed() {
        snapshot = null;
        categorySnapshot = null;
        invalidateSearch();
    }

    /** Live command lists and notes can change without registering a different module. */
    public void invalidateSearch() {
        filters.clear();
    }

    public ModuleRegistry add(Module module) {
        modules.add(module);
        changed();
        return this;
    }

    /** Put a settings group where its first old card was, rather than at the category's end. */
    public ModuleRegistry insertBefore(String id, Module module) {
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i).id().equals(id)) {
                modules.add(i, module);
                changed();
                return this;
            }
        }
        return add(module);
    }

    public ModuleRegistry hide(String id) {
        if (hidden.add(id)) changed();
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
                changed();
                return this;
            }
        }
        return add(module);
    }

    /** Take a module out by id. The browser rebuilds its categories from what is left. */
    public ModuleRegistry remove(String id) {
        if (modules.removeIf(m -> m.id().equals(id))) {
            hidden.remove(id);
            changed();
        }
        return this;
    }

    public List<Module> all() {
        if (snapshot == null) snapshot = List.copyOf(modules);
        return snapshot;
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /** Browser descriptors borrow live accessors; all() remains the persistence/keybind authority. */
    public void present(List<Module> entries, Map<String, String> searchAliases, List<String> defaults) {
        presentation = List.copyOf(entries);
        aliases.clear();
        aliases.putAll(searchAliases);
        favorites.addAll(defaults);
        browserView = favorites.isEmpty() ? null : FAVORITES;
        categorySnapshot = null;
        invalidateSearch();
    }

    private List<Module> visible() { return presentation == null ? modules : presentation; }
    private boolean hidden(Module module) { return presentation == null && hidden.contains(module.id()); }
    public boolean favorite(String id) { return favorites.contains(id); }
    public void toggleFavorite(String id) {
        if (!favorites.remove(id)) favorites.add(id);
        invalidateSearch();
    }
    public String browserView() { return browserView; }
    public void browserView(String view) { browserView = view; }

    public void loadBrowserState(Properties properties) {
        String saved = properties.getProperty("browser.favorites");
        if (saved != null) {
            favorites.clear();
            for (String id : saved.split(",")) if (!id.isBlank()) favorites.add(id);
        }
        browserView = properties.getProperty("browser.view", browserView);
        if ("@all".equals(browserView)) browserView = null;
        invalidateSearch();
    }

    public void saveBrowserState(Properties properties) {
        properties.setProperty("browser.favorites", String.join(",", favorites));
        properties.setProperty("browser.view", browserView == null ? "@all" : browserView);
    }

    /** Categories in the order they first appear, so the sidebar needs no config. */
    public List<String> categories() {
        if (categorySnapshot != null) return categorySnapshot;
        List<String> out = new ArrayList<>();
        for (Module m : visible()) {
            if (hidden(m)) continue;
            if (!out.contains(m.category())) out.add(m.category());
        }
        categorySnapshot = List.copyOf(out);
        return categorySnapshot;
    }

    /**
     * Modules grouped for rendering, after a category filter and a search.
     *
     * Returns a map rather than a flat list because the screen draws a header per
     * category, and an empty category must not produce a header with nothing under
     * it - so the filtering has to happen before the grouping, not during layout.
     */
    public Map<String, List<Module>> grouped(String category, String query) {
        return grouped(category, query, false);
    }

    public Map<String, List<Module>> favorites() { return grouped(null, "", true); }

    private Map<String, List<Module>> grouped(String category, String query, boolean favoritesOnly) {
        Filter key = new Filter(category, SettingsSearch.normalize(query), favoritesOnly);
        Map<String, List<Module>> cached = filters.get(key);
        if (cached != null) return cached;
        Map<String, List<Module>> out = new LinkedHashMap<>();
        for (Module m : visible()) {
            if (hidden(m) || favoritesOnly && !favorites.contains(m.id())) continue;
            if (category != null && !category.equals(m.category())) continue;
            if (!m.matches(key.query()) && !SettingsSearch.matches(key.query(), aliases.get(m.id()))) continue;
            out.computeIfAbsent(m.category(), k -> new ArrayList<>()).add(m);
        }
        out.replaceAll((name, group) -> List.copyOf(group));
        Map<String, List<Module>> result = Collections.unmodifiableMap(out);
        // Search boxes produce many short-lived prefixes. Keep the cache bounded,
        // while layout, hit testing and the result count share the same snapshot.
        if (filters.size() >= 12) filters.remove(filters.keySet().iterator().next());
        filters.put(key, result);
        return result;
    }

    public int count(String category, String query) {
        int n = 0;
        for (List<Module> group : grouped(category, query).values()) n += group.size();
        return n;
    }
}
