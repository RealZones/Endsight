package com.endsight.goop;

import java.util.Map;

/**
 * The Goop's waits and payout, against lines and times from Fear's recording of
 * 2026-11-01 20:33Z (the second fight in it, millisecond stamps) and his chat logs.
 */
public final class GoopFightChecks {
    private static int checks;

    public static void main(String[] args) {
        GoopFight f = new GoopFight();
        String me = "ImFear";
        // Times are the recording's, in ms from its start.
        f.line("_nephis squeezes the goop into the altar.", 58_200, me);
        equal(f.phase(), GoopFight.Phase.STIRRING);
        equal(f.remaining(58_200), 31_300L);
        f.line("The ground begins to shake...", 58_200, me);
        equal(f.remaining(58_200), 31_300L);
        f.line("The ground is shaking...", 73_900, me);
        // Re-anchored on the shaking, the count ends 0.1 s before the Slime landed at 89.5 s.
        equal(f.remaining(89_500), 0L);
        equal(f.remaining(89_300), 100L);
        f.line("The rift throws you skyward!", 89_500, me);
        equal(f.phase(), GoopFight.Phase.SLIME);
        equal(f.remaining(89_500), -1L);
        f.line("It bursts into pieces! Kill every last one of them.", 92_300, me);
        equal(f.phase(), GoopFight.Phase.COLONY);
        f.line("It pulls itself back together...", 93_100, me);
        equal(f.phase(), GoopFight.Phase.SLIME);
        f.line("??? ...under you...", 95_300, me);
        equal(f.phase(), GoopFight.Phase.UNDER);
        equal(f.remaining(95_300), 12_000L);
        // The Slime talks while it is underground; that is not the Slime coming back.
        f.line("Rift Slime The ground is mine. Breathe it.", 102_000, me);
        equal(f.phase(), GoopFight.Phase.UNDER);
        f.line("It drags you under...", 102_900, me);
        equal(f.remaining(102_900), 4_000L);
        f.line("It spits you back out.", 105_900, me);
        // The Corruptor rose at 106.9 s, exactly where the count ends.
        equal(f.remaining(106_900), 0L);
        f.line("IT RISES under you!", 106_900, me);
        f.line("The corruption begins. The sky is not empty any more.", 106_900, me);
        equal(f.phase(), GoopFight.Phase.CORRUPTOR);
        f.line("The island itself starts to come apart.", 108_300, me);
        equal(f.phase(), GoopFight.Phase.DEVOURER);
        f.line("What was left of it dissolves.", 110_200, me);
        f.line("It stops moving.", 110_200, me);
        equal(f.phase(), GoopFight.Phase.DEVOURED);
        equal(f.remaining(110_200), 7_000L);
        f.line("The rift swallows everything.", 115_200, me);
        // The same drag comes in this wait too, and must not move it to 4 s.
        f.line("It drags you under...", 115_200, me);
        f.line("It spits you back out.", 116_700, me);
        equal(f.remaining(115_200), 2_000L);
        f.line("The rift throws you skyward!", 117_100, me);
        equal(f.phase(), GoopFight.Phase.DISTURBED);
        f.line("The Disturbed YOU. DISTURBED. ME.", 117_100, me);
        equal(f.fightTime(119_000), 29_500L);

        // Someone repeating the end in chat is not the end.
        f.line("[DRAGON] Bob: The rift falls silent.", 118_000, me);
        equal(f.phase(), GoopFight.Phase.DISTURBED);

        f.line("The rift falls silent.", 119_000, me);
        equal(f.phase(), GoopFight.Phase.NONE);
        String box = """
                ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
                                            ✦ THE RIFT SLEEPS

                      1st Damager [DRAGON] Kawagane - 26,977,130,232.69
                       2nd Damager [DRAGON] XmuideM - 15,652,739,068.23
                          3rd Damager [DRAGON] ImFear - 745,947,618.47

                          Your Damage: 745,947,618.47 (1.7%, Position #3)
                Fight: 00:28 · pieces killed 17 · dregs home 0 (healed +0.0%) · emerges 1 · dodged drags 0 · rescues 0 · collapses missed 0
                ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬""";
        for (String part : box.split("\\R")) equal(f.line(part, 119_000, me), null);
        f.line("+1 Crypt Cache, 12 Rift Goo and 2 Gelatinous Membranes (top three)", 119_000, me);
        f.line("The island pulls itself back together.", 120_900, me);
        equal(f.poll(123_900), null);
        GoopFight.Result r = f.poll(124_100);
        equal(r.paid(), true);
        equal(r.placed(), false);
        equal(r.place(), 3);
        equal(r.share(), 1.7);
        equal(r.damage(), 745_947_618.47);
        equal(r.drops(), Map.of("Crypt Cache", 1, "Rift Goo", 12, "Gelatinous Membrane", 2));
        equal(f.poll(200_000), null);

        // Not paid: sixth, 0.2%, and the line that says so (his logs).
        GoopFight unpaid = endedFight("ImFear");
        unpaid.line("✦ THE RIFT SLEEPS", 1_000, "ImFear");
        unpaid.line("Your Damage: 72,348,494.26 (0.2%, Position #6)", 1_000, "ImFear");
        unpaid.line("You did 0.2% of the damage. The top 3, the Goop placer and anyone with 3%+ are paid.", 1_000, "ImFear");
        GoopFight.Result u = unpaid.poll(7_000);
        equal(u.paid(), false);
        equal(u.place(), 6);
        equal(u.share(), 0.2);
        equal(u.drops().isEmpty(), true);

        // Your own goop, paid without a reason in brackets (the pay line as in his logs).
        GoopFight own = new GoopFight();
        own.line("✦ [DRAGON] ImFear squeezes the goop into the altar.", 0, "ImFear");
        own.line("The rift falls silent.", 60_000, "ImFear");
        own.line("+1 Crypt Cache, 11 Rift Goo and 1 Gelatinous Membrane", 60_000, "ImFear");
        GoopFight.Result o = own.poll(70_000);
        equal(o.placed(), true);
        equal(o.paid(), true);
        equal(o.drops(), Map.of("Crypt Cache", 1, "Rift Goo", 11, "Gelatinous Membrane", 1));
        // Parties rotate the placer: count the goops since yours, and only once you have placed one.
        equal(own.sinceMine(), 0);
        own.line("_nephis squeezes the goop into the altar.", 80_000, "ImFear");
        own.line("XmuideM squeezes the goop into the altar.", 150_000, "ImFear");
        equal(own.sinceMine(), 2);
        equal(f.sinceMine(), -1);

        // AFK at the altar: no box, no pay, nothing of yours.
        GoopFight afk = endedFight("ImFear");
        afk.line("The island pulls itself back together.", 2_000, "ImFear");
        equal(afk.poll(7_000), null);

        // The Disturbed's own drops, indented under their header; the folded debug line after them is not one.
        GoopFight drop = endedFight("ImFear");
        drop.line("THE DISTURBED DROPPED", 1_000, "ImFear");
        drop.line("  Corrupted Core", 1_000, "ImFear");
        drop.line("▍ The Disturbed 0.441 → Corrupted Core", 1_000, "ImFear");
        drop.line("  Corrupted Core", 1_200, "ImFear");
        equal(drop.poll(7_000).drops(), Map.of("Corrupted Core", 1));

        // Other "+" lines in the same second are not the Goop's pay.
        equal(GoopFight.pay("+5 ☘ Mining Fortune (2x)"), null);
        equal(GoopFight.pay("+120 Bits (Carrot Buff, 3 left)"), null);
        equal(GoopFight.pay("+1 Crypt Cache, 14 Rift Goo and 2 Gelatinous Membranes (top three)"),
                Map.of("Crypt Cache", 1, "Rift Goo", 14, "Gelatinous Membrane", 2));

        // Joined mid-fight: the boss's line names the form.
        GoopFight late = new GoopFight();
        late.line("Rift Devourer Burn with it.", 0, "ImFear");
        equal(late.phase(), GoopFight.Phase.DEVOURER);
        late.line("It stops moving.", 1_000, "ImFear");
        equal(late.remaining(1_000), 7_000L);

        equal(GoopFight.seconds(24_200), "25s");
        equal(GoopFight.seconds(9_240), "9.2s");

        // The card's counts are one line of words, and the turn line counts goops since yours.
        GoopTracker.Counts c = new GoopTracker.Counts();
        equal(GoopTracker.stats(c, "", ""), "No fights yet");
        c.fights = 1;
        c.paid = 1;
        equal(GoopTracker.stats(c, "", ""), "1 fight · 1 paid · 0 placed");
        c.fights = 1_204;
        c.paid = 980;
        c.placed = 301;
        equal(GoopTracker.stats(c, "", ""), "1,204 fights · 980 paid · 301 placed");
        equal(GoopTracker.stats(c, "<", ">"), "<1,204> fights · <980> paid · <301> placed");
        equal(GoopTracker.turn(-1), null);
        equal(GoopTracker.turn(0), "Last goop was yours");
        equal(GoopTracker.turn(1), "1 goop since yours");
        equal(GoopTracker.turn(3), "3 goops since yours");

        // The placement clock keeps the first end signal, survives
        // payout collection, and is cancelled as soon as another fight starts.
        GoopFight test = new GoopFight();
        test.line("[DRAGON] Bob: ✦ THE RIFT SLEEPS", 100, me);
        equal(test.finishedAt(), -1L);
        equal(GoopTracker.placementCount(test, 100), null);
        test.line("The rift falls silent.", 1_000, me);
        test.line("✦ THE RIFT SLEEPS", 1_500, me);
        test.line("The rift falls silent.", 2_000, me);
        equal(test.finishedAt(), 1_000L);
        equal(GoopTracker.placementCount(test, 1_000), "6.0s");
        equal(GoopTracker.placementCount(test, 3_500), "3.5s");
        equal(GoopTracker.placementCount(test, 6_900), "0.1s");
        equal(GoopTracker.placementCount(test, 7_000), "Place Goop");
        test.poll(8_000);
        equal(GoopTracker.placementCount(test, 8_000), "Place Goop");
        equal(GoopTracker.placementCount(test, 12_000), null);
        test.line("ImFear squeezes the goop into the altar.", 12_000, me);
        equal(test.finishedAt(), -1L);
        equal(GoopTracker.placementCount(test, 12_000), null);
        test.line("✦ THE RIFT SLEEPS", 13_000, me);
        equal(test.finishedAt(), -1L); // A late old result cannot restart it during the new spawn wait.
        test.line("It stops moving.", 49_000, me);
        test.line("The rift throws you skyward!", 50_000, me);
        test.line("✦ THE RIFT SLEEPS", 60_000, me);
        equal(test.finishedAt(), 60_000L); // Final-stage result works without the death narration.
        test.line("✦ THE RIFT SLEEPS", 61_000, me);
        equal(test.finishedAt(), 60_000L);
        test.reset();
        equal(test.finishedAt(), -1L);

        System.out.println(checks + " goop checks passed");
    }

    /** A fight that has just fallen silent at t=1000. */
    private static GoopFight endedFight(String me) {
        GoopFight f = new GoopFight();
        f.line("The rift throws you skyward!", 0, me);
        f.line("The rift falls silent.", 1_000, me);
        return f;
    }

    private static void equal(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("check " + checks + ": expected " + expected + " but got " + actual);
    }
}
