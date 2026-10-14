package com.endsight.storage;

/** Inventory packet order, raw mining, and session boundaries for compaction debt. */
public final class MiningGainLedgerChecks {
    private static int checks;

    public static void main(String[] args) {
        MiningGainLedger ledger = new MiningGainLedger();
        equal(ledger.delta("Amethyst", 0, -80, 0, 1_000), 0);
        equal(ledger.delta("Amethyst", 0, 80, 80, 1_100), 0);

        ledger.clear();
        equal(ledger.delta("Amethyst", 80, -80, 0, 2_000), -80);
        equal(ledger.delta("Amethyst", 0, 80, 80, 2_100), 80);

        // Ordinary newly mined Rough is never cancelled, even during pending compaction.
        ledger.clear();
        equal(ledger.delta("Amethyst", 0, -80, 0, 3_000), 0);
        equal(ledger.delta("Amethyst", 0, 7, 0, 3_050), 7);
        equal(ledger.delta("Amethyst", 7, 80, 80, 3_100), 0);

        // A single update can contain both new Rough and the return of a compacted stack.
        ledger.clear();
        equal(ledger.delta("Amethyst", 0, -80, 0, 4_000), 0);
        equal(ledger.delta("Amethyst", 0, 87, 80, 4_100), 7);

        // Cancel only the part that the tracker could not subtract at the zero floor.
        ledger.clear();
        equal(ledger.delta("Amethyst", 20, -80, 0, 5_000), -20);
        equal(ledger.delta("Amethyst", 0, 80, 80, 5_100), 20);

        // Unrelated crafted gains after expiry remain mined progress.
        ledger.clear();
        equal(ledger.delta("Amethyst", 0, -80, 0, 6_000), 0);
        equal(ledger.delta("Amethyst", 0, 80, 80, 6_000 + MiningGainLedger.CARRY_MS + 1), 80);

        ledger.clear();
        equal(ledger.delta("Amethyst", 0, -80, 0, 7_000), 0);
        equal(ledger.delta("Amethyst", 0, 80, 0, 7_100), 80);

        // Reset/menu/world changes invalidate a carry; other materials never share it.
        ledger.clear();
        equal(ledger.delta("Amethyst", 0, -80, 0, 8_000), 0);
        equal(ledger.delta("Obsidian", 0, 160, 160, 8_100), 160);
        ledger.clear();
        equal(ledger.delta("Amethyst", 0, 80, 80, 8_100), 80);

        // A partial return keeps the original deadline rather than extending a debt forever.
        ledger.clear();
        equal(ledger.delta("Amethyst", 0, -160, 0, 9_000), 0);
        equal(ledger.delta("Amethyst", 0, 80, 80, 9_100), 0);
        equal(ledger.delta("Amethyst", 0, 80, 80, 9_000 + MiningGainLedger.CARRY_MS + 1), 80);

        // A backwards wall clock cannot leave a debt attached to a later packet.
        ledger.clear();
        equal(ledger.delta("Amethyst", 0, -80, 0, 10_000), 0);
        equal(ledger.delta("Amethyst", 0, 80, 80, 9_999), 80);
        System.out.println(checks + " Mining gain ledger checks passed");
    }

    private static void equal(long actual, long expected) {
        checks++;
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
