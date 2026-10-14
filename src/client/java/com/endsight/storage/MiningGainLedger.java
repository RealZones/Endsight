package com.endsight.storage;

import java.util.HashMap;
import java.util.Map;

/** Short-lived compaction debt for materials brought into a mining session. */
final class MiningGainLedger {
    static final long CARRY_MS = 750;

    private record Carry(long units, long at) { }
    private final Map<String, Carry> pending = new HashMap<>();

    /**
     * Returns the applied change to the non-negative tracked count. The raw change
     * is measured in equivalent raw units; craftedIncrease is only the increase in
     * compacted forms, so ordinary newly mined raw items cannot pay an old debt.
     */
    long delta(String material, long before, long rawChange, long craftedIncrease, long now) {
        before = Math.max(0, before);
        Carry carry = pending.get(material);
        if (carry != null && (now < carry.at || now - carry.at > CARRY_MS)) {
            pending.remove(material);
            carry = null;
        }
        if (rawChange < 0) {
            long removed = rawChange == Long.MIN_VALUE ? Long.MAX_VALUE : -rawChange;
            long applied = Math.min(before, removed);
            long shortfall = removed - applied;
            if (shortfall > 0) {
                long previous = carry == null ? 0 : carry.units;
                long units = shortfall > Long.MAX_VALUE - previous ? Long.MAX_VALUE : previous + shortfall;
                pending.put(material, new Carry(units, now));
            }
            return -applied;
        }
        if (rawChange == 0 || carry == null || craftedIncrease <= 0) return rawChange;
        // Removing pre-owned Rough and adding its Flawed form can land in different
        // ticks. Only the part discarded by the zero floor is cancelled here; a
        // removal already subtracted from mined totals must be restored normally.
        long cancelled = Math.min(rawChange, Math.min(craftedIncrease, carry.units));
        long remainder = carry.units - cancelled;
        if (remainder == 0) pending.remove(material);
        else pending.put(material, new Carry(remainder, carry.at));
        return rawChange - cancelled;
    }

    void clear() { pending.clear(); }
}
