package com.endsight.ui;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Activity-based browser entries. Original modules still own every saved value and action. */
public final class FeatureCatalog {
    private FeatureCatalog() { }

    public static void publicLayout(ModuleRegistry registry) {
        Catalog c = new Catalog(registry);
        c.group("page.slayerTracking", "Slayer tracking", "Kills, session pace, drops and profit.", "Slayers", "slayer.timer", "slayer.profit");
        c.single("slayer.boss", "Slayers", "Voidgloom Helper", "Shield hits, boss health and phase cues.");
        c.group("page.spawnMarkers", "Spawn markers", "Slayer spawn marker and Revenant tiles.", "Slayers", "slayer.spawnMarker", "slayer.revenantTiles");
        c.single("zealot.tracker", "The End");
        c.single("dragon.bossdrops", "The End");
        c.group("page.dragonTools", "Dragon tools", "Dragon timer and placed-eye protection.", "The End", "dragon.timer", "qol.eyeguard");
        c.group("page.bossIndicators", "Boss indicators", "Golem stage and Protector/Warden beacons.", "The End", "dragon.protector", "visual.beacon");
        c.single("visual.shinyNodes", "The End", "End nodes", "Highlights confirmed shiny End nodes.");
        c.single("tracker.caches", "The End");
        c.single("qol.math", "The End", "Golden Dragon math", "Answers the Golden Dragon's sum.");
        c.single("dungeon.map", "Dungeons");
        c.single("dungeon.keys", "Dungeons");
        c.group("page.roomPuzzles", "Room puzzles", "Five independently controlled room solvers.", "Dungeons",
                "dungeon.threeWeirdos", "dungeon.ticTacToe", "dungeon.quiz", "dungeon.higherOrLower", "dungeon.icePath");
        c.single("dungeon.bloodRoom", "Dungeons");
        c.single("dungeon.terminals", "Dungeons");
        c.group("page.miningTracking", "Mining tracking", "Mining session, powder gains and rates.", "Mining", "mining.session", "mining.powder");
        c.single("storage.forge", "Mining");
        c.group("page.previews", "Inventory previews", "Storage hover previews, item search and inventory HUD.", "Inventory", "storage.preview", "hud.inventoryPreview");
        c.single("storage.recipes", "Inventory");
        c.group("page.itemAppearance", "Item appearance", "Rarity outlines and glass inventory backgrounds.", "Inventory", "visual.rarity", "visual.inventoryGlass");
        c.group("page.itemProtection", "Item protection", "Slot locking and armor-swap protection.", "Inventory", "qol.slotLock", "qol.armorguard");
        c.single("qol.lootChat", "Chat");
        c.group("page.chatBehavior", "Chat behavior", "Ability and Goop boss cleanup, plus global chat on join.", "Chat", "qol.abilityspam", "qol.globalchat");
        c.single("qol.copychat", "Chat");
        c.group("page.playerHud", "Player HUD", "Armor, pet, server stats and ability cooldowns.", "General", "hud.armor", "hud.activePet", "hud.stats", "visual.cooldowns");
        c.group("page.worldAppearance", "World appearance", "Lighting and explosion clouds.", "General", "visual.fullbright", "visual.explosionParticles");
        c.single("qol.damage", "General");
        c.single("visual.etherwarp", "General");
        c.group("page.notifications", "Notifications", "Gameplay and loot alerts, independently controlled.", "General", "alerts", "dragon.loot");
        c.group("page.commands", "Commands & menu", "Command keybinds and menu shortcuts.", "General", "qol.commands", "qol.hotdButton");
        c.single("social.discord", "General");
        c.covered.add("visual.tweaks"); // Its independent controls now have their activity-specific homes.
        c.finish(List.of("page.slayerTracking", "slayer.boss", "dragon.bossdrops", "zealot.tracker"));
    }

