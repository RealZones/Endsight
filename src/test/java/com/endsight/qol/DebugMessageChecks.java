package com.endsight.qol;

import java.util.List;

/**
 * Custom debug messages, against the message and lines in Fear's game log (2026-10-26
 * to 2026-10-30) rather than invented ones.
 */
public final class DebugMessageChecks {
    private static int checks;

    private static final String MESSAGE = "&bResult &d[result]&b! Roll &f[roll]&b, target &e[chance] &b(&e[odds]&b)! "
            + "Magic Find &d[magic_find]&b! Pet Luck &a[pet_luck]&b!";

    public static void main(String[] args) {
        DebugMessage.set(List.of(MESSAGE));

        // Rare rolls that missed, far from either end: hidden.
        DebugMessage.Read candy = DebugMessage.read("Result Haunted Candy; MF not applied miss! Roll 40.47261, "
                + "target 0.3003% (1/333)! Magic Find 207.61764! Pet Luck 71.3!");
        equal(candy.roll(), 40.47261);
        equal(candy.result(), "Haunted Candy; MF not applied miss");
        check(candy.complete(), "a one-line message is a whole roll");
        check(!LootFilter.shows(candy.roll(), candy.result()), "a distant miss is hidden");
        // Commas in the odds, and in a roll this low the filter's default cutoff lets it through.
        DebugMessage.Read chest = DebugMessage.read("Result Trick-or-Treat Chest; MF not applied miss! Roll 1.86139, "
                + "target 0.02667% (1/3,750)! Magic Find 207.61764! Pet Luck 71.3!");
        equal(chest.roll(), 1.86139);
        check(LootFilter.shows(chest.roll(), chest.result()), "a close miss shows");

        // The table roll: "miss" alone, and close to 100 counts with both ends on.
        DebugMessage.Read table = DebugMessage.read("Result miss! Roll 98.00892, target 98.41892% (1/1)! "
                + "Magic Find 208.61764! Pet Luck 71.3!");
        equal(table.result(), "miss");
        check(LootFilter.shows(table.roll(), table.result()), "a roll near 100 shows");
        check(!LootFilter.shows(42.92388, "miss"), "a plain miss is hidden");

        // Hits and drops always show, however far the roll.
        for (String line : new String[]{
                "Result Summoning Eye; MF not applied HIT! Roll 4.03609, target 15% (1/7)! Magic Find 163.41! Pet Luck 65.72!",
                "Result Warden Catalyst HIT! Roll 9.65619, target 24% (1/4)! Magic Find 279.3712! Pet Luck 71.3!",
                "Result Golden Eye! Roll 60.50569, target 100% (guaranteed)! Magic Find 280.3712! Pet Luck 71.3!",
                "Result Strong Dragon! Roll 72.47074, target 0% (n/a)! Magic Find 236.6625! Pet Luck 71.3!"}) {
            DebugMessage.Read r = DebugMessage.read(line);
            check(r != null && LootFilter.shows(r.roll(), r.result()), "shown: " + line);
        }
        check(!DebugMessage.happened("nothing") && !DebugMessage.happened(null), "nothing is not a drop");
        equal(DebugMessage.read("Result Special Zealot HIT! Roll 0.0368, target 0.31502% (1/317)! "
                + "Magic Find 279.3712! Pet Luck 71.3!").result(), "Special Zealot HIT");

        // Other people's chat, the message itself pasted, and the server's other lines are not yours.
        for (String line : new String[]{
                "[DRAGON] GangMemberTech: Result Goop miss! Roll 5.1, target 0.1% (1/599)! Magic Find 1! Pet Luck 1!",
                "[DRAGON] GangMemberTech: &bResult &d[result]&b! Roll &f[roll]&b, target &e[chance] &b(&e[odds]&b)! "
                        + "Magic Find &d[magic_find]&b! Pet Luck &a[pet_luck]&b!",
                "+5 ✯ Magic Find (1x)",
                "[Debug] The Disturbed · Roll 27.86/100 · Goop · Chance 0.1%"})
            check(DebugMessage.read(line) == null, "not a debug line: " + line);

        // Several lines. "[debug]" alone is no anchor, but the message as a whole is.
        DebugMessage.set(List.of("&7[debug]", "&fRoll [roll] &8· [result]"));
        DebugMessage.Read both = DebugMessage.read("[Debug] Zealot\nRoll 1.25 · Goop miss");
        check(both != null && both.complete(), "several lines in one message");
        equal(both.roll(), 1.25);
        equal(DebugMessage.lastLine(), 1);
        DebugMessage.Read part = DebugMessage.read("Roll 50.5/100 · Goop HIT");
        check(part != null && part.roll() == 50.5 && DebugMessage.happened(part.result()), "one line of several");
        check(DebugMessage.read("[VIP] Bob: Roll 3 · Goop miss") == null, "a message that opens on a placeholder skips chat");

        // Placeholders in a row before any text of the message's own: ordinary chat took
        // 650 ms a line to rule out before the words were checked first.
        DebugMessage.set(List.of("[mob] [result] [magic_find] [pet_luck] [luck] Roll [roll]"));
        String chat = "[DRAGON] GangMemberTech: " + "anyone selling a summoning eye ".repeat(4);
        long started = System.nanoTime();
        check(DebugMessage.read(chat) == null, "chat without the message's words is not a roll");
        check(System.nanoTime() - started < 50_000_000L, "ruled out without the pattern's backtracking");
        DebugMessage.Read leading = DebugMessage.read("Zealot miss 207.6 71.3 1.0x Roll 12.5");
        check(leading != null && leading.roll() == 12.5, "the same message still reads its own line");

        // Nothing loaded, or a message too bare to match safely: the built-in formats carry on.
        DebugMessage.set(List.of());
        check(DebugMessage.read("Result miss! Roll 1, target 1% (1/1)! Magic Find 1! Pet Luck 1!") == null, "no message");
        DebugMessage.set(List.of("[debug]"));
        check(DebugMessage.read("anything at all") == null, "a placeholder alone never matches");

        // /debug message, as sent.
        equal(DebugMessage.fromCommand("debug message &bRoll [roll]!"), List.of("&bRoll [roll]!"));
        equal(DebugMessage.fromCommand("Debug Message one\\ntwo"), List.of("one", "two"));
        check(DebugMessage.fromCommand("debug message") == null && DebugMessage.fromCommand("debug on") == null,
                "other /debug commands");

        // The Custom Message page: the line's first lore line is the message.
        equal(DebugMessage.template(List.of(MESSAGE, "", "Click to edit this line privately in chat.")), MESSAGE);
        check(DebugMessage.template(List.of("Click to edit this line privately in chat.")) == null, "hint only");
        check(DebugMessage.template(List.of("(Empty)")) == null && DebugMessage.template(List.of()) == null, "unused line");

        System.out.println(checks + " debug message checks passed");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void equal(Object got, Object want) {
        check(want.equals(got), "expected " + want + ", got " + got);
    }
}
