package com.endsight.profile;

import com.endsight.storage.Recipes;
import com.google.common.collect.ImmutableMultimap;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.endsight.profile.ProfileData.*;

/** Native game models and head textures, independent of the website's image renderer. */
public final class ProfileItems {
    private final JsonObject catalog;
    private final Map<String, ItemStack> icons = new HashMap<>();
    private final Map<String, List<Component>> lores = new HashMap<>();
    public ProfileItems(JsonObject catalog) { this.catalog = catalog; }
    public static String key(ProfileData.Item item) { return item.location()+":"+item.slot(); }
    public JsonObject definition(ProfileData.Item item) { return object(object(catalog, "items"), item.entry()); }

    private Set<String> shared;
    /** Catalog names more than one item uses: seven "Shimmer Skin"s, one per dragon set. */
    private Set<String> sharedNames() {
        if (shared == null) {
            Map<String, Integer> seen = new HashMap<>();
            object(catalog, "items").entrySet().forEach(e -> {
                if (e.getValue().isJsonObject()) seen.merge(plain(text(e.getValue().getAsJsonObject(), "name", "")), 1, Integer::sum);
            });
            shared = new HashSet<>();
            seen.forEach((name, n) -> { if (n > 1 && !name.isBlank()) shared.add(name); });
        }
        return shared;
    }
    /** What to call an item: its own name, unless the catalog gives that name to several
     *  items, where the id says which one (unstable_shimmer reads "Unstable Shimmer Skin"). */
    public String displayName(ProfileData.Item item) {
        JsonObject pet = object(object(item.raw(), "extra"), "pet_data");
        // An owned pet's name carries its level, as on dragsim.co. A missing-pet suggestion
        // keeps its plain name: a level on a pet nobody owns would read as a real one.
        if (item.location().equals("pets") && !pet.isEmpty()) {
            String type = text(pet, "type", "").toLowerCase(Locale.ROOT);
            JsonObject def = ProfileCollection.petDefinition(catalog, type);
            return "[Lvl "+petLevel(pet, def, item.rarity()).level()+"] "+text(def, "display", title(type));
        }
        String name = item.name();
        if (!sharedNames().contains(name)) return name;
        String byId = title(item.entry());
        return name.endsWith(" Skin") && !byId.endsWith(" Skin") ? byId+" Skin" : byId;
    }
    /** The name line as the game draws it: rarity colour, then stars as gold ✪ after the
     *  name, the way Dragsim shows them, not as a "5 stars" lore line. */
    public Component nameLine(ProfileData.Item item) {
        var line = Component.literal(displayName(item)).withColor(color(item.rarity()) & 0xFFFFFF);
        int stars = integer(object(item.raw(), "item_modifiers"), "star_level", 0);
        if (stars > 0) line.append(Component.literal(" "+"✪".repeat(Math.min(stars, 10))).withColor(0xFFAA00));
        return line;
    }

    public ItemStack icon(ProfileData.Item item) {
        if (net.minecraft.client.Minecraft.getInstance().level == null) return ItemStack.EMPTY;
        return icons.computeIfAbsent(key(item), unused -> create(item));
    }

