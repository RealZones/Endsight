package com.endsight.storage;

/** A private placement arms the HUD; nearby Caches can never arm or revive it. */
final class CacheVisibility {
    private static final long GRACE_MS = 3_000;
    private long until = -1;
    private boolean seen, gone;

    void placed(long now) { seen = gone = false; until = now + 15_000; }
    void observe(boolean ownBlockPresent, long now) {
        if (!visible(now)) return;
        if (gone) return;
        if (ownBlockPresent) { seen = true; until = now + GRACE_MS; }
        else if (seen) gone = true;
    }
    // If the placement target was unavailable, the private result still bounds
    // the lifetime. The recording removes its vault about 1.5s after this result.
    void result(long now) { if (visible(now) && !seen) until = now + 5_000; }
    boolean visible(long now) { return until >= 0 && now <= until; }
    void reset() { until = -1; seen = gone = false; }
}
