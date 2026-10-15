package com.endsight.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Category marks and familiar item icons for the features inside them. */
final class MenuIcons {
    private MenuIcons() { }

    // Kept once per icon, not allocated while scrolling the module list.
    private static final ItemStack DRAGON = new ItemStack(Items.DRAGON_HEAD);
    private static final ItemStack SWORD = new ItemStack(Items.IRON_SWORD);
    private static final ItemStack EYE = new ItemStack(Items.ENDER_EYE);
    private static final ItemStack CLOCK = new ItemStack(Items.CLOCK);
    private static final ItemStack GOLEM = new ItemStack(Items.IRON_BLOCK);
    private static final ItemStack COIN = new ItemStack(Items.GOLD_INGOT);
    private static final ItemStack PICKAXE = new ItemStack(Items.DIAMOND_PICKAXE);
    private static final ItemStack POWDER = new ItemStack(Items.GLOWSTONE_DUST);
    private static final ItemStack FORGE = new ItemStack(Items.ANVIL);
    private static final ItemStack RECIPES = new ItemStack(Items.CRAFTING_TABLE);
    private static final ItemStack STORAGE = new ItemStack(Items.ENDER_CHEST);
    private static final ItemStack ALERT = new ItemStack(Items.BELL);
    private static final ItemStack BEACON = new ItemStack(Items.BEACON);
    private static final ItemStack WARP = new ItemStack(Items.ENDER_PEARL);
    private static final ItemStack GEM = new ItemStack(Items.AMETHYST_SHARD);
    private static final ItemStack LIGHT = new ItemStack(Items.SEA_LANTERN);
    private static final ItemStack COMPASS = new ItemStack(Items.COMPASS);
    private static final ItemStack BOOK = new ItemStack(Items.BOOK);
    private static final ItemStack WRITING = new ItemStack(Items.WRITABLE_BOOK);
    private static final ItemStack PAPER = new ItemStack(Items.PAPER);
    private static final ItemStack FILTER = new ItemStack(Items.HOPPER);
    private static final ItemStack COMMAND = new ItemStack(Items.COMMAND_BLOCK);
    private static final ItemStack MAP = new ItemStack(Items.FILLED_MAP);
    private static final ItemStack CHEST = new ItemStack(Items.CHEST);
    private static final ItemStack BLAZE = new ItemStack(Items.BLAZE_ROD);
    private static final ItemStack REDSTONE = new ItemStack(Items.REDSTONE);
    private static final ItemStack ICE = new ItemStack(Items.BLUE_ICE);
    private static final ItemStack ARMOR = new ItemStack(Items.DIAMOND_CHESTPLATE);
    private static final ItemStack PET = new ItemStack(Items.NAME_TAG);
    private static final ItemStack GLASS = new ItemStack(Items.GLASS);
    private static final ItemStack LOCK = new ItemStack(Items.BARRIER);
    private static final ItemStack MUSIC = new ItemStack(Items.NOTE_BLOCK);
    private static final ItemStack MARKER = new ItemStack(Items.REDSTONE_TORCH);
    private static final ItemStack GRID = new ItemStack(Items.RED_CONCRETE);

    /** One recognizable icon per current feature; related features reuse an item. */
    private static ItemStack featureItem(String id) {
        return switch (id) {
            case "dragon.bossdrops", "dragon.loot" -> DRAGON;
            case "slayer.timer", "qol.damage" -> SWORD;
            case "zealot.tracker", "qol.eyeguard", "slayer.boss" -> EYE;
            case "dragon.timer", "visual.cooldowns", "qol.abilityspam" -> CLOCK;
            case "dragon.protector" -> GOLEM;
            case "slayer.profit" -> COIN;
            case "mining.session", "mining.spread" -> PICKAXE;
            case "mining.powder" -> POWDER;
            case "storage.forge" -> FORGE;
            case "storage.recipes" -> RECIPES;
            case "storage.preview" -> STORAGE;
            case "alerts" -> ALERT;
            case "visual.beacon" -> BEACON;
            case "visual.etherwarp" -> WARP;
            case "visual.rarity" -> GEM;
            case "visual.fullbright" -> LIGHT;
            case "hud.stats", "qol.hotdButton" -> COMPASS;
            case "qol.debug", "qol.math", "dungeon.quiz" -> BOOK;
            case "qol.copychat", "qol.globalchat" -> WRITING;
            case "qol.lootroll" -> PAPER;
            case "qol.lootfilter" -> FILTER;
            case "qol.commands" -> COMMAND;
            case "dungeon.map" -> MAP;
            case "dungeon.threeWeirdos", "hud.inventoryPreview" -> CHEST;
            case "dungeon.higherOrLower" -> BLAZE;
            case "dungeon.bloodRoom", "dungeon.terminals" -> REDSTONE;
            case "dungeon.icePath" -> ICE;
            case "hud.armor", "qol.armorguard" -> ARMOR;
            case "hud.activePet" -> PET;
            case "visual.inventoryGlass" -> GLASS;
            case "qol.slotLock" -> LOCK;
            case "social.discord" -> MUSIC;
            case "slayer.spawnMarker", "slayer.revenantTiles" -> MARKER;
            case "dungeon.ticTacToe" -> GRID;
            case "visual.explosionParticles" -> POWDER;
            default -> null;
        };
    }

