package com.endsight.dungeon;

import java.util.List;

/** Selection never substitutes an unrelated map when the dungeon map is ambiguous. */
public final class DungeonMapChecks {
    private static int checks;

    public static void main(String[] args) {
        check(DungeonMap.dungeonName("§aMagical Map"), "formatted Magical Map is explicit");
        check(DungeonMap.dungeonName(" Dungeon Map "), "trimmed Dungeon Map is explicit");
        check(DungeonMap.dungeonName("Catacombs Map"), "Catacombs Map is explicit");
        check(!DungeonMap.dungeonName("Map to the Catacombs"), "unrecognised descriptive map is not guessed");
        check(!DungeonMap.dungeonName(null), "missing name rejected");
        check(DungeonMap.choose(List.of(), 3) == null, "no map clears a previous id");
        check(DungeonMap.choose(List.of(map(1, "Map", false)), null).equals(1), "unique server map fallback");
        check(DungeonMap.choose(List.of(map(1, "Map", false), map(1, "Map", true)), null).equals(1),
                "copies of one id are still unique");
        check(DungeonMap.choose(List.of(map(1, "Map", true), map(2, "Map", false)), null) == null,
                "held ordinary map does not win an ambiguous inventory");
        check(DungeonMap.choose(List.of(map(1, "Map", true), map(2, "Magical Map", false)), null).equals(2),
                "named dungeon map wins over held ordinary map");
        check(DungeonMap.choose(List.of(map(1, "Dungeon Map", false), map(2, "Magical Map", false)), null) == null,
                "two named maps without evidence are ambiguous");
        check(DungeonMap.choose(List.of(map(1, "Dungeon Map", false), map(2, "Magical Map", true)), null).equals(2),
                "held named dungeon map selects a map explicitly");
        check(DungeonMap.choose(List.of(map(1, "Dungeon Map", false), map(2, "Magical Map", false)), 1).equals(1),
                "same-world named selection remains stable");
        check(DungeonMap.choose(List.of(map(1, "Map", false), map(2, "Map", false)), 1) == null,
                "remembered unlabeled map cannot override new ambiguity");
        check(DungeonMap.choose(List.of(map(1, "Dungeon Map", false), map(1, "Dungeon Map", true),
                map(2, "Magical Map", false)), null).equals(1), "held duplicate keeps explicit selection");
        System.out.println(checks + " dungeon map checks passed");
    }

    private static DungeonMap.Candidate map(int id, String name, boolean held) {
        return new DungeonMap.Candidate(id, name, held);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
