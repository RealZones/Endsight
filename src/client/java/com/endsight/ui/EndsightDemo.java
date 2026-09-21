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

        // Trackers: the readouts, the ones you look at most first.
        r.add(Module.placeholder("dragon.bossdrops", "Boss Drops",
                "Kills and drops from whichever boss you killed last, sorted by rarity.", "Trackers"));
        r.add(Module.placeholder("slayer.timer", "Slayer Tracker",
                "Kills, time spent and rate for the session.", "Trackers"));
        r.add(Module.placeholder("zealot.tracker", "Zealot Tracker",
                "Your zealot kills and rate for the session.", "Trackers"));
        r.add(Module.placeholder("dragon.timer", "Dragon Timer",
                "Eye count and dragon state, read from chat.", "Trackers"));
        r.add(Module.placeholder("dragon.protector", "Protector Stage",
                "The Protector's tier and how long it has been up.", "Trackers"));

        // Mining: live grind readouts only.
        r.add(Module.placeholder("mining.session", "Mining Session",
                "Active mining time, blocks and rate.", "Mining"));
        r.add(Module.placeholder("storage.forge", "Forge Timer",
                "Active Forge slots, ready claims and empty slot count.", "Mining"));

        // Storage: inventories, recipes and item lookup.
        r.add(Module.placeholder("storage.recipes", "Recipes",
                "Recipes and Forge crafts beside your inventory.", "Storage"));
        r.add(Module.placeholder("storage.preview", "Storage Preview",
                "Hover a storage page to see inside it.", "Storage"));

        // Alerts.
        r.add(Module.placeholder("alerts", "Alerts",
                "Mid-screen alerts for bosses, fireballs and a full inventory.", "Alerts"));
        r.add(Module.unimplemented("dragon.loot", "Loot Alerts",
                "Mid-screen alert and a ping for the drops you pick.", "Alerts"));

        // Visual: the fight first, the furniture last.
        r.add(Module.placeholder("slayer.boss", "Voidgloom Helper",
                "Shield hits and boss health on screen, boss and glyph highlights.", "Visual"));
        r.add(Module.placeholder("visual.beacon", "Boss Beacon",
                "A beam over the Endstone Protector and the Warden.", "Visual"));
        r.add(Module.placeholder("visual.etherwarp", "Etherwarp Outline",
                "Boxes the block Etherwarp will take you to.", "Visual"));
        r.add(Module.placeholder("qol.damage", "Damage Numbers",
                "Shortens the damage popups, or takes them away.", "Visual"));
        r.add(Module.placeholder("visual.cooldowns", "Ability Cooldowns",
                "Each ability you use as a ring that fills while it cools down.", "Visual"));
        r.add(Module.placeholder("visual.rarity", "Rarity Outlines",
                "A colour round each item, by its rarity, in any window and on the hotbar.", "Visual"));
        r.add(Module.placeholder("visual.fullbright", "Fullbright",
                "Lights the whole world.", "Visual"));
        r.add(Module.placeholder("hud.stats", "Ping / TPS",
                "FPS, your ping and the server's tick rate, in a pill.", "Visual"));

        // Chat: what you turn on first, down to the one you rarely touch.
        r.add(Module.placeholder("qol.abilityspam", "Ability Spam",
                "One cooldown line with a count, or none.", "Chat"));
        r.add(Module.placeholder("qol.debug", "Debug on join",
                "Runs /debug for you two seconds after every join.", "Chat"));
        r.add(Module.placeholder("qol.copychat", "Copy Chat",
                "Right-click a chat line to copy it.", "Chat"));
        r.add(Module.placeholder("qol.math", "Math Solver",
                "Works out the Golden Dragon's sum and puts the answer under it.", "Chat"));
        r.add(Module.placeholder("qol.lootfilter", "Loot Number Filter",
                "Hides loot numbers except the close calls at either end.", "Chat"));
        r.add(Module.placeholder("qol.lootroll", "Compact Loot Roll",
                "The dragon's /debug roll as two lines.", "Chat"));

        // Quality of life.
        r.add(Module.placeholder("qol.commands", "Command Binds",
                "Keys that send a command for you.", "Quality of Life"));
        r.add(Module.placeholder("qol.eyeguard", "Protect Placed Eyes",
                "Ignores right-clicks briefly after you place an eye.", "Quality of Life"));

        return r;
    }
}
