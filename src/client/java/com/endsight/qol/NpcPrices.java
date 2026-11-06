package com.endsight.qol;

import com.endsight.dragons.BossDrops;
import com.endsight.zealots.Zealots;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-item prices. User estimates, sale evidence and built-in NPC defaults stay distinct. */
public final class NpcPrices {
    public record Sale(String item, int count, long unitPrice) {}
    public record Price(long coins, String source, int sales) {}
    private static final class Evidence {
        String name;
        long candidate, confirmed;
        int matches;

        Evidence(String name, long candidate, int matches, long confirmed) {
            this.name = name;
            this.candidate = candidate;
            this.matches = matches;
            this.confirmed = confirmed;
        }
    }

    private static final long MAX_PRICE = 1_000_000_000_000L;
    private static final Pattern SOLD = Pattern.compile("^You sold ([0-9,]+)x (.+?) for ([0-9,]+) coins!$");
    private static final Pattern PET = Pattern.compile("^\\[Lvl [0-9]+\\]\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATED_PET = Pattern.compile("\\((?:Common|Uncommon|Rare|Epic|Legendary|Mythic)\\)$");
    private static final Pattern AMOUNT = Pattern.compile("^([0-9][0-9,]*(?:\\.[0-9]{1,3})?)\\s*([kmb]?)$", Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Long> BUILTIN = Map.of(
            "zombie talisman", 200_000L,
            "revenant viscera", 500_000L,
            "enderman", 50_000_000L,
            "spicy wart", 15_000_000L);
    private static final Map<String, String> BUILTIN_NAMES = Map.of(
            "zombie talisman", "Zombie Talisman",
            "revenant viscera", "Revenant Viscera",
            "enderman", "Enderman",
            "spicy wart", "Spicy Wart");
    // Farming defaults are editable valuations, not claimed NPC sale evidence.
    // Goop and Crypt Cache drops start here so a new tracker reads a number, not a wall of "?".
    private static final Map<String, Long> FARMING_DEFAULTS = Map.ofEntries(
            Map.entry("golden eye", 50_000_000L), Map.entry("rift pass", 50_000_000L),
            Map.entry("warped stone", 50_000_000L), Map.entry("wolf", 20_000_000L),
            Map.entry("infinieye™", 35_000_000L), Map.entry("null ovoid", 1_000_000L),
            Map.entry("goop", 115_000_000L), Map.entry("crypt cache", 80_000_000L),
            Map.entry("rift goo", 1_250_000L), Map.entry("gelatinous membrane", 1_000_000L),
            Map.entry("rift shard", 5_000_000L), Map.entry("training weights", 1_000_000L),
            Map.entry("habanero pepper", 40_000_000L), Map.entry("heart of the disturbed", 300_000_000L),
            Map.entry("rift hourglass", 300_000_000L), Map.entry("the art of war", 500_000_000L),
            Map.entry("corrupted core", 750_000_000L), Map.entry("crystal fragment", 250_000L),
            Map.entry("dragon scale", 600_000L), Map.entry("dragon claw", 3_000_000L),
            Map.entry("aspect of the dragons", 10_000_000L),
            Map.entry("hot potato book", 10_000_000L), Map.entry("golden hot potato book", 100_000_000L),
            Map.entry("draconic talisman", 55_000_000L), Map.entry("undead catalyst", 5_000_000L),
            Map.entry("recombobulator 3000", 420_000_000L), Map.entry("berserk fang", 44_000_000L),
            Map.entry("young dragon fragment", 8_000L), Map.entry("berserk dragon fragment", 500_000L),
            Map.entry("necromancer's brooch", 25_000_000L), Map.entry("high class archfiend dice", 300_000_000L),
            Map.entry("null atom", 2_800_000L), Map.entry("tiger (legendary)", 50_000_000L),
            Map.entry("ender dragon (epic)", 250_000_000L));
    public static final List<String> ZEALOT_ITEMS = List.of("Golden Eye", "Rift Pass", "Summoning Eye", "Warden Catalyst");
    // The compact HUD keeps its four familiar rows; these extra drops still count
    // toward profit and remain available in the price editor and breakdown.
    public static final List<String> ZEALOT_PROFIT_ITEMS = List.of(
            "Golden Eye", "Rift Pass", "Summoning Eye", "Warden Catalyst", "Enderman", "Spicy Wart",
            "Warped Stone", "Wolf", "InfiniEye™", "Null Ovoid");
    private static final Set<String> FARMING_KEYS = ZEALOT_PROFIT_ITEMS.stream()
            .map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private record Normalized(String name, String key, boolean ambiguousPet) { }
    // Drop names repeat for every HUD row. Cache only text normalization, never prices:
    // sale evidence and custom values must take effect immediately.
    private static final Map<String, Normalized> normalizedNames = new LinkedHashMap<>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Normalized> eldest) { return size() > 2048; }
    };
    private static final Map<String, Long> custom = new LinkedHashMap<>();
    private static final Map<String, String> customNames = new LinkedHashMap<>();
    private static final Map<String, Evidence> sales = new LinkedHashMap<>();
    /** What the Cache and Goop trackers have recorded. Their lists are whatever the server pays out. */
    private static final Set<String> tracked = new java.util.HashSet<>();
    private static boolean learning = true, salesDirty;
    private static long lastFlush;
    private static long revision;

