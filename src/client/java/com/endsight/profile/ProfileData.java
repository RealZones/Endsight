package com.endsight.profile;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/** Saved profile data; absence stays distinct from an actual zero. No Minecraft state. */
public final class ProfileData {
    private static final Pattern FORMATTING=Pattern.compile("§[0-9A-FK-ORa-fk-or]");
    public record Choice(String id, String name, String mode, boolean selected) { }
    public record Item(String location, int slot, JsonObject raw) {
        public String entry() { return text(raw, "entry", ""); }
        public String name() {
            String name=text(object(raw,"item_data"),"item_name",null);
            return name==null ? title(entry()) : plain(name);
        }
        public String rarity() { return text(object(raw, "item_data"), "rarity", "COMMON"); }
        public int count() { return Math.max(1, integer(raw, "count", 1)); }
    }
    public record Container(String id, String name, int capacity, Map<Integer, Item> slots) { }
    public record Level(int level, double progress, double xp, boolean maxed) { }
    public final JsonObject root, profile;
    public final String player, uuid, rank, mode, profileName, profileId;
    public final long savedAt;
    public final boolean hidden;
    public final List<Choice> profiles;
    public final List<Container> containers;
    public final List<Item> assets;
    public final List<Item> pets;

    public ProfileData(JsonObject root) {
        if (!root.has("profile") || !root.get("profile").isJsonObject()) throw new IllegalArgumentException("Profile is unavailable.");
        this.root = root;
        profile = object(root, "profile");
        JsonObject identity = object(root, "player");
        player = text(identity, "player", "Unknown player");
        uuid = text(identity, "uuid", ""); rank = text(identity, "rank", "");
        mode = text(root, "mode", "main"); profileId = text(profile, "id", "");
        profileName = plain(text(profile, "profile_name", "Profile"));
        savedAt = (long) number(profile, "updated_at", 0);
        hidden = bool(profile, "inventory_hidden");
        List<Choice> choices = new ArrayList<>();
        for (JsonElement element : array(root, "profiles")) {
            if (!element.isJsonObject()) continue;
            JsonObject choice = element.getAsJsonObject();
            choices.add(new Choice(text(choice, "id", ""), plain(text(choice, "name", "Profile")),
                    text(choice, "mode", "NORMAL"), bool(choice, "selected")));
        }
        profiles = List.copyOf(choices);
        List<Container> pages = new ArrayList<>();
        List<Item> owned = new ArrayList<>(), petItems = new ArrayList<>();
        // Privacy wins even if a malformed response accidentally includes containers.
        if (!hidden) {
            add(pages, owned, "inventory", "Inventory", object(profile, "inventory"), 45, Set.of());
            add(pages, owned, "ender_chest", "Ender chest", object(profile, "ender_chest"), 27, Set.of());
            JsonObject storage = object(profile, "storage");
            for (String key : numericKeys(storage))
                add(pages, owned, "storage/"+key, "Storage "+(parseInt(key, 0)+1), object(storage, key), 45, Set.of());
            add(pages, owned, "accessories", "Accessories", object(profile, "accessory_bag"), 27, Set.of());
            JsonObject wardrobe = object(profile, "wardrobe"), slots = object(wardrobe, "slots");
            int equipped = integer(wardrobe, "equipped", -1);
            String[] pieces = {"helmet", "chestplate", "leggings", "boots"};
            List<String> wardrobeKeys=new ArrayList<>(numericKeys(slots));
            String equippedKey=Integer.toString(equipped);
            if(equipped>=0 && equipped<9 && !wardrobeKeys.contains(equippedKey)
                    && java.util.stream.IntStream.range(41,45).anyMatch(slot -> object(profile,"inventory").has(Integer.toString(slot)))) {
                wardrobeKeys.add(equippedKey); wardrobeKeys.sort(Comparator.comparingInt(Integer::parseInt));
            }
            for (String key : wardrobeKeys) {
                JsonObject set = object(slots, key), mapped = new JsonObject();
                Set<Integer> aliases = new HashSet<>();
                for (int i = 0; i < pieces.length; i++) {
                    if (set.has(pieces[i])) mapped.add(Integer.toString(i), set.get(pieces[i]));
                    else if(parseInt(key,-2)==equipped && object(profile,"inventory").has(Integer.toString(41+i)))
                        mapped.add(Integer.toString(i),object(profile,"inventory").get(Integer.toString(41+i)));
                    // The equipped wardrobe slot references inventory armor. Count
                    // those references once, but retain both views for browsing.
                    if (parseInt(key, -2) == equipped && object(profile, "inventory").has(Integer.toString(41+i))) aliases.add(i);
                }
                add(pages, owned, "wardrobe/"+key, "Wardrobe "+(parseInt(key, 0)+1), mapped, 4, aliases);
            }
            int index = 0;
            for (JsonElement element : array(profile, "pets")) {
                if (!element.isJsonObject()) continue;
                JsonObject pet = element.getAsJsonObject(), raw = new JsonObject(), data = new JsonObject();
                String type = text(pet, "type", "unknown").toLowerCase(Locale.ROOT);
                String rarity = text(pet, "rarity", "COMMON");
                raw.addProperty("entry", type+"_pet_"+rarity.toLowerCase(Locale.ROOT));
                raw.addProperty("count", 1); raw.addProperty("material", "minecraft:player_head");
                data.addProperty("item_name", "[Lvl 100] "+title(type));
                data.addProperty("rarity", rarity); data.addProperty("item_type", "PET"); data.addProperty("reforge", "");
                raw.add("item_data", data);
                JsonObject extra = new JsonObject(); extra.add("pet_data", pet.deepCopy()); raw.add("extra", extra);
                Item item = new Item("pets", index++, raw); petItems.add(item); owned.add(item);
                // A held pet item is real value (a Lucky Clover is over 1b) that lives nowhere
                // else in the profile, so it joins the assets to be priced. It is not drawn
                // anywhere; its rarity comes from the catalog on the price service.
                String held = text(pet, "equippedItem", "");
                if (held.matches("[a-z0-9_]{1,100}")) {
                    JsonObject heldRaw = new JsonObject(), heldData = new JsonObject();
                    heldRaw.addProperty("entry", held); heldRaw.addProperty("count", 1);
                    heldData.addProperty("item_name", title(held.replaceFirst("^pet_item_", "")));
                    heldRaw.add("item_data", heldData);
                    owned.add(new Item("pet_items", index-1, heldRaw));
                }
            }
            // Forge jobs, ready or still running: the output is the player's either way, and
            // it is in no container. Priced, not drawn.
            int job = 0;
            for (JsonElement element : array(profile, "forge")) {
                if (!element.isJsonObject()) continue;
                String recipe = text(element.getAsJsonObject(), "recipe", "");
                if (!recipe.matches("[a-z0-9_]{1,100}")) continue;
                JsonObject forged = new JsonObject(), forgedData = new JsonObject();
                forged.addProperty("entry", recipe); forged.addProperty("count", 1);
                forgedData.addProperty("item_name", title(recipe));
                forged.add("item_data", forgedData);
                owned.add(new Item("forge", job++, forged));
            }
        }
        containers = List.copyOf(pages); assets = List.copyOf(owned); pets = List.copyOf(petItems);
    }

