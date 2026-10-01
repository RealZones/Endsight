package com.endsight.slayers;

public final class SummonsChecks {
    private static int checks;

    public static void main(String[] args) {
        check(Summons.isSoulLabel("[Lv55] Voidling Extremist (ImFear's soul) 47500/47500"),
                "normal summon label recognized");
        check(Summons.isSoulLabel("§7[Lv55] Voidling Extremist (§dSomeone’s soul§7)"),
                "formatted curly-apostrophe label recognized");
        check(Summons.isSoulLabel(new StringBuilder("[Lv55] Voidling Extremist (ImFear's soul)")
                .reverse().toString()), "reversed soul label recognized");
        check(!Summons.isSoulLabel("Dinnerbone"), "upside-down mob name alone is not a summon");
        check(!Summons.isSoulLabel("[Lv55] Voidling Extremist 47500/47500"),
                "regular mobs are not faded");
        check(Summons.isUnderTag(0, 2.9, 0), "display directly above body matches");
        check(!Summons.isUnderTag(1, 2.9, 0), "neighboring body cannot borrow soul label");
        System.out.println(checks + " Summons checks passed");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
