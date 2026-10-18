package com.endsight.storage;

import com.endsight.qol.Rarity;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Pet bonuses still learn when the tooltip footer is drawn as separate badges. */
public final class PetsChecks {
    private static int checks;

    public static void main(String[] args) {
        List<Component> lines = List.of(Component.literal("§7Earn §a27%§7 more Void Powder"),
                Component.literal("§7from Crying Obsidian."), Component.literal("\uE100LEGENDARY\uE405\uE100PET\uE405"));
        check(Pets.learn("§7[Lvl 100] §6Test Bal", new Rarity.Info("LEGENDARY", "PET"), lines), "learn without literal rarity prefix");
        check(Pets.powderBuff("LEGENDARY Test Bal", "Crying Obsidian", "Void") == 0.27, "wrapped perk uses metadata rarity");
        check(!Pets.learn("[Lvl 100] Test Bal", new Rarity.Info("LEGENDARY", "PET"), lines), "unchanged perk does not request a save");
        check(Pets.learn("[Lvl 1] Test Bal", new Rarity.Info("EPIC", "PET"),
                List.of(Component.literal("Earn 17% more Void Powder from Crying Obsidian."))), "separate pet rarity");
        check(Pets.powderBuff("EPIC Test Bal", "Crying Obsidian", "Void") == 0.17, "epic bonus does not borrow legendary value");
        check(Pets.powderBuff("LEGENDARY Test Bal", "Crying Obsidian", "Void") == 0.27, "legendary value retained");
        check(Pets.learn("[Lvl 100] Test Scatha", new Rarity.Info("LEGENDARY", "PET"),
                List.of(Component.literal("Earn 21% more Void Powder from all sources."))), "all-source perk");
        check(Pets.powderBuff("LEGENDARY Test Scatha", "Amethyst", "Void") == 0.21, "all-source bonus applies");
        check(!Pets.learn("Other", null, lines), "unknown rarity is not guessed");
        check(!Pets.learn("Other", new Rarity.Info("LEGENDARY", "ACCESSORY"), lines), "non-pet tooltip cannot teach a pet bonus");
        System.out.println(checks + " pet rarity checks passed");
    }

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
}
