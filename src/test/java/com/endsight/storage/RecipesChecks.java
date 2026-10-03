package com.endsight.storage;

import java.util.List;
import java.util.Map;

public final class RecipesChecks {
    public static void main(String[] args) {
        check(!Recipes.matchesRecipeName("Defender Talisman", "Ender Talisman"));
        check(Recipes.matchesRecipeName("Ender Talisman", "Ender Talisman"));
        check(Recipes.matchesRecipeName("Fierce Ender Talisman ✪✪✪✪", "Ender Talisman"));
        check(!Recipes.matchesRecipeName("Defender Talisman", "Defender Ring"));
        int mp = MagicalPower.total(List.of(
                new MagicalPower.Accessory("Wilted Rose Ring", "RARE"),
                new MagicalPower.Accessory("Golden Ghoul Talisman", "RARE"),
                new MagicalPower.Accessory("Zealot Artifact", "LEGENDARY"),
                new MagicalPower.Accessory("Rose Artifact", "EPIC"),
                new MagicalPower.Accessory("Zombie Artifact", "EPIC"),
                new MagicalPower.Accessory("Rose Ring", "RARE"),
                new MagicalPower.Accessory("Warden Talisman", "EPIC"),
                new MagicalPower.Accessory("Personal Compactor 5000", "RARE"),
                new MagicalPower.Accessory("Spare Change Talisman", "RARE"),
                new MagicalPower.Accessory("Ender Talisman", "UNCOMMON"),
                new MagicalPower.Accessory("Soulflow Supercell", "LEGENDARY"),
                new MagicalPower.Accessory("Void Ring", "EPIC")),
                Map.of("Rose Artifact", "Rose Ring", "Rose Ring", "Rose Talisman"));
        check(mp == 117);
        check(MagicalPower.points("MYTHIC") == 22);
        Map<String, String> zombie = MagicalPower.parents(
                Map.of("Zombie Artifact", List.of("Zombie Ring"),
                        "Zombie Ring", List.of("Zombie Talisman")),
                java.util.Set.of("Zombie Artifact", "Zombie Ring"),
                java.util.Set.of("Zombie Artifact", "Zombie Talisman"));
        check(MagicalPower.total(List.of(
                new MagicalPower.Accessory("Zombie Artifact", "EPIC"),
                new MagicalPower.Accessory("Zombie Talisman", "UNCOMMON")), zombie) == 12);
        System.out.println("7 recipe and Magical Power checks passed");
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Recipe name boundary mismatch");
    }
}
