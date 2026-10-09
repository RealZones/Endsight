package com.endsight.qol;

/**
 * Drop reading, against lines taken from the game log rather than invented.
 *
 * The pet cases are the ones that have gone wrong twice. A pet's tier is its own rarity,
 * which the coloured debug line or announcement carries - and the tier list names
 * the pet rather than the rarity, so it is wrong in both directions: it calls every Ender
 * Dragon legendary and every Tiger rare, when the log has an epic dragon and a gold tiger.
 */
public final class DropsChecks {
    private static int checks;

    public static void main(String[] args) {
        // The loot-table colour is not the pet's rarity. Without a separate colour on
        // the name, wait for the announcement instead of guessing from the list.
        equal(Drops.parse("loot number: 0.09930 → §a[Lvl 1] Ender Dragon"), null);

        // The actual server debug line does colour the name after [Lvl 1]. It is
        // personal loot and must count even before the pet is collected.
        Drops.Drop debugPet = Drops.parse("§7loot number: §f0.64978 §8→ §a§7[Lvl 1] §5Ender Dragon");
        equal(debugPet == null, false);
        equal(debugPet.item(), "[Lvl 1] Ender Dragon");
        equal(debugPet.tier(), 2);

        // The later announcement is the same pet, not a second one.
        Drops.Drop epic = Drops.parse("RNGESUS DROP! §5[Lvl 1] §5Ender Dragon");
        equal(epic, null);

        // §6 is legendary, against a list that says Tiger (pet) is rare. A different pet,
        // so the repeat window does not eat it.
        Drops.Drop legendary = Drops.parse("RNGESUS DROP! §6[Lvl 1] §6Tiger");
        equal(legendary == null, false);
        equal(legendary.tier(), 3);

        // Nothing here checks a tier that comes from the list. The list is read from the
        // game's own config dir, so asking for one outside the game dies in FabricLoader -
        // which is also why the three cases above are the ones worth having: they are
        // decided by the colour of the name and need no list at all.

        System.out.println("DropsChecks: " + checks + " checks passed");
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