    static void feature(GuiGraphicsExtractor g, Module module, int x, int y, int color) {
        ItemStack item = featureItem(module.id());
        if (item != null) g.item(item, x + 1, y + 1);
        else draw(g, module.category(), x + 3, y + 3, color);
    }

    enum Arrow { UP, RIGHT, DOWN, LEFT }

    /** Fixed pixels make arrows look like arrows even with a custom font pack. */
    static void arrow(GuiGraphicsExtractor g, int x, int y, Arrow direction, int color) {
        for (int i = 0; i < 3; i++) {
            switch (direction) {
                case DOWN -> {
                    bar(g, x + 1 + i * 2, y + 3 + i * 2, 2, 2, color);
                    bar(g, x + 9 - i * 2, y + 3 + i * 2, 2, 2, color);
                }
                case UP -> {
                    bar(g, x + 1 + i * 2, y + 7 - i * 2, 2, 2, color);
                    bar(g, x + 9 - i * 2, y + 7 - i * 2, 2, 2, color);
                }
                case RIGHT -> {
                    bar(g, x + 3 + i * 2, y + 1 + i * 2, 2, 2, color);
                    bar(g, x + 3 + i * 2, y + 9 - i * 2, 2, 2, color);
                }
                case LEFT -> {
                    bar(g, x + 7 - i * 2, y + 1 + i * 2, 2, 2, color);
                    bar(g, x + 7 - i * 2, y + 9 - i * 2, 2, 2, color);
                }
            }
        }
    }

    static void draw(GuiGraphicsExtractor g, String category, int x, int y, int color) {
        switch (category == null ? "" : category) {
            case "Trackers" -> {
                bar(g, x + 1, y + 7, 2, 4, color);
                bar(g, x + 5, y + 4, 2, 7, color);
                bar(g, x + 9, y + 1, 2, 10, color);
            }
            case "Social" -> {
                bar(g, x + 2, y + 2, 3, 3, color);
                bar(g, x + 7, y + 2, 3, 3, color);
                bar(g, x + 1, y + 6, 5, 4, color);
                bar(g, x + 6, y + 6, 5, 4, color);
            }
            case "Mining" -> {
                bar(g, x + 1, y + 2, 9, 2, color);
                bar(g, x + 3, y + 4, 2, 2, color);
                bar(g, x + 5, y + 6, 2, 2, color);
                bar(g, x + 7, y + 8, 2, 3, color);
            }
            case "Storage" -> {
                bar(g, x + 1, y + 3, 10, 2, color);
                bar(g, x + 1, y + 5, 2, 6, color);
                bar(g, x + 9, y + 5, 2, 6, color);
                bar(g, x + 3, y + 9, 6, 2, color);
                bar(g, x + 5, y + 5, 2, 2, color);
            }
            case "Alerts" -> {
                bar(g, x + 5, y + 1, 2, 2, color);
                bar(g, x + 3, y + 3, 6, 6, color);
                bar(g, x + 1, y + 9, 10, 1, color);
                bar(g, x + 5, y + 10, 2, 2, color);
            }
            case "Visual" -> {
                bar(g, x + 3, y + 2, 6, 1, color);
                bar(g, x + 1, y + 4, 2, 4, color);
                bar(g, x + 9, y + 4, 2, 4, color);
                bar(g, x + 4, y + 5, 4, 3, color);
                bar(g, x + 3, y + 9, 6, 1, color);
            }
            case "Chat" -> {
                bar(g, x + 2, y + 2, 8, 2, color);
                bar(g, x + 1, y + 4, 2, 5, color);
                bar(g, x + 9, y + 4, 2, 5, color);
                bar(g, x + 2, y + 9, 4, 2, color);
                bar(g, x + 6, y + 9, 3, 1, color);
            }
            case "Quality of Life" -> {
                bar(g, x + 5, y + 1, 2, 10, color);
                bar(g, x + 1, y + 5, 10, 2, color);
            }
            case "Dungeons" -> {
                bar(g, x + 1, y + 1, 10, 2, color);
                bar(g, x + 1, y + 3, 2, 8, color);
                bar(g, x + 5, y + 3, 2, 5, color);
                bar(g, x + 9, y + 3, 2, 8, color);
                bar(g, x + 3, y + 9, 8, 2, color);
            }
            default -> {
                for (int dy = 1; dy <= 7; dy += 6)
                    for (int dx = 1; dx <= 7; dx += 6)
                        bar(g, x + dx, y + dy, 4, 4, color);
            }
        }
    }

    static void search(GuiGraphicsExtractor g, int x, int y, int color) {
        bar(g, x + 2, y + 1, 5, 1, color);
        bar(g, x + 1, y + 2, 1, 5, color);
        bar(g, x + 7, y + 2, 1, 5, color);
        bar(g, x + 2, y + 7, 5, 1, color);
        bar(g, x + 8, y + 8, 2, 2, color);
        bar(g, x + 10, y + 10, 2, 2, color);
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        Draw.rect(g, x, y, w, h, color);
    }
}
