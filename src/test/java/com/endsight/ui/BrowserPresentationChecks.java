package com.endsight.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Presentation must never replace persistence owners or freeze live command settings. */
public final class BrowserPresentationChecks {
    private static int checks;
    public static void main(String[] args) {
        ModuleRegistry registry = fixture();
        List<Module> originals = registry.all();
        FeatureCatalog.publicLayout(registry);
        yes(registry.all() == originals, "presentation replaced the backend snapshot");
        yes(registry.count(null, "") == 36, "expected thirty-six complete browser entries");
        yes(registry.categories().equals(List.of("Slayers", "The End", "Dungeons", "Mining", "Inventory", "Chat", "General")), "activity order changed");
        yes(registry.favorites().values().stream().mapToInt(List::size).sum() == 4, "priority favorites missing");
        Module tracking = find(registry, "page.slayerTracking");
        Module timer = originals.stream().filter(m -> m.id().equals("slayer.timer")).findFirst().orElseThrow();
        Module profit = originals.stream().filter(m -> m.id().equals("slayer.profit")).findFirst().orElseThrow();
        Setting.Toggle timerControl = tracking.settings().stream().filter(s -> s instanceof Setting.Toggle && s.label().equals(timer.title())).map(s -> (Setting.Toggle)s).findFirst().orElseThrow();
        timerControl.set().accept(false);
        yes(!timer.isEnabled() && profit.isEnabled(), "group control changed an unrelated tracker");
        yes(!tracking.togglable(), "settings group acquired a misleading master switch");
        yes(tracking.settings().stream().noneMatch(s -> s instanceof Setting.KeyAction), "a tracker grew a key nobody needs");
        yes(tracking.settings().stream().noneMatch(s -> s.label().equals("Keybinds")), "a page kept a heading with nothing under it");
        keysWorthHaving();
        for (Module original : originals) yes(original.settings().stream().anyMatch(s -> s.label().equals("Persisted option")), "backend setting labels were rewritten");

        Module commands = originals.stream().filter(m -> m.id().equals("qol.commands")).findFirst().orElseThrow();
        Module commandPage = find(registry, "page.commands");
        int before = commandPage.settings().size();
        Setting.Command command = new Setting.Command("cmd.example", "/warp sapphire", () -> {});
        commands.settings().add(command);
        registry.invalidateSearch();
        yes(commandPage.settings().size() == before + 1, "live command addition was frozen by grouping");
        yes(registry.count(null, "sapphire") == 1, "search missed a newly added command");
        commands.settings().remove(command);
        registry.invalidateSearch();
        yes(registry.count(null, "sapphire") == 0, "removed command stayed in search");
        yes(registry.count(null, "Slayer Profit") == 1, "old feature name disappeared from search");
        // Every grouped page carries a Keybinds heading and "X key" rows; those must not make
        // a search for "key" list the whole menu.
        yes(SettingsSearch.matchingLabels(tracking, "key").isEmpty(), "keybind rows matched a browser search");
        long otherKeyMatches = registry.grouped(null, "key").values().stream().flatMap(List::stream).count();
        yes(otherKeyMatches <= 2, "a search for key listed pages only for their keybinds");
        yes(!registry.categories().contains("Loadouts"), "removed category remained visible");
        yes(registry.count(null, "Door Highlight") == 0, "removed feature remained searchable");

        registry.toggleFavorite("page.slayerTracking");
        registry.browserView("Slayers");
        Properties saved = new Properties();
        registry.saveBrowserState(saved);
        ModuleRegistry fresh = fixture();
        FeatureCatalog.publicLayout(fresh);
        fresh.loadBrowserState(saved);
        yes(!fresh.favorite("page.slayerTracking") && fresh.favorite("slayer.boss"), "favorite state did not round-trip");
        yes("Slayers".equals(fresh.browserView()), "last destination did not round-trip");
        System.out.println(checks + " browser presentation checks passed");
    }

    private static Module find(ModuleRegistry registry, String id) {
        return registry.grouped(null, "").values().stream().flatMap(List::stream).filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private static Module stub(String id) {
        String title = switch (id) { case "slayer.timer" -> "Slayer Tracker"; case "slayer.profit" -> "Slayer Profit"; default -> id; };
        Module module = Module.placeholder(id, title, "Saved backend descriptor", "Original", new ArrayList<>(List.of(Setting.demoToggle("Persisted option", "Existing saved label", true))));
        module.setEnabled().accept(true);
        return module;
    }

    private static ModuleRegistry fixture() {
        String ids = "slayer.timer slayer.profit slayer.boss slayer.spawnMarker slayer.revenantTiles zealot.tracker dragon.bossdrops dragon.timer qol.eyeguard dragon.protector goop.timer tracker.goop visual.beacon visual.shinyNodes tracker.caches qol.math dungeon.map dungeon.keys dungeon.threeWeirdos dungeon.ticTacToe dungeon.quiz dungeon.icePath dungeon.iceFill dungeon.creeperBeams dungeon.bloodRoom dungeon.terminals mining.session mining.powder storage.forge storage.preview hud.inventoryPreview storage.recipes visual.rarity visual.inventoryGlass qol.slotLock qol.armorguard qol.lootChat qol.abilityspam qol.copychat hud.armor hud.activePet hud.stats visual.cooldowns visual.fullbright visual.perspective visual.camera visual.animations dungeon.roomGlow visual.explosionParticles qol.damage visual.etherwarp alerts dragon.loot qol.commands qol.hotdButton social.discord";
        ModuleRegistry registry = new ModuleRegistry();
        for (String id : (ids + " dragon.hitboxes qol.autoclicker").split(" ")) registry.add(stub(id));
        return registry;
    }

    private static void yes(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void keysWorthHaving() {
        yes(!Keybinds.worthAKey("module.dungeon.icePath"), "a room puzzle does not want a key");
        yes(Keybinds.worthAKey("module.qol.autoclicker") && Keybinds.worthAKey("module.visual.fullbright"), "a switch for play keeps its key");
        yes(Keybinds.worthAKey("qol.slotLock.lock") && Keybinds.worthAKey("cmd.0"), "an action is not a module switch");
        Keybinds.set("module.dungeon.icePath", 71);
        yes(Keybinds.worthAKey("module.dungeon.icePath"), "a key already set stays where it can be cleared");
        Keybinds.clear("module.dungeon.icePath");
        int[] menu = {344};
        Keybinds.openMenuKey(() -> menu[0], key -> menu[0] = key == Keybinds.NONE ? 344 : key);
        Keybinds.set("cmd.0", 344);
        yes(Keybinds.get("cmd.0") == Keybinds.NONE && Keybinds.get(Keybinds.OPEN_MENU) == 344, "the menu key cannot be taken by another bind");
        Keybinds.set("cmd.0", 71);
        Keybinds.set(Keybinds.OPEN_MENU, 71);
        yes(menu[0] == 71 && Keybinds.get("cmd.0") == Keybinds.NONE, "the menu key takes a key from what held it");
        Keybinds.clear(Keybinds.OPEN_MENU);
        yes(menu[0] == 344, "clearing the menu key puts the default back, so the menu stays reachable");
    }
}
