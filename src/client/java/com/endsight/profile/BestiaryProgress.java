package com.endsight.profile;

import java.util.Locale;
import java.util.Map;

/** Final-tier requirements confirmed in DragSim's bestiary and collection menus. */
public final class BestiaryProgress {
    private BestiaryProgress() { }

    // The October 25 bestiary menus are the source here. Earlier tiers
    // can look like the end of a row: Protector XI is 500, but its final XV is 2,500.
    private static final Map<String,Integer> CAPS=Map.ofEntries(
            Map.entry("CRYPT_GHOUL",40_000),
            Map.entry("GOLDEN_GHOUL",300),
            Map.entry("REVENANT_CHAMPION",70),
            Map.entry("REVENANT_SYCOPHANT",70),
            Map.entry("DEFORMED_REVENANT",70),
            Map.entry("REVENANT_HORROR",50),
            Map.entry("NEST_ZEALOT",50_000),
            Map.entry("NEST_ZEALOT_BRUISER",50_000),
            Map.entry("NEST_WATCHER",50_000),
            Map.entry("NEST_DEFENDER",20_000),
            Map.entry("NEST_ENDERMITE",12_000),
            Map.entry("VOIDLING_DEVOTEE",70),
            Map.entry("VOIDLING_RADICAL",70),
            Map.entry("VOIDCRAZED_MANIAC",70),
            Map.entry("VOIDLING_FANATIC",250),
            Map.entry("VOIDLING_EXTREMIST",200),
            Map.entry("ENDSTONE_PROTECTOR",2_500),
            Map.entry("CORRUPTED_REVENANT",70),
            Map.entry("RIFTBORN_SERAPH",200),
            Map.entry("RIFT_SLIMELING",300),
            Map.entry("WARDEN",200),
            Map.entry("THE_DISTURBED",200),
            Map.entry("GOOP",200));

    /** Zero means unconfirmed; a large kill count alone must never imply completion. */
    public static int cap(String entry) {
        if(entry==null) return 0;
        String key=entry.toUpperCase(Locale.ROOT);
        if(key.endsWith("_MOB")) key=key.substring(0,key.length()-4);
        // Fear confirmed that every dragon family, including the rare dragons, ends at 200.
        return key.endsWith("_DRAGON") ? 200 : CAPS.getOrDefault(key,0);
    }

    public static boolean maxed(String entry,double kills) {
        return reached(cap(entry),kills);
    }

    // Fear's screenshots of each final tier at 100%, 2026-10-31. These are the only four
    // collections; /pv hides the profile's Summoning Eye and Null Ovoid counts.
    private static final Map<String,Integer> COLLECTION_CAPS=Map.of(
            "ENDER_STONE",50_000,      // End Stone IX
            "OBSIDIAN",50_000,         // Obsidian IX
            "ROUGH_AMETHYST",50_000,   // Amethyst X
            "CRYING_OBSIDIAN",5_000);  // Crying Obsidian IX

    public static int collectionCap(String entry) {
        return entry==null ? 0 : COLLECTION_CAPS.getOrDefault(entry.toUpperCase(Locale.ROOT),0);
    }

    public static boolean reached(int cap,double amount) {
        return cap>0 && Double.isFinite(amount) && amount>=cap;
    }
}