    public ItemStack playerHead(String uuid, String player) {
        if (net.minecraft.client.Minecraft.getInstance().level == null) return ItemStack.EMPTY;
        return icons.computeIfAbsent("@player", unused -> {
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
            // Explicit UUID resolution uses Minecraft's own skin manager. Head
            // textures on individual items below are already resolved properties.
            try { stack.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(UUID.fromString(uuid))); }
            catch (IllegalArgumentException ignored) { stack.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(player)); }
            return stack;
        });
    }

    private ItemStack create(ProfileData.Item item) {
        JsonObject raw = item.raw(), definition = definition(item), data = object(raw, "item_data"), extra = object(raw, "extra");
        String material = text(raw, "material", text(definition, "material", "paper")).replace("minecraft:", "");
        material = switch (material) { case "skull_item", "skull" -> "player_head"; default -> material; };
        net.minecraft.world.item.Item vanilla;
        try { vanilla = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("minecraft", material)); }
        catch (RuntimeException ignored) { vanilla = Items.PAPER; }
        if (vanilla == null || vanilla == Items.AIR) vanilla = Items.PAPER;
        ItemStack stack = new ItemStack(vanilla, Math.min(item.count(), 99));
        String skinId = text(object(extra, "item_skin_applied"), "skinId", text(object(extra, "item_skin"), "skinId", ""));
        String texture = text(object(object(object(catalog, "items"), skinId), "cosmetic"), "skin_value", "");
        if (texture.isEmpty()) texture = texture(raw.get("item_skin"));
        if (texture.isEmpty()) texture = texture(definition.get("skin"));
        if (item.location().equals("pets")) {
            String type = text(object(extra, "pet_data"), "type", "").toLowerCase(Locale.ROOT);
            JsonObject pet = ProfileCollection.petDefinition(catalog, type);
            texture = texture(object(object(catalog, "items"), text(object(extra, "pet_data"), "skinOverride", "")).get("skin"));
            if (texture.isEmpty()) texture = texture(pet.get("skin"));
            // No head texture anywhere (an unskinned Phoenix): the stand-in's vanilla icon
            // beats the blank player head an owned pet starts as.
            if (texture.isEmpty() && pet.has("material")) {
                try {
                    var icon = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("minecraft", text(pet, "material", "paper").replace("minecraft:", "")));
                    if (icon != null && icon != Items.AIR) stack = new ItemStack(icon);
                } catch (RuntimeException ignored) { }
            }
        }
        if (!texture.isEmpty()) {
            stack = new ItemStack(Items.PLAYER_HEAD, Math.min(item.count(), 99));
            Property property = new Property("textures", texture);
            PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", property));
            GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)), "Endsight", properties);
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        } else if (vanilla == Items.PLAYER_HEAD || vanilla == Items.PAPER) {
            ItemStack learned = Recipes.icon(item.name());
            if (learned != null && !learned.isEmpty()) stack = learned.copyWithCount(Math.min(item.count(), 99));
        }
        String color = text(data, "color", text(definition, "color", ""));
        JsonObject dye = object(object(object(catalog, "items"), text(object(extra, "item_dye"), "dyeId", "")), "cosmetic");
        JsonArray colors = array(dye, "colors");
        if (!colors.isEmpty()) color = colors.get(0).getAsString();
        try {
            int rgb;
            if (color.startsWith("#")) rgb = Integer.parseInt(color.substring(1), 16);
            else { String[] bits = color.split(","); rgb = Integer.parseInt(bits[0].trim())<<16 | Integer.parseInt(bits[1].trim())<<8 | Integer.parseInt(bits[2].trim()); }
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
        } catch (RuntimeException ignored) { }
        stack.set(DataComponents.CUSTOM_NAME, nameLine(item));
        stack.set(DataComponents.LORE, new ItemLore(lore(item)));
        return stack;
    }

    private static String texture(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        return value.isJsonObject() ? text(value.getAsJsonObject(), "value", "") : "";
    }

    public List<Component> lore(ProfileData.Item item) {
        return lores.computeIfAbsent(key(item), unused -> buildLore(item));
    }

    private List<Component> buildLore(ProfileData.Item item) {
        JsonObject petData = object(object(item.raw(), "extra"), "pet_data");
        if (!petData.isEmpty()) return petLore(item, petData);
        JsonObject raw = item.raw(), def = definition(item), data = object(raw, "item_data"), mods = object(raw, "item_modifiers"), extra = object(raw, "extra");
        List<Component> lines = new ArrayList<>();
        Map<String, Double> stats = new LinkedHashMap<>();
        object(object(raw, "item_stats"), "base").entrySet().forEach(e -> {
            if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) stats.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsDouble());
        });
        double books = number(mods, "hot_potato_books", 0)+number(mods, "golden_hot_potato_books", 0);
        // These are the authoritative saved base values. Adding only some upgrade
        // effects looked like a final stat total while omitting reforges and rolls.
        // Keep upgrades explicit below until every server modifier is modelled.
        if(!stats.isEmpty()) lines.add(Component.literal("Base stats").withColor(0x555555));
        for (Map.Entry<String, Double> stat : stats.entrySet()) if (Math.abs(stat.getValue()) > 1e-8)
            lines.add(Component.literal(title(stat.getKey())+": ").withColor(0xAAAAAA)
                    .append(Component.literal((stat.getValue()>0 ? "+" : "")+ProfileFormat.number(stat.getValue())).withColor(0xFF5555)));
        if (!stats.isEmpty()) lines.add(Component.empty());
        JsonObject reforgeStats=object(object(data,"reforge_stats"),"base");
        if(!reforgeStats.isEmpty()) {
            lines.add(Component.literal("Reforge bonuses").withColor(0x8C98A5));
            reforgeStats.entrySet().forEach(e -> {
                if(e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber() && e.getValue().getAsDouble()!=0)
                    lines.add(Component.literal(title(e.getKey())+": "+ProfileFormat.number(e.getValue().getAsDouble())).withColor(0xB5A6FF));
            });
            lines.add(Component.empty());
        }
        String reforge = text(data, "reforge", "");
        if (!reforge.isEmpty()) lines.add(Component.literal(reforge+" reforge").withColor(0x9B8CF5));
        if (bool(mods, "recombobulated")) lines.add(Component.literal("Recombobulated").withColor(0xFF77E0));
        if (bool(mods, "art_of_war")) lines.add(Component.literal("Art of War applied").withColor(0xE8BD77));
        if (bool(mods, "enrichment")) lines.add(Component.literal("Enriched").withColor(0xE8BD77));
        if (books > 0) lines.add(Component.literal(ProfileFormat.number(books)+" potato books").withColor(0xE8BD77));
        if(number(mods,"final_synchronizers",0)>0) lines.add(Component.literal(ProfileFormat.number(number(mods,"final_synchronizers",0))+" final synchronizers").withColor(0xE8BD77));
        if (mods.has("kills")) lines.add(Component.literal("Kills: "+ProfileFormat.number(number(mods, "kills", 0))).withColor(0xAAB4BE));
        if(extra.has("dungeon_quality")) lines.add(Component.literal("Dungeon quality: "+ProfileFormat.number(number(object(extra,"dungeon_quality"),"percent",0))+"%").withColor(0xAAB4BE));
        if(number(object(extra,"dungeon_upgrade"),"stars",0)>0) lines.add(Component.literal("Dungeon stars: "+ProfileFormat.number(number(object(extra,"dungeon_upgrade"),"stars",0))).withColor(0xFFCC55));
        JsonObject drill=object(extra,extra.has("drill") ? "drill" : "drill_data");
        for(String field:List.of("engine","tank","module")) {
            String id=text(drill,field,"");
            if(!id.isEmpty()) lines.add(Component.literal(title(field)+": "+text(object(object(catalog,"items"),id),"name",title(id))).withColor(0x66DDDD));
        }
        if(bool(drill,"coated") || bool(object(extra,"coating"),"coated")) lines.add(Component.literal("Coating applied").withColor(0x66DDDD));
        for (String field : List.of("item_skin_applied", "item_skin", "item_dye")) {
            String id = text(object(extra, field), field.equals("item_dye") ? "dyeId" : "skinId", "");
            if (!id.isEmpty()) lines.add(Component.literal(text(object(object(catalog, "items"), id), "name", title(id))).withColor(0x66DDDD));
        }
        for (JsonElement description : array(def, "description")) if (description.isJsonPrimitive()) lines.add(legacy(description.getAsString()));
        JsonObject lore = object(object(catalog, "itemLore"), item.entry());
        for (JsonElement element : array(lore, "abilities")) {
            if (!element.isJsonObject()) continue;
            JsonObject ability = element.getAsJsonObject();
            if (bool(ability, "hidden")) continue;
            lines.add(Component.empty());
            lines.add(Component.literal("Ability: "+text(ability, "name", "Ability")+"  ").withColor(0xFFAA00)
                    .append(Component.literal(text(ability,"trigger","")).withStyle(net.minecraft.ChatFormatting.YELLOW,net.minecraft.ChatFormatting.BOLD)));
            for (JsonElement line : array(ability, "lines")) if (line.isJsonPrimitive()) lines.add(legacy(line.getAsString()));
            if (number(ability, "cost", 0) > 0) lines.add(Component.literal(title(text(ability,"costType","Mana"))+" Cost: ").withColor(0x555555)
                    .append(Component.literal(ProfileFormat.number(number(ability,"cost",0))).withColor(0x55FFFF)));
        }
        lines.add(Component.empty());
        lines.add(Component.literal(item.rarity().replace('_', ' ')+" "+text(data, "item_type", "ITEM")).withColor(color(item.rarity()) & 0xFFFFFF));
        return List.copyOf(lines);
    }

    /** A pet's tooltip in the order dragsim.co lays it out: class, stats, abilities at its
     *  level, then level and held item. The ability text is the site's own, per level, from
     *  the catalog's petAbilities. Unlike the site, an ability the pet's rarity has not
     *  unlocked is still listed, greyed out with the rarity it needs: every Phoenix players
     *  own is Legendary and its only ability is Mythic, so the site shows it with no lore. */
    private List<Component> petLore(ProfileData.Item item, JsonObject pet) {
        String type = text(pet, "type", "").toLowerCase(Locale.ROOT), rarity = item.rarity();
        JsonObject def = ProfileCollection.petDefinition(catalog, type);
        PetLevel level = petLevel(pet, def, rarity);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(title(text(def, "category", "combat"))+" Pet").withColor(0x555555));
        JsonObject byRarity = object(def, "rarity_perks");
        JsonArray perks = array(byRarity, rarity.toLowerCase(Locale.ROOT));
        if (perks.isEmpty()) perks = array(byRarity, rarity);
        if (perks.isEmpty()) perks = array(def, "perks");
        for (JsonElement element : perks) {
            if (!element.isJsonObject()) continue;
            JsonObject perk = element.getAsJsonObject();
            String stat = text(perk, "stat", "");
            JsonObject known = new JsonObject();
            for (JsonElement s : array(catalog, "stats"))
                if (s.isJsonObject() && text(s.getAsJsonObject(), "id", "").equals(stat)) known = s.getAsJsonObject();
            long value = Math.round(number(perk, "per_level", 0)*(bool(perk, "flat") ? 1 : level.level()));
            String colour = text(known, "color", "");
            var code = colour.isEmpty() ? null : net.minecraft.ChatFormatting.getByCode(colour.charAt(0));
            lines.add(Component.literal(text(known, "name", title(stat))+": ").withColor(0xAAAAAA)
                    .append(Component.literal("+"+ProfileFormat.number(value)).withStyle(code == null ? net.minecraft.ChatFormatting.GREEN : code)));
        }
        for (JsonElement id : array(def, "abilities")) {
            if (!id.isJsonPrimitive()) continue;
            JsonObject ability = object(object(catalog, "petAbilities"), id.getAsString());
            String needs = text(ability, "minRarity", "COMMON").toUpperCase(Locale.ROOT);
            boolean unlocked = ProfileCollection.rank(rarity) >= ProfileCollection.rank(needs);
            JsonArray lore = array(object(ability, "levels"), Integer.toString(level.level()));
            lines.add(Component.empty());
            if (lore.isEmpty()) {
                lines.add(Component.literal(title(id.getAsString().replaceFirst("^pet_", ""))).withColor(unlocked ? 0xFFAA00 : 0x555555));
                continue;
            }
            for (int i = 0; i < lore.size(); i++) {
                String line = lore.get(i).getAsString();
                if (unlocked) { lines.add(legacy(line)); continue; }
                var locked = Component.literal(plain(line)).withColor(0x555555);
                if (i == 0) locked.append(Component.literal("  "+title(needs)).withColor(color(needs) & 0xFFFFFF));
                lines.add(locked);
            }
        }
        lines.add(Component.empty());
        lines.add(level.level() >= level.max() ? Component.literal("MAX LEVEL").withColor(0xFFAA00)
                : Component.literal("Level "+level.level()+" → "+(level.level()+1)+": ").withColor(0xFFAA00)
                    .append(Component.literal(ProfileFormat.number(level.remaining())+" / "+ProfileFormat.number(level.next())+" XP").withColor(0xAAAAAA)));
        String held = text(pet, "equippedItem", "");
        if (!held.isEmpty()) lines.add(Component.literal("Held: "+text(object(object(catalog, "items"), held.toLowerCase(Locale.ROOT)), "name", title(held))).withColor(0xE8BD77));
        if (integer(pet, "candiesUsed", 0) > 0) lines.add(Component.literal("Candies: "+integer(pet, "candiesUsed", 0)).withColor(0xAAB4BE));
        if (bool(pet, "active")) lines.add(Component.literal("Currently summoned").withColor(0x55FF55));
        lines.add(Component.empty());
        lines.add(Component.literal(rarity.replace('_', ' ')+" PET").withColor(color(rarity) & 0xFFFFFF));
        return List.copyOf(lines);
    }

    record PetLevel(int level, int max, double remaining, double next) { }
    /** A pet's level as dragsim.co works it out, ported from its script so the two agree.
     *  Every pet shows its max level, 100, unless its catalog entry says it levels; only the
     *  Golden Dragon does, from 101 to 200 on a flat table. A Golden Dragon saved before
     *  xpCurve 2 has its old-curve XP converted first, the way the site does. */
    static PetLevel petLevel(JsonObject pet, JsonObject def, String rarity) {
        boolean golden = text(pet, "type", "").equalsIgnoreCase("golden_dragon");
        int max = golden ? integer(def, "max_level", 200) : 100;
        if (!golden && !bool(def, "levels")) return new PetLevel(max, max, 0, 0);
        String tier = rarity.toUpperCase(Locale.ROOT);
        int base = switch (tier) { case "UNCOMMON" -> 175; case "RARE" -> 275; case "EPIC" -> 400; case "LEGENDARY" -> 600; case "MYTHIC" -> 800; default -> 100; };
        int step = switch (tier) { case "UNCOMMON" -> 125; case "RARE" -> 175; case "EPIC" -> 250; case "LEGENDARY" -> 350; case "MYTHIC" -> 500; default -> 75; };
        java.util.function.IntToDoubleFunction curve = f -> Math.floor((base+step*f)*(1+Math.pow(f/100.0, 2)*3));
        double xp = Math.max(0, number(pet, "totalXp", 0));
        if (golden && number(pet, "xpCurve", 0) < 2) {
            int f = 1; double left = xp;
            while (f < max && left >= curve.applyAsDouble(f)) left -= curve.applyAsDouble(f++);
            double converted = 0; int end = Math.max(101, f);
            for (int p = 101; p < end; p++) converted += p == 101 ? 650 : 204075;
            if (end < max && f >= 101) converted += Math.floor(left/curve.applyAsDouble(f)*(end == 101 ? 650 : 204075));
            xp = converted;
        }
        java.util.function.IntToDoubleFunction cost = f -> golden ? (f == 101 ? 650 : 204075) : curve.applyAsDouble(f);
        int level = golden ? 101 : 1; double left = xp;
        while (level < max && left >= cost.applyAsDouble(level)) left -= cost.applyAsDouble(level++);
        return new PetLevel(level, max, left, cost.applyAsDouble(level));
    }

    private static Component legacy(String value) {
        var result=Component.empty();
        var style=net.minecraft.network.chat.Style.EMPTY.withColor(0xAAAAAA);
        int start=0;
        for(int i=0;i<value.length()-1;i++) if(value.charAt(i)=='§' || value.charAt(i)=='&') {
            var code=net.minecraft.ChatFormatting.getByCode(value.charAt(i+1));
            if(code==null) continue;
            if(i>start) result.append(Component.literal(value.substring(start,i)).withStyle(style));
            style=style.applyFormat(code); i++; start=i+1;
        }
        if(start<value.length()) result.append(Component.literal(value.substring(start)).withStyle(style));
        return result;
    }

    public static int color(String rarity) {
        return 0xFF000000 | switch (rarity) {
            case "UNCOMMON" -> 0x55FF55; case "RARE" -> 0x5555FF; case "EPIC" -> 0xAA55DD;
            case "LEGENDARY" -> 0xFFAA00; case "MYTHIC" -> 0xFF55FF; case "DIVINE" -> 0x55FFFF;
            case "SPECIAL", "VERY_SPECIAL" -> 0xFF5555; default -> 0xDADAE2;
        };
    }
}
