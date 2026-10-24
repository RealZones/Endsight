package com.endsight.profile;

import java.text.NumberFormat;
import java.util.Locale;

public final class ProfileFormat {
    private ProfileFormat() { }
    private static NumberFormat formatter(int digits) {
        NumberFormat value=NumberFormat.getNumberInstance(Locale.US); value.setMaximumFractionDigits(digits); return value;
    }
    // NumberFormat is mutable, so each thread owns its reusable formatters. Recreating
    // locale data for every visible or scrolled-out metric caused avoidable churn.
    private static final ThreadLocal<NumberFormat[]> FORMATS=ThreadLocal.withInitial(() ->
            new NumberFormat[]{formatter(0),formatter(1),formatter(2)});
    public static String number(double n) {
        if (!Double.isFinite(n)) return "—";
        NumberFormat f = FORMATS.get()[n == Math.rint(n) ? 0 : 1];
        return f.format(n);
    }
    public static String compact(double n) {
        if (!Double.isFinite(n)) return "—";
        String suffix = Math.abs(n) >= 1e12 ? "T" : Math.abs(n) >= 1e9 ? "B" : Math.abs(n) >= 1e6 ? "M" : Math.abs(n) >= 1e3 ? "K" : "";
        double divisor = switch(suffix) { case "T" -> 1e12; case "B" -> 1e9; case "M" -> 1e6; case "K" -> 1e3; default -> 1; };
        NumberFormat format=FORMATS.get()[2];
        return format.format(n/divisor)+suffix;
    }
    public static String age(long timestamp) {
        if (timestamp <= 0) return "Save time unavailable";
        long seconds = Math.max(0, (System.currentTimeMillis()-timestamp)/1000);
        return seconds < 60 ? "Saved just now" : seconds < 3600 ? "Saved "+seconds/60+"m ago"
                : seconds < 86400 ? "Saved "+seconds/3600+"h ago" : "Saved "+seconds/86400+"d ago";
    }
}
