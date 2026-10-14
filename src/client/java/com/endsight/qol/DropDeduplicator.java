package com.endsight.qol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Match two descriptions of one reward, without swallowing a second real reward. */
final class DropDeduplicator {
    enum Source { DEBUG, ANNOUNCEMENT, BONUS }
    private record Seen(String key, int quantity, Source source, long at) { }
    private static final long WINDOW_MS = 1_500;
    private final List<Seen> pending = new ArrayList<>();

    int credit(Drops.Drop drop, Source source, long now) {
        pending.removeIf(s -> now < s.at || now - s.at >= WINDOW_MS);
        if (source == Source.BONUS) return drop.quantity();
        String key = drop.item().toLowerCase(Locale.ROOT) + "\t" + drop.tier();
        for (int i = 0; i < pending.size(); i++) {
            Seen seen = pending.get(i);
            if (seen.key.equals(key) && seen.source != source) {
                pending.remove(i);
                // Debug sometimes names one item while the later reward says xN.
                // Credit only the additional units, rather than count both totals.
                return Math.max(0, drop.quantity() - seen.quantity);
            }
        }
        pending.add(new Seen(key, drop.quantity(), source, now));
        if (pending.size() > 128) pending.removeFirst();
        return drop.quantity();
    }

    void clear() { pending.clear(); }
}
