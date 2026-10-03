package com.endsight.storage;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Rarity points, counting only the strongest accessory in each upgrade family. */
final class MagicalPower {
    private MagicalPower() { }

    record Accessory(String name, String rarity) { }

    static int points(String rarity) {
        return switch (rarity.toUpperCase(Locale.ROOT)) {
            case "COMMON", "SPECIAL" -> 3;
            case "UNCOMMON", "VERY SPECIAL" -> 5;
            case "RARE" -> 8;
            case "EPIC" -> 12;
            case "LEGENDARY" -> 16;
            case "MYTHIC" -> 22;
            default -> 0;
        };
    }

    static Map<String, String> parents(Map<String, List<String>> ingredients,
                                       Set<String> catalog, Set<String> bagNames) {
        Map<String, String> parent = new HashMap<>();
        for (String name : catalog) {
            for (String ingredient : ingredients.getOrDefault(name, List.of())) {
                // Some dropped base talismans have no recipe/category entry, but their
                // crafted upgrades still name them as ingredients.
                if (!name.equals(ingredient) && (catalog.contains(ingredient) || bagNames.contains(ingredient))) {
                    parent.put(name, ingredient);
                    break;
                }
            }
        }
        return parent;
    }

    static int total(List<Accessory> accessories, Map<String, String> parent) {
        Map<String, Integer> best = new HashMap<>();
        for (Accessory accessory : accessories) {
            int value = points(accessory.rarity());
            if (value == 0) continue;
            String family = accessory.name();
            Set<String> seen = new java.util.HashSet<>();
            while (seen.add(family) && parent.containsKey(family)) family = parent.get(family);
            best.merge(family, value, Math::max);
        }
        return best.values().stream().mapToInt(Integer::intValue).sum();
    }
}
