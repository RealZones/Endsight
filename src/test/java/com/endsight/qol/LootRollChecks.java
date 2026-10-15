package com.endsight.qol;

import com.endsight.zealots.Zealots;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import java.util.List;

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
        equal(LootRoll.dragonRoll(dragon), "§8▍ §7Loot §a36.43 §8→ §aAspect of the Dragons");

        // October 12's Golden dragon: the annotated loot line leaked out of the
        // fold and the combined MF/PL line lost pet luck. Frags were mislabeled ×6 boots.
        String[] goldenLines = {
                "dragon: Golden Dragon rank: #3 damage: 196,340,213",
                "drag score: 650 (3 eyes x150 + 200 top-3)",
                "magic find: 401.21 (multiplies rare-table, armor and frozen rolls); pet luck: 66.12",
                "loot number: 86.81384 (the rare table, lower = rarer)",
                "armor roll: 51.710 (needs < 320.96450560 and <= 162.50000000)",
                "rng meter: golden_egg (x1.10 on its own slice of the table)",
                "result: Golden Dragon Boots frags: 6 (3-6 side)",
                "frozen/crystal roll: 0.51710018 (shared frozen/crystal: 0-1) Core < 0.00033090; Fragment [0.00033090, 0.06018084); Crystal < 0.10000000"
        };
        LootRoll.Block golden = block(goldenLines);
        equal(golden.loot, 86.81384);
        equal(golden.dragon.get("pet"), "66.12");
        equal(golden.dragon.get("armorCap"), "162.50000000");
        equal(Zealots.strip(LootRoll.dragonTop(golden)), "▍ Score 650 · MF 401 · PL 66");
        equal(Zealots.strip(LootRoll.dragonRoll(golden)), "▍ Loot 86.81 → Golden Dragon Boots");
        for (String line : goldenLines) golden.raw.add(Component.literal(line));
        Component goldenSummary = LootRoll.hover(LootRoll.dragonRoll(golden), golden);
        List<Component> goldenDetails = LootRollDetails.rows(id((ClickEvent.RunCommand) goldenSummary.getStyle().getClickEvent()));
        equal(LootRollDetailsScreen.copyLine(goldenDetails.get(3)), goldenLines[3]);
        equal(LootRollDetailsScreen.copyLine(goldenDetails.get(4)), goldenLines[4]);

        LootRoll.Block tableDrop = block("dragon: Berserk Dragon rank: #3 damage: 67,286,137",
                "loot number: 44.52835 (the rare table, lower = rarer)",
                "armor roll: skipped (table hit or score < 350 with no eyes)",
                "result: Ancestral Scale frags: 6 (3-6 side)");
        equal(Zealots.strip(LootRoll.dragonRoll(tableDrop)), "▍ Loot 44.53 → Ancestral Scale");

        // October 12's log: the server now prints base -> effective chance rows.
        // These escaped the fold and lost the table bounds used for close-call colour.
        LootRoll.Block effective = block(
                "§7mob: §fNEST_ZEALOT §7magic find: §b223.98 §7pet luck: §d59.95",
                "§7loot number: §f0.41178 §8(0-100, lower = rarer)",
                "§8  §7base 0.00100000% → effective 0.00323980% §8[0.00000000, 0.00323980) §fInfiniEye™",
                "§8  §7base 0.00500000% → effective 0.01619900% §8[0.00323980, 0.01943880) §fWarped Stone",
                "§8  §7base 0.01000000% → effective 0.03239800% §8[0.01943880, 0.05183680) §fSpicy Wart",
                "§8  §7base 0.01000100% → effective 0.03901390% §8[0.05183680, 0.09085070) §f§7[Lvl 1] §6Enderman",
                "§8  §7base 0.05000000% → effective 0.16199000% §8[0.09085070, 0.25284070) §fNull Ovoid",
                "§7result: §cnothing");
        equal(effective.rows.size(), 5);
        equal(effective.rows.get(4)[0], 0.09085070);
        equal(effective.rows.get(4)[1], 0.25284070);
        equal(LootRoll.kill(effective), "§8▍ §fZealot §e0.412");
        equal(effective.read("▶ base 2.00000000% → effective 6.45960000% [5.05539900, 11.51499900) Hot Potato Book"), true);
        equal(effective.rows.getLast()[1], 11.51499900);

        LootRoll.Block nearMiss = block(
                "mob: NEST_DEFENDER magic find: 383.25 pet luck: 59.95",
                "rare roll Goop (1 in 654, 0.15290%) rolled 0.15301 miss");
        String rareLine = "rare roll Goop (1 in 654, 0.15290%) rolled 0.15301 miss";
        nearMiss.raw.add(Component.literal(rareLine));
        Component clickable = LootRoll.hover(LootRoll.kill(nearMiss), nearMiss);
        ClickEvent.RunCommand action = (ClickEvent.RunCommand) clickable.getStyle().getClickEvent();
        equal(action.command().startsWith("/endsightroll "), true);
        equal(LootRollDetailsScreen.copyLine(LootRollDetails.rows(id(action)).getFirst()), rareLine);
        equal(clickable.getString(), LootRoll.kill(nearMiss));
        HoverEvent.ShowText tooltip = (HoverEvent.ShowText) clickable.getStyle().getHoverEvent();
        equal(tooltip.value().getString().contains("rolled 0.15301 miss"), true);
        equal(tooltip.value().getString().contains("Click to pin this hover"), true);
        // The panel removes presentation rules and the base comparison, retaining exact values.
        List<Component> details = LootRollDetails.lines(List.of(Component.literal("══ LOOT DEBUG ══"),
                Component.literal(""), Component.literal("-----"),
                Component.literal("§7loot number: §f82.75201\n§7result: §cnothing"),
                Component.literal("§8  §7base 0.05% → effective 0.21706939% [0.07826939, 0.21706939) Null Ovoid")));
        equal(details.size(), 3);
        equal(LootRollDetailsScreen.copyLine(details.get(0)), "loot number: 82.75201");
        equal(LootRollDetailsScreen.copyLine(details.get(1)), "result: nothing");
        equal(LootRollDetailsScreen.copyLine(details.get(2)), "0.21706939% [0.07826939, 0.21706939) Null Ovoid");
        // Prefixes can cross styled runs; item rarity and hit-marker colors must survive.
        Component styled = Component.literal("▶ ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal("base 0.01000100% → ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("effective 0.03901390% [0.05183680, 0.09085070) [Lvl 1] ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Enderman").withStyle(ChatFormatting.GOLD));
        Component clean = LootRollDetails.displayLine(styled);
        equal(clean.getString(), "▶ 0.03901390% [0.05183680, 0.09085070) [Lvl 1] Enderman");
        equal(clean.getSiblings().getFirst().getStyle().getColor().getValue(), ChatFormatting.GREEN.getColor());
        equal(clean.getSiblings().getLast().getStyle().getColor().getValue(), ChatFormatting.GOLD.getColor());
        equal(LootRollDetails.displayLine(Component.literal("▶ 0.08% [0.226, 0.477) Void Fang")).getString(),
                "▶ 0.08% [0.226, 0.477) Void Fang");
        equal(LootRollDetails.displayLine(Component.literal("base 0.05% → effective unknown [0, 1) Null Ovoid")).getString(),
                "base 0.05% → effective unknown [0, 1) Null Ovoid");
        // Identical rounded labels must still open the correct individual kill's exact details.
        var source = Component.literal("loot number: 0.15301");
        ClickEvent.RunCommand first = LootRollDetails.action(List.of(source));
        source.append(" changed later");
        ClickEvent.RunCommand second = LootRollDetails.action(List.of(Component.literal("loot number: 0.15309")));
        equal(first.equals(second), false);
        equal(LootRollDetailsScreen.copyLine(LootRollDetails.rows(id(first)).getFirst()), "loot number: 0.15301");
        equal(LootRollDetailsScreen.copyLine(LootRollDetails.rows(id(second)).getFirst()), "loot number: 0.15309");
        ClickEvent.RunCommand latest = second;
        for (int i = 0; i < LootRollDetails.MAX_ROLLS; i++) latest = LootRollDetails.action(List.of(Component.literal("result: nothing")));
        equal(LootRollDetails.rows(id(first)), null);
        equal(LootRollDetails.rows(id(latest)).size(), 1);

        // Drops' line and someone else's paste are never part of a block.
        LootRoll.Block none = new LootRoll.Block();
        equal(none.read("loot number: 0.10838 → Null Atom"), false);
        equal(none.read("loot number: 86.81384 (the rare table, lower = rarer) → Golden Dragon Boots"), false);
        equal(none.read("[MVP+] Someone: loot number: 86.81384 (the rare table, lower = rarer)"), false);
        equal(none.read("[DRAGON] NotSecrets: ▶ rare roll Implosion Scroll (1 in 5,250, 0.01905%) rolled 0.00480 HIT"), false);
        equal(none.read("[DRAGON] NotSecrets: base 0.05000000% → effective 0.16199000% [0.09085070, 0.25284070) Null Ovoid"), false);
        equal(none.read("base 0.05% → effective unknown [0, 1) Null Ovoid"), false);

        streakBonusAndBoundaries();

        // Decimals follow how close the roll is to an end.
        equal(LootRoll.fmt(0.00480), "0.0048");
        equal(LootRoll.fmt(54.15717), "54.16");
        equal(LootRoll.fmt(99.98), "99.98");

        System.out.println(checks + " loot roll checks passed");
    }

    private static void streakBonusAndBoundaries() {
        // The short bonus annotation is the reported streak format. The local logs
        // confirm the same short `rare roll: Name (1 in N)` form for other side drops;
        // the bonus itself is independent of the table's result and loot number.
        LootRoll.Block streak = block("mob: ? magic find: 354.59 pet luck: 59.95",
                "loot number: 55.81765 (0-100, lower = rarer)", "result: Summoning Eye",
                "rare roll: bonus Null Atom (1 in 4)");
        streak.observe("NICE! SLAYER BOSS SLAIN!");
        equal(streak.result, "Summoning Eye");
        equal(streak.rares.size(), 1);
        equal(streak.rares.getFirst().needs(), 25.0);
        equal(streak.rares.getFirst().hit(), true);
        equal(Double.isNaN(streak.rares.getFirst().rolled()), true);
        equal(Zealots.strip(LootRoll.kill(streak)), "▍ Slayer loot 55.82 → Summoning Eye · ✔ bonus Null Atom");
        equal(streak.read("result: bonus Null Atom"), true);
        equal(streak.read("result: Null Atom"), true);
        equal(streak.result, "Summoning Eye");
        equal(streak.rares.size(), 1);
        equal(streak.read("result: nothing"), false);
        equal(streak.result, "Summoning Eye");

        // Detailed and short annotations can describe the same extra outcome. Keep
        // one bonus, and prefer its actual rolled value when it is supplied.
        equal(streak.read("▶ rare roll: bonus Null Atom (1 in 4, 25%) rolled 12.5 HIT"), true);
        equal(streak.rares.size(), 1);
        equal(streak.rares.getFirst().rolled(), 12.5);
        equal(streak.read("rare roll: bonus Null Atom (1 in 4)"), true);
        equal(streak.rares.size(), 1);

        LootRoll.Block bonusBeforeMain = block("mob: ? magic find: 354.59",
                "result: bonus: Null Atom", "result: Transmission Tuner");
        equal(bonusBeforeMain.result, "Transmission Tuner");
        equal(bonusBeforeMain.rares.size(), 1);
        equal(Zealots.strip(LootRoll.kill(bonusBeforeMain)), "▍ Loot roll → Transmission Tuner · ✔ bonus Null Atom");
        LootRoll.Block emptyMain = block("mob: ? magic find: 354.59", "loot number: 50", "result: nothing");
        boolean previousFilter = LootFilter.on();
        try {
            LootFilter.module().setEnabled().accept(true);
            equal(emptyMain.worthShowing(), false);
            equal(emptyMain.read("rare roll: bonus Null Atom (1 in 4)"), true);
            equal(emptyMain.result, "nothing");
            equal(emptyMain.worthShowing(), true);
        } finally {
            LootFilter.module().setEnabled().accept(previousFilter);
        }
        equal(emptyMain.read("[MVP+] Someone: rare roll: bonus Null Atom (1 in 4)"), false);
        equal(emptyMain.read("rare roll: bonus Null Atom (1 in 0)"), false);
        equal(LootRoll.rollAnchor("rare roll: bonus Null Atom (1 in 4)"), false);

        // Observed headers are anchored. A player paste cannot flush another kill,
        // and compatibility end markers close a block rather than open a new one.
        equal(LootRoll.debugHeader("------------ LOOT DEBUG ------------"), true);
        equal(LootRoll.debugHeader("[MVP+] Someone: LOOT DEBUG"), false);
        equal(LootRoll.debugHeader("------------ END LOOT DEBUG ------------"), false);
        equal(LootRoll.debugEnd("------------ END LOOT DEBUG ------------"), true);
        equal(LootRoll.debugEnd("------------ LOOT DEBUG END ------------"), true);
        equal(LootRoll.debugEnd("[MVP+] Someone: END LOOT DEBUG"), false);
        equal(LootRoll.rollAnchor("[MVP+] Someone: mob: ? magic find: 354.59"), false);
        equal(streak.startsNext("mob: NEST_ZEALOT magic find: 223.98 pet luck: 59.95"), true);
        equal(streak.startsNext("[MVP+] Someone: mob: NEST_ZEALOT magic find: 223.98"), false);
        equal(streak.startsNext("result: Null Atom"), false);
        equal(new LootRoll.Block().startsNext("mob: ? magic find: 354.59"), false);
        LootRoll.Block firstDragon = block("dragon: Golden Dragon rank: #3 damage: 196,340,213");
        equal(firstDragon.startsNext("dragon: Berserk Dragon rank: #3 damage: 67,286,137"), true);
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

    private static long id(ClickEvent.RunCommand action) {
        return Long.parseLong(action.command().substring("/endsightroll ".length()));
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("check " + checks + ": expected " + expected + " but got " + actual);
        }
    }
}
