package com.endsight.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Browser snapshots and collapsed settings must agree without bootstrapping Minecraft. */
public final class CompactModuleChecks {
    private static int checks;

    public static void main(String[] args) {
        registrySnapshots();
        registryMutation();
        groupPresentation();
        liveSearch();
        moduleSearch();
        settingsOutline();
        duplicateHeadings();
        rowTargets();
        System.out.println(checks + " compact module checks passed");
    }

    private static void registrySnapshots() {
        ModuleRegistry registry = new ModuleRegistry();
        yes(registry.isEmpty(), "new registry is not empty");
        equal(registry.all(), List.of());
        same(registry.all(), registry.all(), "empty module snapshot rebuilt");
        same(registry.categories(), registry.categories(), "empty category snapshot rebuilt");
        Map<String, List<Module>> empty = registry.grouped(null, null);
        same(empty, registry.grouped(null, " \t "), "blank filters did not share a snapshot");

        Module alpha = module("alpha", "Alpha", "Dragons");
        Module beta = module("beta", "Beta", "Dungeons");
        Module gamma = module("gamma", "Gamma", "Dragons");
        registry.add(alpha).add(beta).add(gamma);
        List<Module> all = registry.all();
        List<String> categories = registry.categories();
        Map<String, List<Module>> groups = registry.grouped(null, "");
        yes(!registry.isEmpty(), "populated registry is empty");
        equal(empty, Map.of());
        equal(all, List.of(alpha, beta, gamma));
        equal(categories, List.of("Dragons", "Dungeons"));
        equal(List.copyOf(groups.keySet()), categories);
        equal(groups.get("Dragons"), List.of(alpha, gamma));
        equal(groups.get("Dungeons"), List.of(beta));
        same(all, registry.all(), "unchanged module snapshot rebuilt");
        same(categories, registry.categories(), "unchanged category snapshot rebuilt");
        same(groups, registry.grouped(null, null), "unchanged grouped snapshot rebuilt");
        equal(registry.count(null, ""), 3);
        same(groups, registry.grouped(null, ""), "count invalidated the grouped snapshot");
        Map<String, List<Module>> filtered = registry.grouped(null, " ALPHA \t dragons ");
        equal(filtered, Map.of("Dragons", List.of(alpha)));
        same(filtered, registry.grouped(null, "alpha dragons"), "normalized query rebuilt its snapshot");
        equal(registry.grouped("Dungeons", ""), Map.of("Dungeons", List.of(beta)));
        equal(registry.grouped("Dungeons", "alpha"), Map.of());
        equal(registry.grouped("Missing", ""), Map.of());
        unsupported(() -> all.add(alpha), "module snapshot is mutable");
        unsupported(() -> categories.add("Extra"), "category snapshot is mutable");
        unsupported(() -> groups.put("Extra", List.of(alpha)), "group map is mutable");
        unsupported(() -> groups.get("Dragons").clear(), "group members are mutable");
        unsupported(() -> groups.entrySet().iterator().next().setValue(List.of()), "group entry is mutable");

        registry.invalidateSearch();
        same(all, registry.all(), "search invalidation rebuilt module membership");
        same(categories, registry.categories(), "search invalidation rebuilt categories");
        Map<String, List<Module>> refreshed = registry.grouped(null, "");
        different(groups, refreshed, "search invalidation retained the grouped cache");
        equal(refreshed, groups);
    }