    private static void add(List<Container> pages, List<Item> owned, String id, String name,
                            JsonObject source, int capacity, Set<Integer> aliases) {
        Map<Integer, Item> items = new TreeMap<>();
        for (String key : numericKeys(source)) {
            int slot = parseInt(key, -1);
            JsonObject raw = object(source, key);
            if (slot < 0 || slot > 4095 || text(raw, "entry", "").isBlank() || integer(raw, "count", 1) <= 0) continue;
            Item item = new Item(id, slot, raw);
            items.put(slot, item);
            if (!aliases.contains(slot)) owned.add(item);
        }
        int size = Math.max(capacity, items.isEmpty() ? capacity : items.keySet().stream().max(Integer::compareTo).orElse(0)+1);
        pages.add(new Container(id, name, Math.min(4096, size), Collections.unmodifiableMap(items)));
    }

    public Container container(String id) { return containers.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null); }
    public static List<String> numericKeys(JsonObject value) {
        return value.keySet().stream().filter(k -> k.matches("\\d{1,4}"))
                .sorted(Comparator.comparingInt(Integer::parseInt)).toList();
    }
    public static JsonObject object(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject();
    }
    public static JsonArray array(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonArray() ? value.getAsJsonArray(key) : new JsonArray();
    }
    public static String text(JsonObject value, String key, String fallback) {
        try { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    public static double number(JsonObject value, String key, double fallback) {
        if(value==null) return fallback;
        JsonElement element=value.get(key);
        if(element==null || !element.isJsonPrimitive()) return fallback;
        try { double n = element.getAsDouble(); return Double.isFinite(n) ? n : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    public static int integer(JsonObject value, String key, int fallback) { return (int) number(value, key, fallback); }
    public static boolean bool(JsonObject value, String key) {
        if(value==null) return false;
        JsonElement element=value.get(key);
        try { return element!=null && element.isJsonPrimitive() && element.getAsBoolean(); } catch (RuntimeException ignored) { return false; }
    }
    private static int parseInt(String text, int fallback) { try { return Integer.parseInt(text); } catch (RuntimeException ignored) { return fallback; } }
    public static String plain(String value) { return value.indexOf('§')<0 ? value : FORMATTING.matcher(value).replaceAll(""); }
    public static String title(String value) {
        StringBuilder result = new StringBuilder();
        for (String word : value.toLowerCase(Locale.ROOT).replace('_', ' ').split(" "))
            if (!word.isEmpty()) result.append(result.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        return result.toString();
    }
    public static Level level(String skill, double xp, JsonObject catalog) {
        return level(skill,xp,catalog,50);
    }
    public Level skillLevel(String skill,JsonObject catalog) {
        JsonObject slayer=object(profile,"slayer");
        int cap=skill.equals("combat") ? 50+Math.clamp(integer(slayer,"zombie_level",0)-4,0,5)
                +Math.clamp(integer(slayer,"enderman_level",0)-4,0,5) : 50;
        return level(skill,number(object(profile,"skills"),skill,0),catalog,cap);
    }
    private static Level level(String skill, double xp, JsonObject catalog,int cap) {
        JsonObject curves = object(catalog, "progression");
        JsonArray thresholds = array(curves, curves.has(skill) ? skill : "default");
        if (thresholds.isEmpty()) return new Level(-1,0,xp,false);
        int level = 0;
        int maximum=Math.min(cap,thresholds.size());
        while (level < maximum && xp >= thresholds.get(level).getAsDouble()) level++;
        double start = level == 0 ? 0 : thresholds.get(level-1).getAsDouble();
        boolean maxed = level == maximum;
        double end = maxed || thresholds.isEmpty() ? start : thresholds.get(level).getAsDouble();
        return new Level(level, maxed ? 1 : Math.clamp((xp-start)/Math.max(1, end-start), 0, 1), xp, maxed);
    }
}
