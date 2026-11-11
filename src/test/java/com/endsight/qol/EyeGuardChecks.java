package com.endsight.qol;

import net.minecraft.core.BlockPos;

/** Protect Placed Eyes: the eye's second click is dropped, every other right-click goes through. */
public final class EyeGuardChecks {
    private static int checks;

    public static void main(String[] args) {
        BlockPos floor = new BlockPos(10, 68, 10), frame = new BlockPos(0, 70, 0), next = new BlockPos(1, 70, 0);

        // Spamming a Hyperion or a bow at the floor: nothing armed it, so nothing is dropped.
        EyeGuard.Guard g = new EyeGuard.Guard();
        for (long t = 0; t < 3000; t += 100) check(!g.click(floor, false, true, t, 900), "a right-click spam at one block is never dropped");

        // An eye in, then the double click that would pull it back out.
        g = new EyeGuard.Guard();
        check(!g.click(frame, true, true, 0, 900), "the click that places the eye goes through");
        check(g.click(frame, true, true, 120, 900), "a second click on that frame is dropped");
        check(g.click(frame, false, true, 300, 900), "even with the eye gone from your hand");
        check(!g.click(next, true, true, 350, 900), "the next frame along still takes its eye");
        check(!g.click(next, false, true, 2000, 900), "and the window runs out");

        // Holding the button on the frame keeps it guarded: each dropped press restarts the window.
        g = new EyeGuard.Guard();
        g.click(frame, true, true, 0, 900);
        for (long t = 200; t <= 2000; t += 200) check(g.click(frame, true, true, t, 900), "held on the frame at " + t + " ms");

        // The off hand is asked about the same click: it never arms, and never clears.
        g = new EyeGuard.Guard();
        check(!g.click(frame, true, true, 0, 900), "main hand places");
        check(g.click(frame, false, false, 0, 900), "the off hand's turn on the same click is the one dropped, as before");
        g = new EyeGuard.Guard();
        check(!g.click(floor, false, false, 0, 900) && !g.click(floor, false, true, 50, 900), "an off hand click alone arms nothing");

        // The server's confirmation holds the window open; with nothing armed it does nothing.
        g = new EyeGuard.Guard();
        g.click(frame, true, true, 0, 900);
        g.armed(800);
        check(g.click(frame, false, true, 1500, 900), "confirmed late, still guarded");
        g = new EyeGuard.Guard();
        g.armed(0);
        check(!g.click(floor, false, true, 10, 900), "a confirmation with no eye click arms nothing");
        System.out.println(checks + " eye guard checks passed");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
