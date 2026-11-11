package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.List;

/**
 * The arrow device's routes on Dragsim's own board, and the click count against a server
 * with real ping: a player hammering right click as fast as a tick allows must land every
 * arrow on its target, the extra clicks stopped, at any ping.
 */
public final class ArrowAlignChecks {
    private static int checks;

    public static void main(String[] args) {
        // The board in the 2026-11-09 18:12 recording (x = 998), as its frames first read.
        int[][] cells = {{124,75,2},{124,76,2},{124,77,7},{124,78,2},{124,79,7},{123,75,4},{123,79,6},
                {122,75,1},{122,79,1},{121,75,5},{121,77,6},{121,79,5},{120,75,3},{120,76,5},{120,77,6}};
        List<ArrowAlignLogic.Frame> frames = new ArrayList<>();
        for (int[] c : cells) frames.add(new ArrowAlignLogic.Frame(c[0], c[1], c[2]));
        ArrowAlignLogic.Board board = ArrowAlignLogic.locate(frames);
        check(board != null && board.originY() == 120 && board.originZ() == 75, "Dragsim's board is found");
        check(board.targetAt(124, 78) == 1 && board.targetAt(121, 79) == 7, "teammates' finished rotations are its targets");
        frames.removeFirst();
        check(ArrowAlignLogic.locate(frames) == null, "a partial board is left alone");
        check(ArrowAlignLogic.clicks(7, 1) == 2 && ArrowAlignLogic.clicks(1, 7) == 6 && ArrowAlignLogic.clicks(3, 3) == 0,
                "frames turn one way only");

        for (int rtt = 50; rtt <= 400; rtt += 25)
            for (int need = 0; need <= 7; need++) {
                long[] r = spam(need, rtt, -1);
                check(r[0] == need, need + " needed at " + rtt + " ms let " + r[0] + " clicks through");
                check(r[1] == need, need + " needed at " + rtt + " ms ended on " + r[1]);
            }
        long[] lost = spam(5, 134, 1);
        check(lost[1] == 5 && lost[0] == 6, "a lost click is made up once, not a lap: " + lost[0] + " sent, ended on " + lost[1]);
        System.out.println(checks + " Arrow Align checks passed");
    }

    /**
     * Fifteen clicks, one a tick, on a frame needing {@code need}; the server takes each a
     * half trip later and the client sees it a whole trip later. Returns {clicks let
     * through, rotation the arrow ends on}. {@code drop} is a click the server never gets.
     */
    private static long[] spam(int need, int rtt, int drop) {
        ArrowAlignLogic.Count count = new ArrowAlignLogic.Count(0);
        List<long[]> through = new ArrayList<>();
        int tries = 0;
        for (long t = 0; t <= 6000; t += 50) {
            int visible = 0;
            for (long[] s : through) if (s[1] == 0 && s[0] + rtt <= t) visible = (visible + 1) % 8;
            count.server(visible, t * 1_000_000L);
            // The player keeps clicking; after a resync they would click again, as anyone would.
            if (tries < 15 || count.remaining(need) > 0) {
                tries++;
                if (count.remaining(need) == 0) continue;        // stopped: it would overshoot
                through.add(new long[]{t, through.size() == drop ? 1 : 0});
                count.sent(t * 1_000_000L);
            }
        }
        int end = 0;
        for (long[] s : through) if (s[1] == 0) end = (end + 1) % 8;
        return new long[]{through.size(), end};
    }

    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
}
