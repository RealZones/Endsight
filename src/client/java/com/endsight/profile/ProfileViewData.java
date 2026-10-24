package com.endsight.profile;

import com.google.gson.*;
import java.util.*;
import static com.endsight.profile.ProfileData.*;

/** Derived presentation data belongs to one profile/catalog snapshot, never to a frame. */
public final class ProfileViewData {
    public record Metric(String key,String label,double value) { }
    public record Group(String name,JsonObject values) { }
    private record Filtered(String query,boolean rarity,List<ProfileData.Item> values) { }
    public final ProfileData data;
    public final JsonObject catalog,bestiary=new JsonObject(),meters=new JsonObject(),unknownNodes=new JsonObject(),dungeonSummary=new JsonObject();
    public final long dragons;
    private final Map<String,List<ProfileData.Container>> pages=new HashMap<>(),usedPages=new HashMap<>();
    private final Map<String,List<ProfileData.Item>> contents=new HashMap<>();
    private final Map<JsonObject,List<Metric>> metrics=new IdentityHashMap<>();
    private final Map<JsonObject,List<Group>> groups=new IdentityHashMap<>();
    private final Map<List<ProfileData.Item>,Filtered> filtered=new IdentityHashMap<>();
    private final Map<ProfileData.Item,String> searchText=new IdentityHashMap<>();
    private final Map<String,Level> levels=new HashMap<>();

    public ProfileViewData(ProfileData data,JsonObject catalog) {
        this.data=data;this.catalog=catalog;
        for(String type:List.of("inventory","ender_chest","storage","wardrobe")) {
            var selected=data.containers.stream().filter(c -> type.equals("storage") || type.equals("wardrobe") ? c.id().startsWith(type+"/") : c.id().equals(type)).toList();
            pages.put(type,selected);usedPages.put(type,selected.stream().filter(c -> !c.slots().isEmpty()).toList());
            contents.put(type,selected.stream().flatMap(c -> c.slots().values().stream()).toList());
        }
        long killed=0;
        for(var entry:object(data.profile,"bestiary").entrySet()) {
            double count=number(entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : null,"kills",0);
            if(count>0) bestiary.addProperty(entry.getKey(),count);
            if(entry.getKey().endsWith("_DRAGON")) killed+=(long)count;
        }
        dragons=killed;
        object(data.profile,"rng_meters").entrySet().forEach(e -> { if(e.getValue().isJsonObject()) meters.addProperty(e.getKey(),number(e.getValue().getAsJsonObject(),"kills",0)); });
        Set<String> mapped=new HashSet<>();ProfileMining.NODES.forEach(n -> mapped.add(n.id()));
        object(object(data.profile,"hotd"),"nodes").entrySet().forEach(e -> { if(!mapped.contains(e.getKey())) unknownNodes.add(e.getKey(),e.getValue()); });
        JsonObject dungeon=object(data.profile,"dungeons");
        for(String key:List.of("secrets","zombie_kills","skeletor_kills")) if(dungeon.has(key)) dungeonSummary.add(key,dungeon.get(key));
    }
    public List<ProfileData.Container> pages(String type) { return pages.getOrDefault(type,List.of()); }
    public List<ProfileData.Container> usedPages(String type) { return usedPages.getOrDefault(type,List.of()); }
    public Level skillLevel(String skill) { return levels.computeIfAbsent(skill,key -> data.skillLevel(key,catalog)); }
    public List<ProfileData.Item> search(String type,String query) { return filter(contents.getOrDefault(type,List.of()),query,true); }
    public List<ProfileData.Item> filter(List<ProfileData.Item> source,String query,boolean rarity) {
        if(query.isBlank()) return source;
        String needle=query.toLowerCase(Locale.ROOT);
        Filtered old=filtered.get(source);
        if(old!=null && old.rarity()==rarity && old.query().equals(needle)) return old.values();
        List<ProfileData.Item> values=source.stream().filter(it -> {
            String text=searchText.computeIfAbsent(it,item -> (item.name()+" "+item.entry()).toLowerCase(Locale.ROOT));
            return text.contains(needle) || rarity && (text+" "+it.rarity().toLowerCase(Locale.ROOT)).contains(needle);
        }).toList();
        filtered.put(source,new Filtered(needle,rarity,values));return values;
    }
    public List<Metric> metrics(JsonObject values) {
        if(values.isEmpty()) return List.of();
        return metrics.computeIfAbsent(values,source -> source.entrySet().stream()
                .filter(e -> e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber())
                .sorted(Comparator.comparingDouble((Map.Entry<String,JsonElement> e) -> -e.getValue().getAsDouble())
                        .thenComparing(Map.Entry::getKey,String.CASE_INSENSITIVE_ORDER))
                .map(e -> new Metric(e.getKey(),title(e.getKey().replaceFirst("(?i)^(dragon_|mine_)","")),e.getValue().getAsDouble())).toList());
    }
    public List<Group> groups(JsonObject values) {
        if(values.isEmpty()) return List.of();
        return groups.computeIfAbsent(values,source -> {
            Map<String,JsonObject> grouped=new LinkedHashMap<>();
            for(String group:List.of("Dragons","Slayer","Mining","End island","Other")) grouped.put(group,new JsonObject());
            for(var entry:source.entrySet()) {
                String key=entry.getKey().toLowerCase(Locale.ROOT),group;
                if(key.contains("dragon")) group="Dragons";
                else if(key.contains("revenant") || key.contains("voidgloom") || key.contains("ghoul") || key.contains("zombie")) group="Slayer";
                else if(key.startsWith("mine_") || key.contains("amethyst")) group="Mining";
                else if(key.contains("nest") || key.contains("zealot") || key.contains("enderman") || key.contains("voidling") || key.contains("protector")) group="End island";
                else group="Other";
                grouped.get(group).add(entry.getKey(),entry.getValue());
            }
            return grouped.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                    .sorted(Comparator.comparingDouble((Map.Entry<String,JsonObject> e) -> -e.getValue().entrySet().stream().mapToDouble(v -> v.getValue().getAsDouble()).sum()))
                    .map(e -> new Group(e.getKey(),e.getValue())).toList();
        });
    }
}
