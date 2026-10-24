package com.endsight.dungeon;

import java.util.List;

/** A damaged mob must retain its original place in the puzzle order. */
public final class HigherOrLowerChecks {
    private HigherOrLowerChecks() { }

    public static void main(String[] args) {
        HigherOrLowerLogic.Blaze low = HigherOrLowerLogic.read("[Lv100] Blaze 200000/225000❤");
        HigherOrLowerLogic.Blaze middle = HigherOrLowerLogic.read("[Lv100] Blaze 100000/250000❤");
        HigherOrLowerLogic.Blaze high = HigherOrLowerLogic.read("[Lv100] Blaze 275000/275000❤");
        if (low == null || middle == null || high == null) throw new AssertionError("Blaze tag not read");
        List<HigherOrLowerLogic.Blaze> room = List.of(middle, high, low);
        if (HigherOrLowerLogic.next(room, HigherOrLowerLogic.Order.LOWEST_FIRST) != low)
            throw new AssertionError("Damage changed lowest-first spawn order");
        if (HigherOrLowerLogic.next(room, HigherOrLowerLogic.Order.HIGHEST_FIRST) != high)
            throw new AssertionError("Highest-first order wrong");
        HigherOrLowerLogic.Blaze tied = HigherOrLowerLogic.read("[Lv100] Blaze 220000/225000❤");
        if (HigherOrLowerLogic.next(List.of(low, tied, middle), HigherOrLowerLogic.Order.LOWEST_FIRST) != null)
            throw new AssertionError("Ambiguous displayed health got a target");
        if (HigherOrLowerLogic.order("Highest health first.") != HigherOrLowerLogic.Order.HIGHEST_FIRST
                || HigherOrLowerLogic.order("Lowest health first.") != HigherOrLowerLogic.Order.LOWEST_FIRST)
            throw new AssertionError("Direction label misread");
        System.out.println("5 Higher Or Lower checks passed");
    }
}
