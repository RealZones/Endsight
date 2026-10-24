package com.endsight.hud;

import com.endsight.qol.Drops;
import com.endsight.qol.Rarity;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** One row order for Dragon and Cache loot; filtering stays with each tracker. */
public final class TrackerDropOrder {
    private TrackerDropOrder() { }
    public static Comparator<Map.Entry<String,Integer>> comparator(ToIntFunction<String> rank,
            ToIntFunction<String> tier, Function<String,String> plain) {
        return (a, b) -> {
            int compared = Integer.compare(rank.applyAsInt(b.getKey()), rank.applyAsInt(a.getKey()));
            if (compared != 0) return compared;
            compared = Integer.compare(tier.applyAsInt(b.getKey()), tier.applyAsInt(a.getKey()));
            if (compared != 0) return compared;
            compared = Integer.compare(Drops.orderOf(a.getKey()), Drops.orderOf(b.getKey()));
            if (compared != 0) return compared;
            compared = Integer.compare(Rarity.rank(b.getKey()), Rarity.rank(a.getKey()));
            if (compared != 0) return compared;
            compared = Integer.compare(b.getValue(), a.getValue());
            return compared != 0 ? compared : plain.apply(a.getKey()).compareToIgnoreCase(plain.apply(b.getKey()));
        };
    }
}
