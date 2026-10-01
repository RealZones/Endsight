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
    private static final Pattern AMOUNT = Pattern.compile("^([0-9][0-9,]*(?:\\.[0-9]{1,3})?)\\s*([kmb]?)$", Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Long> BUILTIN = Map.of(
            "zombie talisman", 200_000L,
            "revenant viscera", 500_000L);
    private static final Map<String, String> BUILTIN_NAMES = Map.of(
            "zombie talisman", "Zombie Talisman",
            "revenant viscera", "Revenant Viscera");
    private static final Map<String, Long> custom = new LinkedHashMap<>();
    private static final Map<String, String> customNames = new LinkedHashMap<>();
    private static final Map<String, Evidence> sales = new LinkedHashMap<>();
    private static boolean learning = true, salesDirty;
    private static long lastFlush;

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
    public static void learning(boolean value) { learning = value; }

    public static Path customFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("drop-prices.json");
    }

    private static Path salesFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("npc-sales.json");
    }

    private static String key(String item) {
        return Zealots.strip(item).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean validName(String item) {
        return item != null && !item.isBlank() && item.length() <= 120 && item.chars().noneMatch(Character::isISOControl);
    }

    private static boolean pet(String item) {
        return PET.matcher(Zealots.strip(item).trim()).find();
    }

    public static boolean ambiguousPet(String item) { return pet(item); }

    public static Price priceInfo(String item) {
        if (item == null || pet(item) || !BossDrops.isSlayerDropName(item)) return null;
        String k = key(item);
        Long own = custom.get(k);
        if (own != null) return new Price(own, "Custom", 0);
        Evidence learned = sales.get(k);
        if (learning && learned != null && learned.confirmed > 0)
            return new Price(learned.confirmed, "Learned sale", learned.matches);
        Long builtIn = BUILTIN.get(k);
        return builtIn == null ? null : new Price(builtIn, "Built-in NPC", 0);
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
        if (item == null || pet(item) || !BossDrops.isSlayerDropName(item)) return null;
        Evidence found = sales.get(key(item));
        if (found != null && found.confirmed > 0) return new Price(found.confirmed, "Learned sale", found.matches);
        Long builtIn = BUILTIN.get(key(item));
        return builtIn == null ? null : new Price(builtIn, "Built-in NPC", 0);
    }

    public static List<String> knownNames() {
        Map<String, String> names = new LinkedHashMap<>(BUILTIN_NAMES);
        sales.forEach((k, v) -> { if (BossDrops.isSlayerDropName(v.name)) names.put(k, v.name); });
        customNames.forEach((k, v) -> { if (BossDrops.isSlayerDropName(v)) names.put(k, v); });
        return names.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** A custom value is per one item, never per stack. */
    public static boolean setCustom(String item, long coins) {
        if (!validName(item) || pet(item) || !BossDrops.isSlayerDropName(item) || coins < 1 || coins > MAX_PRICE) return false;
        String k = key(item);
        Long oldPrice = custom.put(k, coins);
        String oldName = customNames.put(k, Zealots.strip(item).trim());
        if (saveCustom()) return true;
        if (oldPrice == null) custom.remove(k); else custom.put(k, oldPrice);
        if (oldName == null) customNames.remove(k); else customNames.put(k, oldName);
        return false;
    }

    public static boolean clearCustom(String item) {
        if (item == null) return false;
        String k = key(item);
        Long oldPrice = custom.remove(k);
        String oldName = customNames.remove(k);
        if (saveCustom()) return true;
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
        if (!validName(item) || pet(item) || !BossDrops.isSlayerDropName(item)) return null;
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
        return changed;
    }

    public static boolean reloadCustom() {
        Path path = customFile();
        if (!Files.exists(path)) {
            custom.clear();
            customNames.clear();
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
                if (!validName(name) || pet(name) || coins < 1 || coins > MAX_PRICE) return false;
                String k = key(name);
                if (prices.putIfAbsent(k, coins) != null) return false;
                names.put(k, name.trim());
            }
            custom.clear();
            custom.putAll(prices);
            customNames.clear();
            customNames.putAll(names);
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
                if (!validName(name) || pet(name) || !BossDrops.isSlayerDropName(name)
                        || candidate < 1 || candidate > MAX_PRICE
                        || matches < 1 || matches > 2 || confirmed < 0 || confirmed > MAX_PRICE) return;
                // Older files held the first sale, including a changed price, as a candidate.
                if (confirmed != candidate) {
                    confirmed = candidate;
                    promoted = true;
                }
                if (parsed.putIfAbsent(key(name), new Evidence(name, candidate, matches, confirmed)) != null) return;
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
