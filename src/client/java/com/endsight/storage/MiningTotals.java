package com.endsight.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Persistent mining totals are independent from a resettable session. */
final class MiningTotals {
    final Map<String, Long> materials = new LinkedHashMap<>();
    long activeMs, fragments, sold;

    void material(String name, long delta) {
        materials.put(name, Math.max(0, materials.getOrDefault(name, 0L) + delta));
    }

    Properties properties() {
        Properties out = new Properties();
        out.setProperty("activeMs", Long.toString(activeMs));
        out.setProperty("fragments", Long.toString(fragments));
        out.setProperty("sold", Long.toString(sold));
        materials.forEach((name, count) -> out.setProperty("material." + name, Long.toString(count)));
        return out;
    }

    void load(Properties in) {
        activeMs = number(in, "activeMs");
        fragments = number(in, "fragments");
        sold = number(in, "sold");
        materials.clear();
        for (String key : in.stringPropertyNames()) {
            if (key.startsWith("material.")) materials.put(key.substring(9), number(in, key));
        }
    }

    private static long number(Properties in, String key) {
        try { return Math.max(0, Long.parseLong(in.getProperty(key, "0"))); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
