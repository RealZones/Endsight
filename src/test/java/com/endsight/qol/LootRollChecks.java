package com.endsight.qol;

import com.endsight.zealots.Zealots;

/**
 * The detailed roll's fold, against blocks taken from the game log rather than invented.
 */
public final class LootRollChecks {
    private static int checks;

    public static void main(String[] args) {
        // A zealot that got nothing: the table and both rolls fold to one line.
        equal(fold(
                "§7mob: §fNEST_ZEALOT §7magic find: §b384.75 §8(x3.85) §7pet luck: §d59.95",
                "§7loot number: §f2.73060 §8(0-100, lower = rarer)",
                "§8  §70% §8[0.000, 0.005) §fInfiniEye™",
                "§8  §70.01% §8[0.080, 0.138) §f§7[Lvl 1] §6Enderman",
                "§8  §70.05% §8[0.138, 0.388) §fNull Ovoid",
                "§7result: §cnothing",
                "§8  §7rare roll §fSpecial Zealot §8(1 in 347, 0.28839%) §7rolled §f24.92616 §8miss"),
                "§8▍ §fZealot §f2.73 §8· §7Special Zealot §824.93");

        // Within double of the table's top row is a close call: Null Ovoid ends at 0.388.
        equal(fold(
                "§7mob: §fNEST_ZEALOT §7magic find: §b384.75 §8(x3.85) §7pet luck: §d59.95",
                "§7loot number: §f0.41178 §8(0-100, lower = rarer)",
                "§8  §70.05% §8[0.138, 0.388) §fNull Ovoid",
                "§7result: §cnothing"),
                "§8▍ §fZealot §e0.412");

        // The roll that spawned a Goop: a hit is a tick, not a number.
        equal(fold(
                "§7mob: §fNEST_DEFENDER §7magic find: §b383.25 §8(x3.83) §7pet luck: §d59.95",
                "§8  §70% §8[0.000, 0.004) §fCombat XP Boost",
                "§7result: §cnothing",
                "§a▶ §7rare roll §fGoop §8(1 in 654, 0.15290%) §7rolled §f0.15001 §aHIT"),
                "§8▍ §fDefender §8· §a✔ Goop");

        // An unknown source stays neutral unless the same block includes a slayer clear.
        equal(fold(
                "§7mob: §f? §7magic find: §b382.25 §8(x3.82) §7pet luck: §d59.95",
                "§8  §70.25% §8[0.000, 1.106) §f§7[Lvl 1] §6Goop",
                "§a▶ §72% §8[4.928, 12.573) §fGooey Talisman",
                "§7result: §aGooey Talisman"),
                "§8▍ §fLoot roll §8→ §aGooey Talisman");

        LootRoll.Block slayer = block(
                "§7mob: §f? §7magic find: §b354.59 §8(x3.55) §7pet luck: §d59.95",
                "§7loot number: §f55.81765 §8(0-100, lower = rarer)",
                "§7result: §aSummoning Eye");
        slayer.observe("NICE! SLAYER BOSS SLAIN!");
        equal(LootRoll.kill(slayer), "§8▍ §fSlayer loot §a55.82 §8→ §aSummoning Eye");

        // The old dragon fold stopped at "skipped" and let the rest of the block out raw.
        LootRoll.Block dragon = block(
                "§7loot number: §f36.42911",
                "§7armor roll: §8skipped (table hit or score < 350 with no eyes)",
                "§7rng meter: §fender_dragon_pet_legendary §8(x1.10 on its own slice of the table)",
                "§7result: §aAspect of the Dragons §7frags: §f4 §8(3-6 side)",
                "§7frozen/crystal roll: §f0.849 §8(same number as the armor roll)");
        equal(dragon.read, true);
        equal(LootRoll.dragonRoll(dragon), "§8▍ §8loot §a36.43  §8→ §aAspect of the Dragons §7×4");

        // Drops' line and someone else's paste are never part of a block.
        LootRoll.Block none = new LootRoll.Block();
        equal(none.read("loot number: 0.10838 → Null Atom"), false);
        equal(none.read("[DRAGON] NotSecrets: ▶ rare roll Implosion Scroll (1 in 5,250, 0.01905%) rolled 0.00480 HIT"), false);

        // Decimals follow how close the roll is to an end.
        equal(LootRoll.fmt(0.00480), "0.0048");
        equal(LootRoll.fmt(54.15717), "54.16");
        equal(LootRoll.fmt(99.98), "99.98");

        System.out.println(checks + " loot roll checks passed");
    }

    private static LootRoll.Block block(String... lines) {
        LootRoll.Block b = new LootRoll.Block();
        for (String l : lines) {
            if (!b.read(Zealots.strip(l).trim())) throw new AssertionError("not read: " + l);
        }
        return b;
    }

    private static String fold(String... lines) {
        return LootRoll.kill(block(lines));
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError("check " + checks + ": expected " + expected + " but got " + actual);
        }
    }
}
