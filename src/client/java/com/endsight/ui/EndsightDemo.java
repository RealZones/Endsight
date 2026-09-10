package com.endsight.ui;

import java.util.List;

/**
 * The modules Endsight is actually going to ship, minus the ones that are already real.
 *
 * This started as nineteen placeholders across six categories, which was the right way
 * to lay a menu out - a grid that looks correct with three cards and falls apart at
 * twenty has not been tested. That job is done and the shipping list has since been
 * picked, so the padding is gone: what is left is a plan rather than a stress test.
 *
 * Everything here still toggles a boolean this object owns and nothing else. Modules
 * that have grown real behaviour are registered by their own class and replace their
 * placeholder by id in {@code EndsightClient.registry()}, so this file never has to
 * know which of them have been built.
 */
public final class EndsightDemo {

    private EndsightDemo() {
    }

    private static ModuleRegistry cached;

    /** Built once and kept, so a toggle survives closing and reopening the menu. */
    public static ModuleRegistry registry() {
        if (cached != null) return cached;
        ModuleRegistry r = new ModuleRegistry();

        r.add(Module.placeholder("dragon.timer", "Dragon Timer",
                "Spawn countdown and eye placement tracking.", "Dragons", List.of(
                        new Setting.Section("Timing"),
                        Setting.demoSlider("Warn ahead of spawn",
                                "How early to call it.", 0, 120, 5, 30, "s"),
                        Setting.demoSlider("Eye placement delay",
                                "Pause between placing each eye.", 0, 2, 0.1, 0.4, "s"),
                        new Setting.Section("Display"),
                        Setting.demoToggle("Show on HUD",
                                "Keep the countdown on screen.", true),
                        Setting.demoChoice("Anchor",
                                "Where the readout sits.",
                                List.of("Top left", "Top right", "Bottom left", "Bottom right"),
                                "Top right"),
                        Setting.demoSlider("HUD scale",
                                "Size of the readout.", 0.5, 2, 0.1, 1, "x"))));
        r.add(Module.placeholder("dragon.loot", "Loot Alerts",
                "On-screen alerts for drops worth stopping for.", "Dragons"));

        r.add(Module.placeholder("slayer.boss", "Boss Highlight",
                "Marks your own boss, ignores everyone else's.", "Slayers"));
        r.add(Module.placeholder("slayer.timer", "Slayer Tracker",
                "Kills, time spent and rate for the session.", "Slayers"));

        // Both Storage modules are real - registered here only so they keep their place
        // in the order, then replaced by id at startup.
        r.add(Module.placeholder("storage.preview", "Storage Preview",
                "Hover a storage page to see inside it.", "Storage"));
        r.add(Module.placeholder("storage.search", "Item Search",
                "Finds an item across every page at once.", "Storage"));

        r.add(Module.placeholder("alerts", "Alerts",
                "On-screen calls for the things worth looking up for.", "Alerts"));

        r.add(Module.placeholder("visual.nukubi", "Nukubi Highlight",
                "Marks Nukubi so it is not lost in the crowd.", "Visual", List.of(
                        Setting.demoSlider("Outline width",
                                "Thickness of the marker.", 1, 6, 1, 2, "px"),
                        Setting.demoSlider("Range",
                                "How far out to highlight.", 8, 128, 8, 48, "m"),
                        Setting.demoToggle("Through walls",
                                "Draw it even when out of sight.", true))));
        r.add(Module.placeholder("visual.nametags", "Nametag Cleanup",
                "Hides the nametags that are just noise.", "Visual"));

        r.add(Module.placeholder("qol.eyeguard", "Protect Placed Eyes",
                "Ignores right-clicks briefly after you place an eye.", "Quality of Life"));

        cached = r;
        return r;
    }
}
