package com.endsight.storage;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

public final class RecipesChecks {
    public static void main(String[] args) throws Exception {
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
        cacheInvalidation();
        System.out.println("Recipe name, Magical Power and render-cache checks passed");
    }

    private static void cacheInvalidation() throws Exception {
        Method read = method(Recipes.class, "readRecipes", List.class, boolean.class);
        read.invoke(null, List.of(recipe("Cache Root", 1, "2*Cache Part"),
                recipe("Cache Part", 1, "3*Cache Dust"), "@Accessories\tCache Root"), false);
        var recipesField = Recipes.class.getDeclaredField("RECIPES");
        recipesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Recipes.Recipe> recipes = (Map<String, Recipes.Recipe>) recipesField.get(null);
        Recipes.Recipe root = recipes.get("Cache Root");
        List<Recipes.Ingredient> cost = Recipes.fullCost(root, Map.of());
        check(cost.equals(List.of(new Recipes.Ingredient("Cache Dust", 6))));
        check(Recipes.fullCost(root, Map.of()) == cost);

        Method uses = method(Recipes.class, "usesOf", String.class);
        Object oldUses = uses.invoke(null, "Cache Dust");
        check(uses.invoke(null, "Cache Dust") == oldUses);
        Method closest = method(Recipes.class, "closest", Map.class);
        Map<String, Integer> have = Map.of("Cache Part", 1);
        Object oldClosest = closest.invoke(null, have);
        check(closest.invoke(null, have) == oldClosest);

        // The number of recipes is unchanged. Output counts and edges can still
        // change, and every derived result must stop serving the previous graph.
        read.invoke(null, List.of(recipe("Cache Part", 2, "3*Cache Grit")), false);
        check(Recipes.fullCost(root, Map.of()).equals(List.of(new Recipes.Ingredient("Cache Grit", 3))));
        check(((List<?>) uses.invoke(null, "Cache Dust")).isEmpty());
        check(closest.invoke(null, have) != oldClosest);

        Method visible = method(Recipes.class, "visible");
        Object oldVisible = visible.invoke(null);
        check(visible.invoke(null) == oldVisible);
        read.invoke(null, List.of(recipe("Cache Added", 1, "1*Cache Dust")), false);
        check(((List<?>) visible.invoke(null)).contains("Cache Added"));

        Method forgeRead = method(ForgeRecipes.class, "readRecipes", List.class, boolean.class);
        forgeRead.invoke(null, List.of("Refining\tCache Ingot\t1m\t2*Cache Ore\t"), false);
        check(ForgeRecipes.info("Cache Ingot").durationMs() == 60_000);
        long revision = ForgeRecipes.revision();
        forgeRead.invoke(null, List.of("Refining\tCache Ingot\t2m\t2*Cache Ore\t"), false);
        check(ForgeRecipes.revision() > revision);
        check(ForgeRecipes.info("Cache Ingot").durationMs() == 120_000);
    }

    private static String recipe(String name, int count, String ingredient) {
        return name + "\t" + count + "\t" + ingredient + "\t".repeat(8);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Recipe name boundary mismatch");
    }
}