    /** Refreshed only when a child list changes; command rows remain editable after grouping. */
    private static List<Setting> live(List<Module> children, Supplier<List<Setting>> build) {
        return new AbstractList<>() {
            private List<List<Setting>> membership;
            private List<Setting> cached;
            private List<Setting> snapshot() {
                if (membership == null || java.util.stream.IntStream.range(0, children.size())
                        .anyMatch(i -> !membership.get(i).equals(children.get(i).settings()))) {
                    membership = children.stream().map(m -> List.copyOf(m.settings())).toList();
                    cached = List.copyOf(build.get());
                }
                return cached;
            }
            @Override public Setting get(int index) { return snapshot().get(index); }
            @Override public int size() { return snapshot().size(); }
        };
    }

    private static void key(List<Setting> settings, Module module) {
        if (module.togglable()) settings.add(new Setting.KeyAction(Keybinds.moduleId(module), module.title() + " key",
                "Assign a shortcut for " + module.title() + ".", "Toggle", module::toggle));
    }

    private static final class Catalog {
        private final ModuleRegistry registry;
        private final Map<String, Module> originals = new LinkedHashMap<>();
        private final Map<String, String> aliases = new LinkedHashMap<>();
        private final List<Module> entries = new ArrayList<>();
        private final java.util.Set<String> originallyVisible = new java.util.HashSet<>();
        private final java.util.Set<String> covered = new java.util.HashSet<>();
        private Catalog(ModuleRegistry registry) {
            this.registry = registry;
            for (Module module : registry.all()) originals.put(module.id(), module);
            registry.grouped(null, "").values().forEach(group -> group.forEach(m -> originallyVisible.add(m.id())));
        }

        private Module require(String id) {
            Module module = originals.get(id);
            if (module == null) throw new IllegalStateException("Missing browser feature: " + id);
            covered.add(id);
            return module;
        }

        private void single(String id, String category) { single(id, category, null, null); }
        private void single(String id, String category, String title, String description) {
            Module old = require(id);
            List<Setting> settings = old.group() ? old.settings() : live(List.of(old), () -> {
                List<Setting> result = new ArrayList<>(old.settings());
                result.add(new Setting.Section("Keybinds"));
                key(result, old);
                return result;
            });
            entries.add(new Module(id, title == null ? old.title() : title, description == null ? old.description() : description,
                    category, old.enabled(), old.setEnabled(), settings));
            aliases.put(id, old.title() + " " + old.category());
        }

        private void group(String id, String title, String description, String category, String... ids) {
            List<Module> children = java.util.Arrays.stream(ids).map(this::require).toList();
            List<Setting> settings = live(children, () -> {
                List<Setting> result = new ArrayList<>();
                result.add(new Setting.Section("Controls"));
                for (Module child : children) if (child.togglable())
                    result.add(new Setting.Toggle(child.title(), child.description(), child.enabled(), child.setEnabled()));
                for (Module child : children) {
                    boolean heading = false;
                    for (Setting setting : child.settings()) {
                        if (setting instanceof Setting.Section section) {
                            result.add(new Setting.Section(child.title() + " / " + section.label()));
                            heading = true;
                        } else {
                            if (!heading) { result.add(new Setting.Section(child.title())); heading = true; }
                            result.add(setting);
                        }
                    }
                }
                result.add(new Setting.Section("Keybinds"));
                for (Module child : children) key(result, child);
                return result;
            });
            entries.add(Module.group(id, title, description, category, settings));
            aliases.put(id, children.stream().map(m -> m.title() + " " + m.category()).collect(java.util.stream.Collectors.joining(" ")));
        }

        private void finish(List<String> defaults) {
            // New real features must remain discoverable even before a presentation is assigned.
            for (Module module : originals.values()) if (originallyVisible.contains(module.id()) && !covered.contains(module.id()) && module.implemented())
                single(module.id(), module.category());
            registry.present(entries, aliases, defaults);
        }
    }
}
