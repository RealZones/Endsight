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

    public static void main(String[] args) throws Exception {
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

        // Install deterministic rules without depending on a running game's config.
        java.util.Map<String, Object> fixtures = new java.util.LinkedHashMap<>();
        var readRules = Drops.class.getDeclaredMethod("read", java.util.List.class, java.util.Map.class);
        readRules.setAccessible(true);
        readRules.invoke(null, java.util.List.of("rare Riftstring", "common Null Atom", "rare Summoning Eye"), fixtures);
        var field = Drops.class.getDeclaredField("rules");
        field.setAccessible(true);
        field.set(null, java.util.List.copyOf(fixtures.values()));
        var dedupeField = Drops.class.getDeclaredField("deduplicator");
        dedupeField.setAccessible(true);
        ((DropDeduplicator) dedupeField.get(null)).clear();

        Drops.Drop rift = Drops.parseMessage("RARE DROP! Riftstring", 100, 1_000);
        equal(rift, new Drops.Drop("Riftstring", 1));
        equal(Drops.parseMessage("RARE DROP! Riftstring", 100, 1_050), rift);
        // An identical line in the NEXT packet is another real drop, not a cached null.
        equal(Drops.parseMessage("RARE DROP! Riftstring", 101, 1_051), rift);
        equal(Drops.parseMessage("RARE DROP! Null Atom", 102, 1_052), new Drops.Drop("Null Atom", 0));
        equal(Drops.parseMessage("BONUS! You received 2x Null Atom!", 103, 1_053), new Drops.Drop("Null Atom", 0, 2));
        equal(Drops.parseMessage("RARE DROP! Riftstring x3", 104, 1_054), new Drops.Drop("Riftstring", 1, 3));
        equal(Drops.parseMessage("RARE DROP! 2x Riftstring", 105, 1_055), new Drops.Drop("Riftstring", 1, 2));
        equal(Drops.parseMessage("[DRAGON] Someone: RARE DROP! Riftstring", 106, 1_056), null);
        equal(Drops.parseMessage("[MVP+] Someone: BONUS! You received 2x Null Atom!", 107, 1_057), null);
        equal(Drops.bonus("BONUS! You received 20x Wise Dragon Fragment!"), new Drops.Drop("Wise Dragon Fragment", 0, 20));
        equal(Drops.bonus("BONUS! You received 1x Summoning Eye!"), new Drops.Drop("Summoning Eye", 0));
        equal(Drops.bonus("BONUS! You received 0x Null Atom!"), null);
        equal(Drops.bonus("BONUS! You received 999999999999x Null Atom!"), null);

        equal(Drops.modernLoot("Roll 0.49284180/100 → Null Ovoid"), "Null Ovoid");
        equal(Drops.modernLoot("MF 294.69 PL 66.12 Table 55.59835938/100 → Wise Dragon Helmet + 4 frags"), "Wise Dragon Helmet");
        equal(Drops.modernLoot("MF 380.31 PL 66.12 Table 50.68099857/100 → §aAspect of the Dragons §7+ §f6 §7frags"), "§aAspect of the Dragons");
        equal(Drops.modernLoot("Roll 70.84092822/100 → nothing"), null);
        equal(Drops.modernLoot("[DRAGON] Someone: Roll 0.49284180/100 → Null Ovoid"), null);
        equal(Drops.parseMessage("Roll 0.01/100 → Null Atom", 110, 10_000), new Drops.Drop("Null Atom", 0));
        equal(Drops.parseMessage("RARE DROP! Null Atom", 111, 10_001), null);
        equal(Drops.parseMessage("Roll 0.01/100 → Null Atom", 112, 10_002), new Drops.Drop("Null Atom", 0));
        equal(Drops.parseMessage("MF 380.31 PL 66.12 Table 0.01/100 → §7[Lvl 1] §5Ender Dragon + 6 frags", 113, 10_003),
                new Drops.Drop("[Lvl 1] Ender Dragon", 2));
        equal(Drops.parseMessage("RNGESUS DROP! §7[Lvl 1] §5Ender Dragon", 114, 10_004), null);

        DropDeduplicator matching = new DropDeduplicator();
        Drops.Drop a = new Drops.Drop("Riftstring", 1), b = new Drops.Drop("Null Atom", 0);
        equal(matching.credit(a, DropDeduplicator.Source.DEBUG, 2_000), 1);
        equal(matching.credit(b, DropDeduplicator.Source.DEBUG, 2_001), 1);
        equal(matching.credit(a, DropDeduplicator.Source.ANNOUNCEMENT, 2_002), 0);
        equal(matching.credit(b, DropDeduplicator.Source.ANNOUNCEMENT, 2_003), 0);
        equal(matching.credit(b, DropDeduplicator.Source.ANNOUNCEMENT, 2_004), 1);
        equal(matching.credit(b, DropDeduplicator.Source.BONUS, 2_005), 1);
        matching.clear();
        equal(matching.credit(a, DropDeduplicator.Source.DEBUG, 3_000), 1);
        equal(matching.credit(new Drops.Drop("Riftstring", 1, 3), DropDeduplicator.Source.ANNOUNCEMENT, 3_001), 2);
        matching.clear();
        equal(matching.credit(new Drops.Drop("Riftstring", 1, 3), DropDeduplicator.Source.ANNOUNCEMENT, 4_000), 3);
        equal(matching.credit(a, DropDeduplicator.Source.DEBUG, 4_001), 0);
        equal(matching.credit(a, DropDeduplicator.Source.DEBUG, 6_000), 1);
        equal(matching.credit(a, DropDeduplicator.Source.ANNOUNCEMENT, 7_500), 1);
        matching.clear();
        equal(matching.credit(new Drops.Drop("[Lvl 1] Ender Dragon", 2), DropDeduplicator.Source.DEBUG, 8_000), 1);
        equal(matching.credit(new Drops.Drop("[Lvl 1] Ender Dragon", 3), DropDeduplicator.Source.ANNOUNCEMENT, 8_001), 1);

        // An older copied config reorders its entries ahead of new bundled
        // entries, even though the bundle was read first for default coverage.
        java.util.Map<String, Object> layered = new java.util.LinkedHashMap<>();
        var readLayer = Drops.class.getDeclaredMethod("read", java.util.List.class, java.util.Map.class, boolean.class);
        readLayer.setAccessible(true);
        readLayer.invoke(null, java.util.List.of("epic Dragon Horn", "epic Golden Droplet", "epic New Scale"), layered, false);
        readLayer.invoke(null, java.util.List.of("epic Golden Droplet", "epic Dragon Horn"), layered, true);
        field.set(null, java.util.List.copyOf(layered.values()));
        equal(Drops.orderOf("Golden Droplet") < Drops.orderOf("Dragon Horn"), true);
        equal(Drops.orderOf("Dragon Horn") < Drops.orderOf("New Scale"), true);
        equal(Drops.listing(2), "Golden Droplet, Dragon Horn, New Scale.");

        System.out.println("DropsChecks: " + checks + " checks passed");
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
