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

        // Registration order is sidebar order: categories appear in the order they are
        // first seen, and modules in the order they are added. Nothing else configures
        // the sidebar, so this list IS the layout.

        r.add(Module.placeholder("dragon.timer", "Dragon Timer",
                "Eye count and dragon state, read from chat.", "Dragons"));
        r.add(Module.unimplemented("dragon.loot", "Loot Alerts",
                "On-screen alerts for drops worth stopping for.", "Alerts"));

        r.add(Module.placeholder("dragon.protector", "Protector Stage",
                "Tracks the Endstone Protector rising, tier by tier.", "Golem"));
        r.add(Module.placeholder("visual.beacon", "Protector Beacon",
                "Beam over the Endstone Protector so it can be found at a glance.", "Golem"));

        r.add(Module.unimplemented("slayer.boss", "Boss Highlight",
                "Marks your own boss, ignores everyone else's.", "Slayers"));
        r.add(Module.placeholder("slayer.timer", "Slayer Tracker",
                "Kills, time spent and rate for the session.", "Slayers"));

        r.add(Module.placeholder("zealot.tracker", "Zealot Tracker",
                "Your zealot kills and rate for the session.", "Visual"));

        r.add(Module.placeholder("visual.nukubi", "Nukubi Highlight",
                "Marks Nukubi so it is not lost in the crowd.", "Visual"));

        r.add(Module.placeholder("alerts", "Alerts",
                "On-screen calls for the things worth looking up for.", "Alerts"));

        r.add(Module.placeholder("storage.preview", "Storage Preview",
                "Hover a storage page to see inside it.", "Visual"));
        r.add(Module.placeholder("storage.search", "Item Search",
                "Finds an item across every page at once.", "Quality of Life"));
        r.add(Module.placeholder("qol.eyeguard", "Protect Placed Eyes",
                "Ignores right-clicks briefly after you place an eye.", "Quality of Life"));
        r.add(Module.placeholder("qol.damage", "Damage Numbers",
                "Shortens the damage popups, or takes them away.", "Visual"));

        cached = r;
        return r;
    }
}
