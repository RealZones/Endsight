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
        yes(registry.count(null, "") == 31, "expected thirty-one complete browser entries");
        yes(registry.categories().equals(List.of("Slayers", "The End", "Dungeons", "Mining", "Inventory", "Chat", "General")), "activity order changed");
        yes(registry.favorites().values().stream().mapToInt(List::size).sum() == 4, "priority favorites missing");
        Module tracking = find(registry, "page.slayerTracking");
        Module timer = originals.stream().filter(m -> m.id().equals("slayer.timer")).findFirst().orElseThrow();
        Module profit = originals.stream().filter(m -> m.id().equals("slayer.profit")).findFirst().orElseThrow();
        Setting.Toggle timerControl = tracking.settings().stream().filter(s -> s instanceof Setting.Toggle && s.label().equals(timer.title())).map(s -> (Setting.Toggle)s).findFirst().orElseThrow();
        timerControl.set().accept(false);
        yes(!timer.isEnabled() && profit.isEnabled(), "group control changed an unrelated tracker");
        yes(!tracking.togglable(), "settings group acquired a misleading master switch");
        yes(tracking.settings().stream().anyMatch(s -> s instanceof Setting.KeyAction k && k.id().equals("module.slayer.profit")), "profit keybind identity changed");
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
        yes(registry.count(null, "key") <= 2, "a search for key listed pages only for their keybinds");

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
        String ids = "slayer.timer slayer.profit slayer.boss slayer.spawnMarker slayer.revenantTiles zealot.tracker dragon.bossdrops dragon.timer qol.eyeguard dragon.protector visual.beacon visual.shinyNodes tracker.caches qol.math dungeon.map dungeon.keys dungeon.threeWeirdos dungeon.ticTacToe dungeon.quiz dungeon.higherOrLower dungeon.icePath dungeon.bloodRoom dungeon.terminals mining.session mining.powder storage.forge storage.preview hud.inventoryPreview storage.recipes visual.rarity visual.inventoryGlass qol.slotLock qol.armorguard qol.lootChat qol.abilityspam qol.globalchat qol.copychat hud.armor hud.activePet hud.stats visual.cooldowns visual.fullbright visual.explosionParticles qol.damage visual.etherwarp alerts dragon.loot qol.commands qol.hotdButton social.discord";
        ModuleRegistry registry = new ModuleRegistry();
        for (String id : ids.split(" ")) registry.add(stub(id));
        return registry;
    }

    private static void yes(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