    private static void registryMutation() {
        Module alpha = module("alpha", "Alpha", "Dragons");
        Module beta = module("beta", "Beta", "Dungeons");
        Module gamma = module("gamma", "Gamma", "Dragons");
        Module delta = module("delta", "Delta", "Alchemy");
        ModuleRegistry registry = new ModuleRegistry().add(alpha).add(beta).add(gamma);
        List<Module> beforeAdd = registry.all();
        List<String> categoriesBeforeAdd = registry.categories();
        Map<String, List<Module>> groupsBeforeAdd = registry.grouped(null, "");
        registry.add(delta);
        different(beforeAdd, registry.all(), "add retained module snapshot");
        different(categoriesBeforeAdd, registry.categories(), "add retained category snapshot");
        different(groupsBeforeAdd, registry.grouped(null, ""), "add retained grouped snapshot");
        equal(beforeAdd, List.of(alpha, beta, gamma));
        equal(categoriesBeforeAdd, List.of("Dragons", "Dungeons"));
        equal(groupsBeforeAdd.get("Dragons"), List.of(alpha, gamma));
        equal(registry.all(), List.of(alpha, beta, gamma, delta));
        equal(registry.categories(), List.of("Dragons", "Dungeons", "Alchemy"));

        List<Module> beforeReplace = registry.all();
        Map<String, List<Module>> groupsBeforeReplace = registry.grouped(null, "");
        Module replacement = module("beta", "Replaced", "Dragons");
        registry.replace(replacement);
        equal(registry.all(), List.of(alpha, replacement, gamma, delta));
        equal(registry.categories(), List.of("Dragons", "Alchemy"));
        equal(registry.grouped(null, "").get("Dragons"), List.of(alpha, replacement, gamma));
        equal(registry.grouped(null, "beta"), Map.of());
        equal(registry.grouped(null, "replaced"), Map.of("Dragons", List.of(replacement)));
        different(beforeReplace, registry.all(), "replace retained module snapshot");
        different(groupsBeforeReplace, registry.grouped(null, ""), "replace retained grouped snapshot");
        equal(beforeReplace, List.of(alpha, beta, gamma, delta));
        equal(groupsBeforeReplace.get("Dungeons"), List.of(beta));

        Module appended = module("appended", "Appended", "Mining");
        registry.replace(appended);
        equal(registry.all(), List.of(alpha, replacement, gamma, delta, appended));
        equal(registry.categories(), List.of("Dragons", "Alchemy", "Mining"));
        List<Module> beforeRemove = registry.all();
        List<String> categoriesBeforeRemove = registry.categories();
        Map<String, List<Module>> groupsBeforeRemove = registry.grouped(null, "");
        registry.remove("delta");
        equal(registry.all(), List.of(alpha, replacement, gamma, appended));
        equal(registry.categories(), List.of("Dragons", "Mining"));
        different(beforeRemove, registry.all(), "remove retained module snapshot");
        different(categoriesBeforeRemove, registry.categories(), "remove retained category snapshot");
        different(groupsBeforeRemove, registry.grouped(null, ""), "remove retained grouped snapshot");
        equal(beforeRemove, List.of(alpha, replacement, gamma, delta, appended));
        equal(categoriesBeforeRemove, List.of("Dragons", "Alchemy", "Mining"));
        equal(groupsBeforeRemove.get("Alchemy"), List.of(delta));
        List<Module> unchanged = registry.all();
        List<String> unchangedCategories = registry.categories();
        Map<String, List<Module>> unchangedGroups = registry.grouped(null, "");
        registry.remove("absent");
        same(unchanged, registry.all(), "absent removal rebuilt modules");
        same(unchangedCategories, registry.categories(), "absent removal rebuilt categories");
        same(unchangedGroups, registry.grouped(null, ""), "absent removal rebuilt groups");
    }

    private static void groupPresentation() {
        Module old = module("old", "Old control", "Chat");
        Module other = module("other", "Other control", "Visual");
        Module group = Module.group("group", "Combined", "Controls for old features", "Chat",
                List.of(Setting.demoToggle("Old control", "Preserved behavior", true)));
        ModuleRegistry registry = new ModuleRegistry().add(old).add(other);
        registry.insertBefore("old", group).hide("old");
        yes(group.implemented() && group.group() && !group.togglable() && group.isEnabled(),
                "settings group should open without a master toggle");
        equal(registry.all(), List.of(group, old, other));
        equal(registry.categories(), List.of("Chat", "Visual"));
        equal(registry.grouped(null, ""), Map.of("Chat", List.of(group), "Visual", List.of(other)));
        equal(registry.count(null, ""), 2);
        equal(registry.grouped(null, "old control"), Map.of("Chat", List.of(group)));
        registry.replace(module("old", "Updated control", "Chat"));
        equal(registry.count(null, ""), 2); // replacing a hidden module must not reveal it
        registry.remove("old").add(old);
        equal(registry.count(null, ""), 3); // removing it clears the hidden id
    }