    private NpcPrices() {}

    public static void init() {
        reloadCustom();
        loadSales();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && learning) observe(message.getString());
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            if (salesDirty && now - lastFlush >= 2_000) flushSales();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> flushSales());
    }

    public static boolean learning() { return learning; }
    public static void learning(boolean value) {
        if (learning != value) { learning = value; revision++; }
    }

    /** Invalidates HUD row snapshots after a price source or value changes. */
    public static long revision() { return revision; }

    public static Path customFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("drop-prices.json");
    }

    private static Path salesFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("npc-sales.json");
    }

    private static String key(String item) {
        // Curly apostrophes in a shared price file and the server's plain chat
        // apostrophes identify the same item, rather than two conflicting prices.
        return normalized(item).key();
    }

    private static String canonicalName(String item) {
        return normalized(item).name();
    }

    private static Normalized normalized(String item) {
        Normalized cached = normalizedNames.get(item);
        if (cached != null) return cached;
        String name = WHITESPACE.matcher(Zealots.strip(item).trim()).replaceAll(" ");
        Matcher level = PET.matcher(name);
        // Only Enderman and Wolf are priced as level-independent Zealot drops.
        // Other pets stay ambiguous rather than inheriting a species-wide price.
        boolean pet = level.find();
        // A drop names its rarity, which is all that sets its price: it always arrives at level 1.
        boolean knownPet = pet && (name.substring(level.end()).equalsIgnoreCase("Enderman")
                || name.substring(level.end()).equalsIgnoreCase("Wolf") || RATED_PET.matcher(name).find());
        if (knownPet) name = name.substring(level.end());
        String key = name.replace('\u2019', '\'').toLowerCase(Locale.ROOT);
        if (key.equals("infinieye")) key = "infinieye™";
        if (key.equals("seraph plating")) key = "seraph's plating";
        Normalized result = new Normalized(name, key, pet && !knownPet);
        if (item.length() <= 256) normalizedNames.put(item, result);
        return result;
    }

    private static boolean validName(String item) {
        return item != null && !item.isBlank() && item.length() <= 120 && item.chars().noneMatch(Character::isISOControl);
    }

    private static boolean pet(String item) {
        return PET.matcher(Zealots.strip(item).trim()).find();
    }

    public static boolean ambiguousPet(String item) {
        return item != null && normalized(item).ambiguousPet();
    }

    private static boolean priceable(String item) {
        return item != null && (FARMING_KEYS.contains(key(item)) || FARMING_DEFAULTS.containsKey(key(item))
                || tracked.contains(key(item)) || BossDrops.isSlayerDropName(item));
    }

    /** Lets a tracker's drop take a price. */
    public static void track(String item) {
        if (validName(item)) tracked.add(key(item));
    }

    public static String coins(long amount) {
        if (amount < 0) return "-" + coins(-amount);
        if (amount >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fb", amount / 1_000_000_000.0);
        if (amount >= 1_000_000L) return String.format(Locale.ROOT, "%.1fm", amount / 1_000_000.0);
        if (amount >= 1_000L) return String.format(Locale.ROOT, "%.1fk", amount / 1_000.0);
        return String.valueOf(amount);
    }

    /** The cost spelled out for chat, where there is room to show what a profit was measured against. */
    public static String spentLine(String costItem, long count) {
        long each = price(costItem);
        return "  §7Spent §f" + coins(each * count) + " §8(" + String.format(Locale.ROOT, "%,d", count) + " × " + coins(each) + ")";
    }

    /** What these drops come to, less what was spent. */
    public record Takings(long profit, boolean unpriced) { }

    public static Takings takings(Map<String, Integer> drops, String costItem, long spent) {
        long earned = 0;
        boolean unpriced = false;
        for (Map.Entry<String, Integer> drop : drops.entrySet()) {
            if (drop.getValue() <= 0) continue;
            if (priceInfo(drop.getKey()) == null) unpriced = true;
            earned += value(drop.getKey(), drop.getValue());
        }
        return new Takings(earned - price(costItem) * spent, unpriced);
    }

    public static Price priceInfo(String item) {
        if (item == null || ambiguousPet(item) || !priceable(item)) return null;
        String k = key(item);
        Long own = custom.get(k);
        if (own != null) return new Price(own, "Custom", 0);
        Evidence learned = sales.get(k);
        if (learning && learned != null && learned.confirmed > 0)
            return new Price(learned.confirmed, "Learned sale", learned.matches);
        Long builtIn = BUILTIN.get(k);
        if (builtIn != null) return new Price(builtIn, "Built-in NPC", 0);
        Long farming = FARMING_DEFAULTS.get(k);
        return farming == null ? null : new Price(farming, "Default", 0);
    }

    public static long price(String item) {
        Price value = priceInfo(item);
        return value == null ? 0 : value.coins();
    }

    public static long value(String item, int count) {
        long unit = price(item);
        if (unit <= 0 || count <= 0) return 0;
        return unit > Long.MAX_VALUE / count ? Long.MAX_VALUE : unit * count;
    }

    public static Long customPrice(String item) { return item == null ? null : custom.get(key(item)); }

    public static Price observedPrice(String item) {
        if (item == null || ambiguousPet(item) || !priceable(item)) return null;
        Evidence found = sales.get(key(item));
        if (found != null && found.confirmed > 0) return new Price(found.confirmed, "Learned sale", found.matches);
        Long builtIn = BUILTIN.get(key(item));
        return builtIn == null ? null : new Price(builtIn, "Built-in NPC", 0);
    }

    public static List<String> knownNames() {
        Map<String, String> names = new LinkedHashMap<>(BUILTIN_NAMES);
        ZEALOT_PROFIT_ITEMS.forEach(name -> names.put(key(name), name));
        sales.forEach((k, v) -> { if (priceable(v.name)) names.put(k, v.name); });
        customNames.forEach((k, v) -> { if (priceable(v)) names.put(k, v); });
        return names.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** A custom value is per one item, never per stack. */
    public static boolean setCustom(String item, long coins) {
        if (!validName(item) || ambiguousPet(item) || !priceable(item) || coins < 1 || coins > MAX_PRICE) return false;
        String k = key(item);
        Long oldPrice = custom.put(k, coins);
        String oldName = customNames.put(k, canonicalName(item));
        if (saveCustom()) { revision++; return true; }
        if (oldPrice == null) custom.remove(k); else custom.put(k, oldPrice);
        if (oldName == null) customNames.remove(k); else customNames.put(k, oldName);
        return false;
    }

    public static boolean clearCustom(String item) {
        if (item == null) return false;
        String k = key(item);
        Long oldPrice = custom.remove(k);
        String oldName = customNames.remove(k);
        if (saveCustom()) { revision++; return true; }
        if (oldPrice != null) custom.put(k, oldPrice);
        if (oldName != null) customNames.put(k, oldName);
        return false;
    }

    public static Long parseAmount(String text) {
        if (text == null) return null;
        Matcher m = AMOUNT.matcher(text.trim());
        if (!m.matches()) return null;
        long scale = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "b" -> 1_000_000_000L;
            default -> 1L;
        };
        try {
            long amount = new BigDecimal(m.group(1).replace(",", "")).multiply(BigDecimal.valueOf(scale)).longValueExact();
            return amount >= 1 && amount <= MAX_PRICE ? amount : null;
        } catch (ArithmeticException e) { return null; }
    }

    static Sale parseSale(String raw) {
        if (raw == null) return null;
        Matcher m = SOLD.matcher(Zealots.strip(raw).trim());
        if (!m.matches()) return null;
        String item = m.group(2).trim();
        // Any item, not just what a list holds yet: a sale made before a tracker first saw the
        // drop should already be the price when it does, on every list that shows it.
        if (!validName(item) || ambiguousPet(item)) return null;
        item = canonicalName(item);
        try {
            int count = Integer.parseInt(m.group(1).replace(",", ""));
            long total = Long.parseLong(m.group(3).replace(",", ""));
            if (count < 1 || total < count || total % count != 0) return null;
            long unit = total / count;
            return unit <= MAX_PRICE ? new Sale(item, count, unit) : null;
        } catch (NumberFormatException e) { return null; }
    }

    static boolean observe(String raw) {
        Sale sale = parseSale(raw);
        if (sale == null) return false;
        String k = key(sale.item());
        Evidence e = sales.computeIfAbsent(k, ignored -> new Evidence(sale.item(), sale.unitPrice(), 0, 0));
        e.name = sale.item();
        if (e.candidate == sale.unitPrice()) e.matches = Math.min(2, e.matches + 1);
        else { e.candidate = sale.unitPrice(); e.matches = 1; }
        boolean changed = e.confirmed != sale.unitPrice();
        e.confirmed = sale.unitPrice();
        salesDirty = true;
        revision++;
        return changed;
    }

    public static boolean reloadCustom() {
        Path path = customFile();
        if (!Files.exists(path)) {
            custom.clear();
            customNames.clear();
            revision++;
            return true;
        }
        try {
            Map<String, Long> prices = new LinkedHashMap<>();
            Map<String, String> names = new LinkedHashMap<>();
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) return false;
            JsonArray entries = root.getAsJsonArray("prices");
            if (entries == null || entries.size() > 10_000) return false;
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                String name = entry.get("item").getAsString();
                long coins = entry.get("coins").getAsLong();
                // A shared override can arrive before this client has seen the Slayer drop.
                // It stays dormant until that name is actually recorded as Slayer loot.
                if (!validName(name) || ambiguousPet(name) || coins < 1 || coins > MAX_PRICE) return false;
                String k = key(name);
                if (prices.putIfAbsent(k, coins) != null) return false;
                names.put(k, canonicalName(name));
            }
            custom.clear();
            custom.putAll(prices);
            customNames.clear();
            customNames.putAll(names);
            revision++;
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not read drop-prices.json: " + e);
            return false;
        }
    }

    private static boolean saveCustom() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray entries = new JsonArray();
        customNames.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(entry -> {
            JsonObject item = new JsonObject();
            item.addProperty("item", entry.getValue());
            item.addProperty("coins", custom.get(entry.getKey()));
            entries.add(item);
        });
        root.add("prices", entries);
        try { write(customFile(), GSON.toJson(root)); return true; }
        catch (IOException e) {
            System.err.println("[Endsight] could not write drop-prices.json: " + e);
            return false;
        }
    }

    private static void loadSales() {
        sales.clear();
        revision++;
        if (!Files.exists(salesFile())) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(salesFile(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) return;
            JsonArray entries = root.getAsJsonArray("sales");
            if (entries == null || entries.size() > 10_000) return;
            Map<String, Evidence> parsed = new LinkedHashMap<>();
            boolean promoted = false;
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                String name = entry.get("item").getAsString();
                long candidate = entry.get("candidate").getAsLong();
                int matches = entry.get("matches").getAsInt();
                long confirmed = entry.get("confirmed").getAsLong();
                // No check against the lists here: they fill in after this, and one name they
                // do not hold yet would throw away every sale in the file.
                if (!validName(name) || ambiguousPet(name)
                        || candidate < 1 || candidate > MAX_PRICE
                        || matches < 1 || matches > 2 || confirmed < 0 || confirmed > MAX_PRICE) return;
                // Older files held the first sale, including a changed price, as a candidate.
                if (confirmed != candidate) {
                    confirmed = candidate;
                    promoted = true;
                }
                if (parsed.putIfAbsent(key(name), new Evidence(canonicalName(name), candidate, matches, confirmed)) != null) return;
            }
            sales.putAll(parsed);
            if (promoted) salesDirty = true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not read npc-sales.json: " + e);
        }
    }

    private static void flushSales() {
        if (!salesDirty) return;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray entries = new JsonArray();
        sales.values().forEach(e -> {
            JsonObject item = new JsonObject();
            item.addProperty("item", e.name);
            item.addProperty("candidate", e.candidate);
            item.addProperty("matches", e.matches);
            item.addProperty("confirmed", e.confirmed);
            entries.add(item);
        });
        root.add("sales", entries);
        try {
            write(salesFile(), GSON.toJson(root));
            salesDirty = false;
            lastFlush = System.currentTimeMillis();
        } catch (IOException e) {
            System.err.println("[Endsight] could not write npc-sales.json: " + e);
        }
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, contents, StandardCharsets.UTF_8);
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
