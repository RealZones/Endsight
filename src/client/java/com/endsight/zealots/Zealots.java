package com.endsight.zealots;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;

/**
 * What the zealot modules have to agree on: which endermen count and what a scythe is.
 *
 * One place for it, because two copies of "is this a zealot" would drift the first time
 * the server renamed something, and this one is the copy that was checked against play.
 */
public final class Zealots {

    private Zealots() {
    }

    private static final String MATCH = "Zealot";
    /** Bruisers live in the layer below; nobody farming eyes is counting them. */
    private static final String EXCLUDE = "Bruiser";
    private static final String SCYTHE = "Scythe";
    /**
     * The Rift replaces every mob's name with "Dinnerbone" - the vanilla trick that renders
     * it upside down - so nothing there says "Zealot" and every name-based check went blind:
     * no tracker, no beam. A masked enderman is therefore counted as a zealot, and the one
     * enderman that never is, is the boss: both Voidgloom and Riftborn say Seraph.
     *
     * A nameless enderman is still left alone. Masked mobs are named, not nameless, so this
     * covers the Rift without plain vanilla endermen starting to count.
     */
    private static final String MASK = "Dinnerbone";
    private static final String BOSS = "Seraph";
    /** The Flower of Truth and the Bouquet of Lies: a right-click throws a homing rose. */
    private static final String[] ROSES = {"Flower of Truth", "Bouquet of Lies"};

    public static boolean isZealot(Entity e) {
        if (!(e instanceof EnderMan)) return false;
        Component name = e.getCustomName();
        if (name == null) return false;
        String plain = strip(name.getString());
        if (plain.contains(BOSS)) return false;
        if (plain.equals(MASK)) return true;
        return plain.contains(MATCH) && !plain.contains(EXCLUDE);
    }

    /** The bruisers, the layer below - counted by the tracker, left alone by everything that aims. */
    public static boolean isBruiser(Entity e) {
        if (!(e instanceof EnderMan)) return false;
        Component name = e.getCustomName();
        return name != null && strip(name.getString()).contains(EXCLUDE);
    }

    /** Anything the tracker counts a kill of. */
    public static boolean isTracked(Entity e) {
        return isZealot(e) || isBruiser(e);
    }

    public static boolean holdingScythe(Player player) {
        return player.getMainHandItem().getHoverName().getString().contains(SCYTHE);
    }

    /** How many roses a right-click throws: one for the Flower, three for the Bouquet, none otherwise. */
    public static int roses(Player player) {
        String name = player.getMainHandItem().getHoverName().getString();
        if (name.contains(ROSES[1])) return 3;
        if (name.contains(ROSES[0])) return 1;
        return 0;
    }

    public static String strip(String s) {
        return s == null ? "" : s.replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "");
    }
}