    private static void liveSearch() {
        Setting.Command command = new Setting.Command("travel", "/warp home", () -> { });
        String[] advice = {"Amber routes"};
        List<Setting> settings = new ArrayList<>(List.of(command, new Setting.Note("Advice", () -> advice[0])));
        Module module = Module.placeholder("binds", "Command binds", "Travel helpers", "Utility", settings);
        ModuleRegistry registry = new ModuleRegistry().add(module);
        List<Module> all = registry.all();
        List<String> categories = registry.categories();
        Map<String, List<Module>> beforeText = registry.grouped(null, "forge");
        equal(beforeText, Map.of());
        command.command("/warp forge");
        yes(module.matches("forge"), "module search ignored edited command text");
        registry.invalidateSearch();
        Map<String, List<Module>> forge = registry.grouped(null, "forge");
        equal(forge, Map.of("Utility", List.of(module)));
        equal(beforeText, Map.of());
        different(beforeText, forge, "command edit retained filtered snapshot");

        Map<String, List<Module>> beforeList = registry.grouped(null, "auctions");
        Setting.Command added = new Setting.Command("market", "/view auctions", () -> { });
        settings.add(added);
        yes(module.matches("auctions"), "module search ignored live command list addition");
        registry.invalidateSearch();
        Map<String, List<Module>> auctions = registry.grouped(null, "auctions");
        equal(auctions, Map.of("Utility", List.of(module)));
        equal(beforeList, Map.of());
        settings.remove(added);
        registry.invalidateSearch();
        equal(registry.grouped(null, "auctions"), Map.of());
        equal(auctions, Map.of("Utility", List.of(module)));

        Map<String, List<Module>> beforeNote = registry.grouped(null, "sapphire");
        advice[0] = "Sapphire routes";
        yes(module.matches("sapphire"), "module search ignored live note description");
        registry.invalidateSearch();
        equal(registry.grouped(null, "sapphire"), Map.of("Utility", List.of(module)));
        equal(beforeNote, Map.of());
        command.command("/warp crypt");
        registry.invalidateSearch();
        equal(registry.grouped(null, "forge"), Map.of());
        equal(registry.grouped(null, "crypt"), Map.of("Utility", List.of(module)));
        equal(forge, Map.of("Utility", List.of(module)));
        same(all, registry.all(), "live settings edit rebuilt module membership");
        same(categories, registry.categories(), "live settings edit rebuilt categories");
    }

