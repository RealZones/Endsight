package com.endsight.dungeon;

import com.endsight.hud.Area;
import java.util.List;

public final class DungeonContextChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Object world = new Object();
        DungeonContext.State state = new DungeonContext.State();
        state.sidebar(world, List.of("§7◇ §cThe Catacombs (F7)", "Keys: 1 x 0x"), 1_000);
        yes(state.activeAt(world, 1_001), "live Catacombs sidebar enables dungeon HUDs");
        yes(Area.placeOf("◇ The Catacombs (F7)") == Area.Where.DUNGEON, "Catacombs is distinct from the End");
        var where = Area.class.getDeclaredField("where");
        where.setAccessible(true);
        Object oldArea = where.get(null);
        try {
            where.set(null, Area.Where.DUNGEON);
            yes(!Area.end(), "End-specific HUDs stay hidden in dungeons");
            yes(Area.dungeon(), "Catacombs is a dungeon area");
        } finally { where.set(null, oldArea); }
        yes(!state.activeAt(new Object(), 1_001), "different world cannot use last run");
        state.sidebar(world, List.of("Date", "Updating..."), 1_100);
        yes(state.activeAt(world, 1_101), "short partial sidebar update retains run");
        yes(!state.activeAt(world, 61_000), "missing dungeon evidence expires");
        state.message(world, "[DRAGON] Someone: PUZZLE! Three Weirdos", 70_000);
        yes(!state.activeAt(world, 70_001), "player puzzle paste cannot enable dungeon mode");
        state.message(world, "PUZZLE! Three Weirdos - One of the three always lies.", 71_000);
        yes(state.activeAt(world, 71_001), "personal puzzle entry bridges scoreboard delay");
        state.sidebar(world, List.of("◇ Dragon's Den"), 71_100);
        yes(!state.activeAt(world, 71_101), "outside sidebar closes old dungeon state");
        state.message(world, "PUZZLE! Three Weirdos", 72_000);
        state.message(world, "Warping to the hub...", 72_001);
        yes(!state.activeAt(world, 72_002), "proxy transfer hint closes old dungeon state");
        state.sidebar(new Object(), List.of("Unknown"), 73_000);
        yes(!state.activeAt(world, 73_001), "world change clears run");
        state.clear();
        yes(!state.activeAt(null, 73_002), "disconnect clears run");
        System.out.println("Dungeon context checks passed: " + checks);
    }
    private static void yes(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
}
