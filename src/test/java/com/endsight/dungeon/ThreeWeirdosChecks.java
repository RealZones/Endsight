package com.endsight.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class ThreeWeirdosChecks {
    private static int checks;
    private static final String ENTRY = "PUZZLE! Three Weirdos - One of the three always lies. Talk to them, then open the right chest.";
    private static final String[] OBSERVED = {
            "[NPC] Wren: Whatever you do, Barnaby's chest is not the one.",
            "[NPC] Ferris: The reward is in my chest.",
            "[NPC] Barnaby: Don't trust Wren, they're lying."
    };

    public static void main(String[] args) {
        for (int[] order : List.of(new int[]{0, 1, 2}, new int[]{0, 2, 1}, new int[]{1, 0, 2},
                new int[]{1, 2, 0}, new int[]{2, 0, 1}, new int[]{2, 1, 0})) {
            ThreeWeirdosLogic model = started();
            model.accept(OBSERVED[order[0]]);
            check(model.solution() == null, "one clue cannot give a chest answer");
            model.accept(OBSERVED[order[1]]);
            check(model.solution() == null, "two clues cannot give a chest answer");
            model.accept(OBSERVED[order[2]]);
            check("Ferris".equals(model.solution()), "observed exactly-one-liar puzzle solves Ferris in every dialogue order");
        }

        ThreeWeirdosLogic model = observed();
        model.accept(OBSERVED[1]);
        check(model.answered() == 3 && "Ferris".equals(model.solution()), "repeated dialogue is not an extra speaker or constraint");
        check(!model.accept("[DRAGON] WesternIvey: PUZZLE FAIL! The machine won Tic Tac Toe"), "player-pasted failure is not a room event");
        check("Ferris".equals(model.solution()), "player paste does not erase answer");
        check(!model.accept("[VIP] Example: " + OBSERVED[0]), "player-pasted NPC clue is rejected");
        model.accept("You picked Ferris's chest. Correct!");
        check(!model.active() && model.solution() == null, "observed success clears old chest immediately");
        model = observed();
        model.accept("PUZZLE SOLVED! Three Weirdos");
        check(!model.active(), "observed puzzle solved line clears room");
        model = observed();
        model.accept("PUZZLE! Tic Tac Toe - Take your turn.");
        check(!model.active(), "a different room entry clears old puzzle");
        model = observed();
        model.accept(ENTRY);
        check(model.active() && model.answered() == 0, "new room entry clears all former clues");
        model.clear();
        model.accept(OBSERVED[0]);
        check(model.answered() == 0, "NPC dialogue outside an entered room is ignored");

        model = started();
        model.accept("[NPC] Wren: Whatever you do, Barnaby's chest is not the one.");
        model.accept("[NPC] Ferris: Here is a completely unknown clue.");
        model.accept(OBSERVED[2]);
        check(model.unsupported() && model.solution() == null, "unknown clue never produces a guessed chest");
        model = observed();
        model.accept("[NPC] Ferris: The reward is not in my chest.");
        check(model.unsupported() && model.solution() == null, "conflicting second statement cannot overwrite a constraint");
        model = observed();
        model.accept("[NPC] Stranger: The reward is in my chest.");
        check(model.unsupported() && model.solution() == null, "fourth NPC cannot silently replace one of the three");
        model = started();
        model.accept("[NPC] Wren: Missing's chest is not the one.");
        model.accept(OBSERVED[1]);
        model.accept(OBSERVED[2]);
        check(model.unsupported() && model.solution() == null, "reference to an unknown NPC is unsupported");
        model = started();
        for (String name : List.of("A", "B", "C")) model.accept("[NPC] " + name + ": The reward is not in my chest.");
        check(!model.unsupported() && model.solution() == null, "all three possible reward owners stays ambiguous");
        model = started();
        for (String name : List.of("A", "B", "C")) model.accept("[NPC] " + name + ": The reward is in my chest.");
        check(model.solution() == null, "an unsatisfiable one-liar puzzle gives no answer");

        check(ThreeWeirdosLogic.entry("§dPUZZLE! Three Weirdos – One of the three always lies."), "formatted room entry and en dash accepted");
        check(!ThreeWeirdosLogic.entry("[MVP+] Player: " + ENTRY), "player-pasted entry does not arm solver");
        check(ThreeWeirdosLogic.parse("Wren", "The treasure isn't in my chest!").kind() == ThreeWeirdosLogic.Kind.NOT_CHEST, "self negative chest statement parsed");
        // The exact three lines this server produced, which previously parsed 1 of 3.
        check(ThreeWeirdosLogic.parse("Mabel", "My chest is empty, I promise.").kind() == ThreeWeirdosLogic.Kind.NOT_CHEST, "empty-chest wording parsed");
        check(ThreeWeirdosLogic.parse("Tamsin", "My chest is empty.").kind() == ThreeWeirdosLogic.Kind.NOT_CHEST, "empty-chest wording without the promise parsed");
        check(ThreeWeirdosLogic.parse("Nell", "Whatever you do, Mabel's chest is not the one.").kind() == ThreeWeirdosLogic.Kind.NOT_CHEST, "named negative chest parsed");
        check(ThreeWeirdosLogic.parse("Wren", "Barnaby's chest is the one.").kind() == ThreeWeirdosLogic.Kind.CHEST, "named positive chest statement parsed");
        check(ThreeWeirdosLogic.parse("Wren", "The reward is not in Ferris's chest.").subject().equals("ferris"), "named negative reward statement parsed");
        check(ThreeWeirdosLogic.parse("Wren", "The treasure is in Ferris's chest.").kind() == ThreeWeirdosLogic.Kind.CHEST, "named positive reward statement parsed");
        check(ThreeWeirdosLogic.parse("Wren", "Ferris is telling the truth.").kind() == ThreeWeirdosLogic.Kind.TRUTHFUL, "truth dependency parsed");
        check(ThreeWeirdosLogic.parse("Wren", "Ferris is lying.").kind() == ThreeWeirdosLogic.Kind.LYING, "lie dependency parsed");
        check(ThreeWeirdosLogic.parse("Wren", "My chest contains the reward.").kind() == ThreeWeirdosLogic.Kind.CHEST, "self containment statement parsed");
        model = started();
        model.accept(OBSERVED[0]); model.accept(OBSERVED[1]);
        model.accept("[NPC] Barnaby: Don’t trust Wren, they’re lying.");
        check("Ferris".equals(model.solution()), "curly apostrophes preserve observed logical meaning");

        Vec3 center = new Vec3(.5, .5, .5);
        BlockPos near = new BlockPos(1, 0, 0), far = new BlockPos(3, 0, 0);
        check(near.equals(ThreeWeirdos.nearestChest(center, List.of(far, near))), "closest nearby physical chest selected");
        check(ThreeWeirdos.nearestChest(center, List.of(near, new BlockPos(-1, 0, 0))) == null, "equally close chests are not guessed");
        check(ThreeWeirdos.nearestChest(center, List.of(new BlockPos(5, 0, 0))) == null, "distant room chest cannot be borrowed");
        check(ThreeWeirdos.nearestChest(center, List.of()) == null, "missing physical chest leaves text answer only");
        System.out.println(checks + " Three Weirdos checks passed");
    }

    private static ThreeWeirdosLogic started() {
        ThreeWeirdosLogic model = new ThreeWeirdosLogic();
        model.accept(ENTRY);
        return model;
    }
    private static ThreeWeirdosLogic observed() {
        ThreeWeirdosLogic model = started();
        for (String line : OBSERVED) model.accept(line);
        return model;
    }
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
}