    private static void moduleSearch() {
        List<Setting> settings = settings();
        Module module = Module.placeholder("overlay", "INVENTORY Overlay", "Frame timing", "Dungeons", settings);
        equal(settings.stream().map(Object::getClass).collect(java.util.stream.Collectors.toSet()),
                Set.of(Setting.class.getPermittedSubclasses()));
        yes(module.matches(null), "null query hid module");
        yes(module.matches(" \t\n "), "blank query hid module");
        yes(module.matches("inventory timing dungeons celestial reposition forge navigation"),
                "tokens did not combine metadata and setting text");
        yes(!module.matches("inventory celestial missing-token"), "multiword search accepted a missing token");
        for (String query : List.of("outline armor", "opacity tint", "mode selection celestial", "stroke hue",
                "placement reposition", "advice navigation", "travel forge"))
            yes(module.matches(query), "setting text is not searchable: " + query);
        // Keybind rows and headings repeat on nearly every page, so the browser skips them.
        for (String query : List.of("utility teleport", "basic"))
            yes(!module.matches(query), "keybind row or heading matched a browser search: " + query);

        Locale previous = Locale.getDefault();
        try {
            // Turkish casing turns an ASCII I into a different letter with the default locale.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            yes(module.matches("inventory"), "module text casing used the default locale");
            yes(module("lower", "inventory", "Utility").matches("INVENTORY"),
                    "query casing used the default locale");
            yes(module.matches("  INVENTORY \t DUNGEONS\n Celestial REPOSITION FORGE navigation  "),
                    "case or whitespace changed mixed-field search");
            ModuleRegistry registry = new ModuleRegistry().add(module);
            same(registry.grouped(null, "INVENTORY"), registry.grouped(null, "inventory"),
                    "filter cache normalization used the default locale");
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static void settingsOutline() {
        List<Setting> settings = settings();
        Set<Integer> collapsed = SettingsOutline.initiallyCollapsed(settings);
        equal(collapsed, Set.of(10));
        List<SettingsOutline.Entry> normal = SettingsOutline.visible(settings, "", collapsed);
        equal(indices(normal), List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        equal(entry(normal, 1), new SettingsOutline.Entry(settings.get(1), 1, true, false, 8));
        equal(entry(normal, 10), new SettingsOutline.Entry(settings.get(10), 10, false, false, 1));
        List<SettingsOutline.Entry> open = SettingsOutline.visible(settings, "", Set.of());
        equal(open.stream().map(SettingsOutline.Entry::setting).toList(), settings);
        equal(indices(open), List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));
        for (SettingsOutline.Entry visible : open)
            same(visible.setting(), settings.get(visible.index()), "outline replaced or reordered a setting");
        unsupported(() -> normal.clear(), "visible outline is mutable");
        equal(indices(SettingsOutline.visible(settings, "", Set.of(1, 10))), List.of(0, 1, 10));

        equal(SettingsOutline.visible(settings, "rare", collapsed), List.of(
                new SettingsOutline.Entry(settings.get(10), 10, true, false, 1),
                new SettingsOutline.Entry(settings.get(11), 11, false, true, 0)));
        equal(SettingsOutline.visible(settings, "advanced", collapsed), List.of(
                new SettingsOutline.Entry(settings.get(10), 10, true, true, 1),
                new SettingsOutline.Entry(settings.get(11), 11, false, false, 0)));
        equal(indices(SettingsOutline.visible(settings, "outline armor", collapsed)), List.of(1, 2));
        equal(indices(SettingsOutline.visible(settings, "celestial", collapsed)), List.of(1, 4));
        equal(indices(SettingsOutline.visible(settings, "reposition", collapsed)), List.of(1, 6));
        equal(indices(SettingsOutline.visible(settings, "teleport", collapsed)), List.of(1, 7));
        equal(indices(SettingsOutline.visible(settings, "forge", collapsed)), List.of(1, 8));
        equal(indices(SettingsOutline.visible(settings, "navigation", collapsed)), List.of(1, 9));
        equal(indices(SettingsOutline.visible(settings, "overview", collapsed)), List.of(0));
        equal(collapsed, Set.of(10));
        equal(SettingsOutline.visible(settings, "module-metadata-only", collapsed), normal);
        equal(SettingsOutline.visible(settings, null, collapsed), normal);
        equal(SettingsOutline.visible(settings, " \t\n ", collapsed), normal);
        equal(SettingsOutline.visible(settings, "", collapsed), normal);
        collapsed.add(1);
        equal(indices(SettingsOutline.visible(settings, "rare", collapsed)), List.of(10, 11));
        equal(indices(SettingsOutline.visible(settings, "", collapsed)), List.of(0, 1, 10));
        equal(collapsed, Set.of(1, 10));
        equal(SettingsOutline.initiallyCollapsed(List.of()), Set.of());
        equal(SettingsOutline.visible(List.of(), "anything", Set.of()), List.of());
        List<Setting> ungrouped = List.of(settings.get(2), settings.get(5), settings.get(9));
        equal(SettingsOutline.initiallyCollapsed(ungrouped), Set.of());
        equal(SettingsOutline.visible(ungrouped, "", Set.of()).stream().map(SettingsOutline.Entry::setting).toList(), ungrouped);
    }

    private static void duplicateHeadings() {
        List<Setting> settings = List.of(new Setting.Section("Repeated"),
                Setting.demoToggle("First child", "First option", false), new Setting.Section("Repeated"),
                Setting.demoToggle("Second needle", "Second option", false), new Setting.Section("Repeated"));
        Set<Integer> collapsed = SettingsOutline.initiallyCollapsed(settings);
        equal(collapsed, Set.of(2, 4));
        List<SettingsOutline.Entry> normal = SettingsOutline.visible(settings, "", collapsed);
        equal(indices(normal), List.of(0, 1, 2, 4));
        equal(entry(normal, 0), new SettingsOutline.Entry(settings.get(0), 0, true, false, 1));
        equal(entry(normal, 2), new SettingsOutline.Entry(settings.get(2), 2, false, false, 1));
        equal(entry(normal, 4), new SettingsOutline.Entry(settings.get(4), 4, false, false, 0));
        equal(indices(SettingsOutline.visible(settings, "", Set.of(0, 4))), List.of(0, 2, 3, 4));
        equal(SettingsOutline.visible(settings, "needle", collapsed), List.of(
                new SettingsOutline.Entry(settings.get(2), 2, true, false, 1),
                new SettingsOutline.Entry(settings.get(3), 3, false, true, 0)));
        List<SettingsOutline.Entry> headingMatch = SettingsOutline.visible(settings, "repeated", collapsed);
        equal(indices(headingMatch), List.of(0, 1, 2, 3, 4));
        equal(entry(headingMatch, 0), new SettingsOutline.Entry(settings.get(0), 0, true, true, 1));
        equal(entry(headingMatch, 2), new SettingsOutline.Entry(settings.get(2), 2, true, true, 1));
        equal(entry(headingMatch, 4), new SettingsOutline.Entry(settings.get(4), 4, true, true, 0));
        equal(collapsed, Set.of(2, 4));
        equal(SettingsOutline.visible(settings, "", collapsed), normal);
    }

    private static Module module(String id, String title, String category) {
        return Module.placeholder(id, title, "Test module", category);
    }

    private static void rowTargets() {
        for (int width : List.of(126, 240, 260, 600)) for (boolean implemented : List.of(false, true))
            for (boolean settings : List.of(false, true)) {
                ModuleRowLayout row = ModuleRowLayout.of(20, 30, width, implemented, settings);
                equal(row.row().h(), ModuleRowLayout.height(width));
                equal(row.stacked(), width < 260);
                equal(row.target(19.9, 35), ModuleRowLayout.Target.NONE);
                equal(row.target(20 + width, 35), ModuleRowLayout.Target.NONE);
                equal(row.target(25, 30 + row.row().h()), ModuleRowLayout.Target.NONE);
                ModuleRowLayout.Rect label = row.label();
                yes(label.w() > 0, "module name has no visible space at width " + width);
                equal(row.target(label.x() + .5, label.y() + .5), ModuleRowLayout.Target.ROW);
                List<ModuleRowLayout.Rect> controls = List.of(row.key(), row.toggle(), row.expand(), row.pin());
                List<ModuleRowLayout.Target> targets = List.of(ModuleRowLayout.Target.KEY,
                        ModuleRowLayout.Target.TOGGLE, ModuleRowLayout.Target.EXPAND, ModuleRowLayout.Target.PIN);
                for (int i = 0; i < controls.size(); i++) {
                    ModuleRowLayout.Rect control = controls.get(i);
                    boolean present = i == 3 || (i == 2 ? settings : i == 0 ? implemented && width >= 150 : implemented);
                    equal(control.w() > 0, present);
                    if (!present) continue;
                    yes(row.row().contains(control.x(), control.y())
                                    && row.row().contains(control.x() + control.w() - .01, control.y() + control.h() - .01),
                            "click target extends outside its row at width " + width);
                    for (double dx : List.of(.01, control.w() / 2.0, control.w() - .01))
                        for (double dy : List.of(.01, control.h() - .01))
                            equal(row.target(control.x() + dx, control.y() + dy), targets.get(i));
                    for (int other = 0; other < controls.size(); other++) if (other != i) {
                        ModuleRowLayout.Rect second = controls.get(other);
                        yes(!second.contains(control.x() + control.w() / 2.0, control.y() + control.h() / 2.0),
                                "one click could activate two module controls");
                    }
                }
            }
    }

    private static List<Setting> settings() {
        return List.of(new Setting.Note("Overview", () -> "Read this first"), new Setting.Section("Basic"),
                Setting.demoToggle("Outline marker", "Armor contrast", false),
                Setting.demoSlider("Opacity amount", "Surface tint", 0, 1, .1, .5, ""),
                Setting.demoChoice("Route mode", "Path selection", List.of("Celestial", "Ground"), "Celestial"),
                new Setting.Color("Stroke color", "Visual hue", () -> 0xFF44AAFF, value -> { }),
                new Setting.Action("Preview placement", "Position guides", "Reposition", () -> { }),
                new Setting.KeyAction("quick", "Quick utility", "Execute action", "Teleport", () -> { }),
                new Setting.Command("travel", "/warp forge", () -> { }),
                new Setting.Note("Route advice", () -> "Dynamic navigation"), new Setting.Section("Advanced"),
                Setting.demoToggle("Rare alert", "Distinct warning", false));
    }

    private static List<Integer> indices(List<SettingsOutline.Entry> entries) {
        return entries.stream().map(SettingsOutline.Entry::index).toList();
    }

    private static SettingsOutline.Entry entry(List<SettingsOutline.Entry> entries, int index) {
        return entries.stream().filter(entry -> entry.index() == index).findFirst()
                .orElseThrow(() -> new AssertionError("missing original setting index " + index));
    }

    private static void unsupported(Runnable action, String reason) {
        checks++;
        try { action.run(); }
        catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError(reason);
    }

    private static void same(Object first, Object second, String reason) { yes(first == second, reason); }

    private static void different(Object first, Object second, String reason) { yes(first != second, reason); }

    private static void equal(Object actual, Object expected) {
        yes(Objects.equals(actual, expected), "expected " + expected + ", got " + actual);
    }

    private static void yes(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
}
