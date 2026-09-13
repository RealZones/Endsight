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
        //
        // Categories are KINDS of thing, not activities. The first cut mixed the two -
        // Visual and Alerts beside Golem and Slayers - so two readouts of the same shape
        // sat in different places and Slayers was left holding one module. Sorted by
        // kind, every category has a few members and the sidebar answers "what sort of
        // thing do I want" rather than "what was I doing when I wanted it".

        // Trackers: session readouts on the HUD, in the order of a night - dragons,
        // then the golem, then the grind.
        r.add(Module.placeholder("dragon.timer", "Dragon Timer",
                "Eye count and dragon state, read from chat.", "Trackers"));
        r.add(Module.placeholder("dragon.protector", "Protector Stage",
                "The Protector's tier and how long it has been up.", "Trackers"));
        r.add(Module.placeholder("zealot.tracker", "Zealot Tracker",
                "Your zealot kills and rate for the session.", "Trackers"));
        r.add(Module.placeholder("slayer.timer", "Slayer Tracker",
                "Kills, time spent and rate for the session.", "Trackers"));
        r.add(Module.placeholder("qol.droptracker", "Drop Tracker",
                "Every drop counted on screen, this session or all-time.", "Trackers"));

        // Alerts: the things that interrupt you.
        r.add(Module.placeholder("alerts", "Alerts",
                "Mid-screen alerts for minibosses, slayer bosses and the Protector.", "Alerts"));
        r.add(Module.unimplemented("dragon.loot", "Loot Alerts",
                "Mid-screen alert and a ping for the drops you pick.", "Alerts"));

        // Visual: drawn in the world or over the game.
        r.add(Module.placeholder("slayer.boss", "Voidgloom Helper",
                "Your boss's hits and health on screen, boss and Yang Glyph highlights.", "Visual"));
        r.add(Module.placeholder("visual.beacon", "Protector Beacon",
                "Beam over the Endstone Protector so it can be found at a glance.", "Visual"));
        r.add(Module.placeholder("qol.damage", "Damage Numbers",
                "Shortens the damage popups, or takes them away.", "Visual"));
        r.add(Module.placeholder("storage.preview", "Storage Preview",
                "Hover a storage page to see inside it.", "Visual"));

        // Chat: what the server prints, and what goes to the clipboard from it.
        r.add(Module.placeholder("qol.debug", "Debug on join",
                "Runs /debug for you two seconds after every join.", "Chat"));
        r.add(Module.placeholder("qol.lootfilter", "Loot Number Filter",
                "Hides loot numbers except the close calls at either end.", "Chat"));
        r.add(Module.placeholder("qol.drops", "Copy drops",
                "Each drop goes to your clipboard as it lands.", "Chat"));
        r.add(Module.placeholder("qol.math", "Math Solver",
                "Works out the Golden Dragon's sum and puts the answer under it.", "Chat"));

        // Quality of Life: the rest.
        r.add(Module.placeholder("qol.eyeguard", "Protect Placed Eyes",
                "Ignores right-clicks briefly after you place an eye.", "Quality of Life"));
        r.add(Module.placeholder("storage.search", "Item Search",
                "Finds an item across every page at once.", "Quality of Life"));
        r.add(Module.placeholder("qol.commands", "Command Binds",
                "Keys that send a command for you.", "Quality of Life"));

        cached = r;
        return r;
    }
}
