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
        yes(registry.count(null, "") == 39, "expected thirty-nine complete browser entries");
        yes(registry.categories().equals(List.of("Slayers", "The End", "Dungeons", "Mining", "Inventory", "Loadouts", "Chat", "General")), "activity order changed");
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
        long otherKeyMatches = registry.grouped(null, "key").values().stream().flatMap(List::stream)
                .filter(module -> !module.id().equals("qol.wardrobe") && !module.id().equals("qol.pets")).count();
        yes(otherKeyMatches <= 2, "a search for key listed pages only for their keybinds");
        yes(registry.categories().contains("Loadouts"), "Loadouts is missing from the sidebar");
        yes(registry.count("Loadouts", "") == 2, "Loadouts holds pets and wardrobe");
        loadoutGeometry();

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
        String ids = "slayer.timer slayer.profit slayer.boss slayer.spawnMarker slayer.revenantTiles zealot.tracker dragon.bossdrops dragon.timer qol.eyeguard dragon.protector goop.timer tracker.goop visual.beacon visual.shinyNodes tracker.caches qol.math dungeon.map dungeon.keys dungeon.doors dungeon.threeWeirdos dungeon.ticTacToe dungeon.quiz dungeon.icePath dungeon.iceFill dungeon.creeperBeams dungeon.bloodRoom dungeon.terminals mining.session mining.powder storage.forge storage.preview hud.inventoryPreview storage.recipes visual.rarity visual.inventoryGlass qol.slotLock qol.armorguard qol.lootChat qol.abilityspam qol.copychat hud.armor hud.activePet hud.stats visual.cooldowns visual.fullbright visual.perspective visual.camera visual.animations dungeon.roomGlow visual.explosionParticles qol.damage visual.etherwarp alerts dragon.loot qol.commands qol.hotdButton social.discord";
        ModuleRegistry registry = new ModuleRegistry();
        for (String id : (ids + " dragon.hitboxes qol.autoclicker qol.wardrobe qol.pets").split(" ")) registry.add(stub(id));
        return registry;
    }

    private static void yes(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    /** Two tiles to a row in the usual window, one where the panel is narrow, and never overlapping. */
    private static void loadoutGeometry() {
        var wide = LoadoutView.Geometry.of(10, 20, 386, 1, 9);
        yes(wide.tiles().get(1).y() == wide.tiles().get(0).y(), "tiles share a row at normal width");
        yes(wide.tiles().get(2).y() > wide.tiles().get(1).y(), "a third tile wraps");
        yes(wide.tiles().get(0).x() + wide.tiles().get(0).w() <= wide.tiles().get(1).x(), "tiles in a row do not touch");
        var last = wide.tiles().getLast();
        yes(last.y() + last.h() <= 20 + wide.height(), "the page is tall enough for its last tile");
        var bar = wide.bars().getFirst();
        yes(bar.first().x() + bar.first().w() <= bar.second().x(), "swap sides do not overlap");
        yes(bar.second().x() + bar.second().w() <= bar.key().x(), "swap key clears the second side");
        yes(bar.remove() == null, "the first swap cannot be taken away");
        yes(wide.add().y() + wide.add().h() <= bar.row().y(), "the add button stays on the caption line");
        var two = LoadoutView.Geometry.of(10, 20, 386, 2, 9);
        var more = two.bars().get(1);
        yes(more.row().y() >= bar.row().y() + bar.row().h(), "a second swap sits under the first");
        yes(more.remove().y() + more.remove().h() <= more.key().y(), "the remove button clears the key");
        yes(two.tiles().getFirst().y() > more.row().y() + more.row().h(), "the tiles move down for a second swap");
        for (int swaps = 1; swaps <= Setting.Loadout.MAX_SWAPS; swaps++)
            yes(LoadoutView.height(386, swaps, 9) == LoadoutView.Geometry.of(10, 20, 386, swaps, 9).height(), "height agrees with the placement");
        var narrow = LoadoutView.Geometry.of(10, 20, 230, 1, 3);
        var thin = narrow.bars().getFirst();
        yes(narrow.tiles().get(0).w() == 230 && narrow.tiles().get(1).x() == 10, "one column when two would crush the text");
        yes(thin.second().x() + thin.second().w() <= thin.key().x(), "swap key clears the second side when narrow");
        var empty = LoadoutView.Geometry.of(10, 20, 386, 1, 0);
        yes(empty.tiles().isEmpty() && empty.empty() != null, "no tiles shows the empty card");
        yes(empty.empty().contains(empty.load().x(), empty.load().y()), "the load button sits in the empty card");
        yes(LoadoutView.height(386, 1, 0) == empty.height(), "empty height agrees with the placement");

        List<Setting.Tile> tiles = new java.util.ArrayList<>();
        for (String name : List.of("Warden", "Golem", "Black Cat", "Bal"))
            tiles.add(new Setting.Tile(name, name, 0, name.equals("Golem") ? "Lucky Clover" : "", List.of(), false, () -> { }));
        Setting.Loadout page = new Setting.Loadout("Your pets", "pet", () -> tiles, List::of, () -> { }, () -> { });
        LoadoutView view = new LoadoutView();
        yes(view.shown(page, "").size() == 4, "no search shows every pet");
        yes(view.shown(page, "warden").size() == 1, "a search narrows the pets");
        yes(view.shown(page, "clover").size() == 1, "a search reads the held item too");
        yes(view.shown(page, "swap").size() == 4, "a search for the page itself leaves the pets up");
        yes(view.shown(page, "").size() == 4, "clearing the search brings them back");
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
