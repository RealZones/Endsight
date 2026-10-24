package com.endsight.profile;

import com.google.gson.*;
import java.util.*;
import static com.endsight.profile.ProfileData.*;

/** Catalog suggestions never enter the owned-asset list or valuation requests. */
public final class ProfileCollection {
    public final ProfileData data;
    public final JsonObject catalog;
    public final List<ProfileData.Item> accessories, missingAccessories, pets, missingPets;
    private static final List<String> TIERS=List.of("COMMON","UNCOMMON","RARE","EPIC","LEGENDARY","MYTHIC","DIVINE","SPECIAL","VERY_SPECIAL");
    public static int rank(String rarity) { return TIERS.indexOf(rarity.toUpperCase(Locale.ROOT)); }
    public ProfileCollection(ProfileData data,JsonObject catalog) {
        this.data=data; this.catalog=catalog;
        JsonObject definitions=object(catalog,"items");
        Map<String,ProfileData.Item> owned=new TreeMap<>(); Map<String,Integer> families=new HashMap<>();
        for(ProfileData.Item item:data.assets) {
            JsonObject definition=object(definitions,item.entry());
            if(!accessory(definition)) continue;
            owned.putIfAbsent(item.entry(),item);
            families.merge(family(item.entry(),definition),rank(text(definition,"tier",item.rarity())),Math::max);
        }
        Comparator<ProfileData.Item> byName=Comparator.comparing(ProfileData.Item::name,String.CASE_INSENSITIVE_ORDER).thenComparing(ProfileData.Item::entry);
        accessories=owned.values().stream().sorted(byName).toList();
        Map<String,String> targets=new TreeMap<>();
        for(var entry:definitions.entrySet()) if(entry.getValue().isJsonObject() && accessory(entry.getValue().getAsJsonObject())) {
            String id=entry.getKey(),family=family(id,entry.getValue().getAsJsonObject()),old=targets.get(family);
            if(old==null || rank(text(entry.getValue().getAsJsonObject(),"tier","COMMON"))>rank(text(object(definitions,old),"tier","COMMON"))) targets.put(family,id);
        }
        List<ProfileData.Item> missing=new ArrayList<>();
        for(var target:targets.entrySet()) {
            JsonObject definition=object(definitions,target.getValue());
            if(families.getOrDefault(target.getKey(),-2)<rank(text(definition,"tier","COMMON"))) missing.add(suggestion("accessories",target.getValue(),definition,missing.size()));
        }
        missingAccessories=missing.stream().sorted(byName).toList();
        pets=data.pets.stream().sorted(Comparator.<ProfileData.Item>comparingInt(p -> bool(object(object(p.raw(),"extra"),"pet_data"),"active") ? 0 : 1).thenComparing(byName)).toList();
        Set<String> species=new HashSet<>();
        for(ProfileData.Item pet:pets) species.add(text(object(object(pet.raw(),"extra"),"pet_data"),"type","").toLowerCase(Locale.ROOT));
        List<ProfileData.Item> absent=new ArrayList<>();
        for(var entry:object(catalog,"pets").entrySet()) if(entry.getValue().isJsonObject() && !species.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
            JsonObject pet=entry.getValue().getAsJsonObject(),def=new JsonObject();
            def.addProperty("name",text(pet,"display",title(entry.getKey())));
            String rarity="COMMON";
            for(JsonElement tier:array(pet,"rarities")) if(tier.isJsonPrimitive() && rank(tier.getAsString())>rank(rarity)) rarity=tier.getAsString();
            def.addProperty("tier",rarity); def.addProperty("category","PET");
            def.addProperty("material","minecraft:player_head");
            ProfileData.Item item=suggestion("pets",entry.getKey()+"_pet_"+rarity.toLowerCase(Locale.ROOT),def,absent.size());
            JsonObject extra=new JsonObject(),petData=new JsonObject(); petData.addProperty("type",entry.getKey());
            extra.add("pet_data",petData); item.raw().add("extra",extra);
            if(pet.has("skin")) item.raw().add("item_skin",pet.get("skin"));
            absent.add(item);
        }
        missingPets=absent.stream().sorted(byName).toList();
    }
    private static boolean accessory(JsonObject definition) {
        return Set.of("ACCESSORY","TALISMAN","RING","ARTIFACT","RELIC").contains(text(definition,"category",""));
    }
    private static String family(String id,JsonObject definition) { return text(definition,"accessory_family",id); }
    private static ProfileData.Item suggestion(String section,String id,JsonObject definition,int slot) {
        JsonObject raw=new JsonObject(),data=new JsonObject(); raw.addProperty("entry",id); raw.addProperty("count",1);
        raw.addProperty("material",text(definition,"material","paper"));
        data.addProperty("item_name",text(definition,"name",title(id))); data.addProperty("rarity",text(definition,"tier","COMMON"));
        data.addProperty("item_type",text(definition,"category","ITEM")); raw.add("item_data",data);
        return new ProfileData.Item("catalog/"+section,slot,raw);
    }
}
