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
    /** The Flower of Truth and the Bouquet of Lies: a right-click throws a homing rose. */
    private static final String[] ROSES = {"Flower of Truth", "Bouquet of Lies"};

    public static boolean isZealot(Entity e) {
        if (!(e instanceof EnderMan)) return false;
        Component name = e.getCustomName();
        if (name == null) return false;
        String plain = strip(name.getString());
        return plain.contains(MATCH) && !plain.contains(EXCLUDE);
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
