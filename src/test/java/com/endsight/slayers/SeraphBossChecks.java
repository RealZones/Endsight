package com.endsight.slayers;

public final class SeraphBossChecks {
    private static int checks;

    public static void main(String[] args) {
        SeraphBoss body = SeraphBoss.read("☠ Riftborn Seraph 6B/6B❤");
        check(body != null && body.health().equals("6B"), "static T5 body tag parsed");

        SeraphBoss live = SeraphBoss.liveDisplay(body,
                "☠ Riftborn Seraph 4.7B/6B❤ 60 Hits", 0, 2.9, 0, false);
        check(live != null && live.health().equals("4.7B") && live.hits() == 60,
                "nearby TextDisplay supplies live health and shield");
        SeraphBoss phase = SeraphBoss.liveDisplay(body,
                "☠ Riftborn Seraph 3.0B/6B❤ Radiation", 0, 2.9, 0, false);
        check(phase != null && phase.radiating(), "live TextDisplay supplies radiation state");

        check(SeraphBoss.liveDisplay(body, "☠ Riftborn Seraph 4.7B/6B❤", 1, 2.9, 0, false) == null,
                "another mob's display cannot be borrowed horizontally");
        check(SeraphBoss.liveDisplay(body, "☠ Riftborn Seraph 4.7B/6B❤", 0, 6, 0, false) == null,
                "another mob's display cannot be borrowed vertically");
        check(SeraphBoss.liveDisplay(body, "☠ Riftborn Seraph 4.7B/24B❤", 0, 2.9, 0, false) == null,
                "different max health cannot be borrowed");
        check(SeraphBoss.liveDisplay(body, "☠ Voidgloom Seraph IV 4.7B/6B❤", 0, 2.9, 0, false) == null,
                "different boss family cannot be borrowed");

        // The Rift T4 of 2026-09-29: these are the texts riding its "Dinnerbone" body,
        // copied from boss-entities.txt, in the order the fight showed them.
        SeraphBoss shield = SeraphBoss.read("☠ Voidgloom Seraph IV 2.5B/2.5B❤ 100 Hits");
        check(shield != null && shield.tier().equals("IV") && shield.hits() == 100
                && shield.health().equals("2.5B") && !shield.radiating(), "Rift T4 shield text parsed");
        SeraphBoss beams = SeraphBoss.read("☠ Voidgloom Seraph IV 2.0B/2.5B❤ Radiation");
        check(beams != null && beams.radiating() && beams.supportsRadiation(), "Rift T4 radiation text parsed");
        SeraphBoss open = SeraphBoss.read("☠ Voidgloom Seraph IV 1.6B/2.5B❤");
        check(open != null && open.hits() == -1 && open.health().equals("1.6B"), "Rift T4 open text parsed");
        check(SeraphBoss.read("Dinnerbone") == null, "the body's own name is not a Seraph");
        System.out.println(checks + " Seraph Boss checks passed");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
