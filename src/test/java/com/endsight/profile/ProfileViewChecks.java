package com.endsight.profile;

import com.google.gson.*;
import java.lang.management.ManagementFactory;
import java.text.NumberFormat;
import java.util.*;
import static com.endsight.profile.ProfileData.*;

/** Presentation caches must preserve ordering/search, and refresh with the owning snapshot. */
public final class ProfileViewChecks {
    private static int checks;
    private static volatile Object consumed;
    private static void yes(boolean result,String why) { checks++;if(!result) throw new AssertionError(why); }
    private static JsonObject item(String id,String name,String rarity) {
        JsonObject item=new JsonObject(),details=new JsonObject();item.addProperty("entry",id);item.addProperty("count",3);
        details.addProperty("item_name",name);details.addProperty("rarity",rarity);item.add("item_data",details);return item;
    }
    private static ProfileData data() {
        JsonObject root=JsonParser.parseString("{\"player\":{\"player\":\"Tester\"},\"profile\":{\"inventory\":{},\"storage\":{\"0\":{},\"1\":{}},\"bestiary\":{},\"rng_meters\":{\"REVENANT\":{\"kills\":7}},\"hotd\":{\"nodes\":{\"NEW_NODE\":3}}}}").getAsJsonObject();
        JsonObject p=object(root,"profile");object(p,"inventory").add("0",item("frozen_scythe","§5Frozen Scythe","EPIC"));
        object(object(p,"storage"),"0").add("0",item("null_atom","Null Atom","DIVINE"));
        object(object(p,"storage"),"1").add("1",item("frozen_fragment","Frozen Fragment","LEGENDARY"));
        for(String key:List.of("GOLDEN_DRAGON","REVENANT","mine_end_stone","ZEALOT","other","zero")) {
            JsonObject value=new JsonObject();value.addProperty("kills",key.equals("zero") ? 0 : 12);object(p,"bestiary").add(key,value);
        }
        return new ProfileData(root);
    }
    private static List<String> referenceMetrics(JsonObject values) {
        return values.entrySet().stream().filter(e -> e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber())
                .sorted(Comparator.comparingDouble((Map.Entry<String,JsonElement> e) -> -e.getValue().getAsDouble()).thenComparing(Map.Entry::getKey,String.CASE_INSENSITIVE_ORDER))
                .map(Map.Entry::getKey).toList();
    }
    private static List<ProfileViewData.Group> referenceGroups(JsonObject values) {
        Map<String,JsonObject> groups=new LinkedHashMap<>();
        for(String name:List.of("Dragons","Slayer","Mining","End island","Other")) groups.put(name,new JsonObject());
        for(var entry:values.entrySet()) {
            String key=entry.getKey().toLowerCase(Locale.ROOT),group;
            if(key.contains("dragon")) group="Dragons";
            else if(key.contains("revenant") || key.contains("voidgloom") || key.contains("ghoul") || key.contains("zombie")) group="Slayer";
            else if(key.startsWith("mine_") || key.contains("amethyst")) group="Mining";
            else if(key.contains("nest") || key.contains("zealot") || key.contains("enderman") || key.contains("voidling") || key.contains("protector")) group="End island";
            else group="Other";
            groups.get(group).add(entry.getKey(),entry.getValue());
        }
        return groups.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                .sorted(Comparator.comparingDouble((Map.Entry<String,JsonObject> e) -> -e.getValue().entrySet().stream().mapToDouble(v -> v.getValue().getAsDouble()).sum()))
                .map(e -> new ProfileViewData.Group(e.getKey(),e.getValue())).toList();
    }
    private static String originalFormat(double value,boolean compact) {
        if(!Double.isFinite(value)) return "—";
        String suffix=compact ? Math.abs(value)>=1e12 ? "T" : Math.abs(value)>=1e9 ? "B" : Math.abs(value)>=1e6 ? "M" : Math.abs(value)>=1e3 ? "K" : "" : "";
        double divisor=switch(suffix){case "T" -> 1e12;case "B" -> 1e9;case "M" -> 1e6;case "K" -> 1e3;default -> 1;};
        NumberFormat format=NumberFormat.getNumberInstance(Locale.US);format.setMaximumFractionDigits(compact ? 2 : value==Math.rint(value) ? 0 : 1);
        return format.format(value/divisor)+suffix;
    }
    public static void main(String[] args) throws Exception {
        ProfileData data=data();var prepared=new ProfileViewData(data,new JsonObject());
        yes(prepared.pages("storage").size()==2,"All storage pages remain available");
        yes(prepared.pages("storage")==prepared.pages("storage"),"Unchanged pages reuse their plan");
        for(String type:List.of("inventory","storage","wardrobe","ender_chest")) for(String query:List.of("f","SCYTHE","legendary","null","does not exist"," ")) {
            String needle=query.toLowerCase(Locale.ROOT);
            var expected=data.containers.stream().filter(c -> type.equals("storage") || type.equals("wardrobe") ? c.id().startsWith(type+"/") : c.id().equals(type))
                    .flatMap(c -> c.slots().values().stream()).filter(it -> query.isBlank() || (it.name()+" "+it.entry()+" "+it.rarity()).toLowerCase(Locale.ROOT).contains(needle)).toList();
            yes(prepared.search(type,query).equals(expected),"Search semantics unchanged: "+type+" / "+query);
        }
        yes(prepared.search("storage","frozen")==prepared.search("storage","frozen"),"Unchanged search reuses its result");
        yes(prepared.dragons==12 && !prepared.bestiary.has("zero"),"Dragon total and nonzero bestiary values preserved");
        yes(prepared.meters.get("REVENANT").getAsDouble()==7,"RNG data retained");
        yes(prepared.unknownNodes.has("NEW_NODE"),"Future mining nodes stay visible");
        JsonObject values=new JsonObject();Random random=new Random(91);
        for(int i=0;i<240;i++) values.addProperty(List.of("dragon_","mine_","revenant_","zealot_","misc_").get(i%5)+i,random.nextInt(100));
        values.addProperty("not_a_number","x");
        yes(prepared.metrics(values).stream().map(ProfileViewData.Metric::key).toList().equals(referenceMetrics(values)),"Descending order and name ties match the previous renderer");
        values.remove("not_a_number");
        var original=referenceGroups(values);
        var cached=prepared.groups(values);
        yes(original.equals(cached),"Group categories and descending total order are identical");
        yes(prepared.groups(values)==cached,"Groups are not rebuilt each frame");
        yes(prepared.metrics(values)==prepared.metrics(values),"Metric sorting is not repeated each frame");
        var next=new ProfileViewData(data(),new JsonObject());
        yes(next.groups(values)!=cached,"A new profile snapshot gets a separate presentation cache");
        for(double value:new double[]{0,-0.0,1,-1,12.25,999.999,1000,1500000,-1234567.89,6_036_639_049L,1e12,Double.NaN,Double.POSITIVE_INFINITY}) {
            yes(ProfileFormat.number(value).equals(originalFormat(value,false)),"Full number formatting unchanged: "+value);
            yes(ProfileFormat.compact(value).equals(originalFormat(value,true)),"Compact formatting unchanged: "+value);
        }
        for(String name:List.of("plain","§5Frozen §lScythe","§zliteral","§AUpper","a§rb"))
            yes(plain(name).equals(name.replaceAll("§[0-9A-FK-ORa-fk-or]","")),"Formatting stripping unchanged");
        yes(ProfileData.number(new JsonObject(),"missing",8)==8 && !ProfileData.bool(new JsonObject(),"missing"),"Missing fields retain their fallbacks");
        // Exercise the same content cache used by the screen without a client/window.
        var tooltips=new ProfileTooltipCache();var icons=new ProfileItems(new JsonObject());var absent=new JsonObject();
        var shown=data.assets.getFirst();
        var firstTip=tooltips.get(icons,shown,absent,false,0xAAAAAA,0x777777);
        yes(firstTip==tooltips.get(icons,shown,absent,false,0xAAAAAA,0x777777),"Hovering the same item reuses the real tooltip list");
        JsonObject estimate=new JsonObject();estimate.addProperty("value",800000000);estimate.addProperty("method","manual");
        long now=System.currentTimeMillis();
        var fresh=new ProfileClient.Prices(now,now,Map.of(ProfileItems.key(shown),estimate));
        var pricedTip=tooltips.get(icons,shown,fresh.values().get(ProfileItems.key(shown)),false,0xAAAAAA,0x777777);
        yes(firstTip!=pricedTip && pricedTip.stream().anyMatch(line -> line.getString().contains("800M")),"A fresh estimate immediately rebuilds tooltip pricing");
        yes(pricedTip==tooltips.get(icons,shown,estimate,false,0xAAAAAA,0x777777),"Fresh unchanged price lines reuse their tooltip");
        var expired=new ProfileClient.Prices(now-121000,now-121000,Map.of(ProfileItems.key(shown),estimate));
        var expiredTip=tooltips.get(icons,shown,expired.fresh() ? estimate : absent,false,0xAAAAAA,0x777777);
        yes(expiredTip!=pricedTip && expiredTip.stream().anyMatch(line -> line.getString().equals("No reliable price")),"Expired estimates cannot remain in cached hover text");
        yes(expiredTip!=tooltips.get(new ProfileItems(new JsonObject()),shown,absent,false,0xAAAAAA,0x777777),"A new catalogue/item renderer invalidates cached hover content");
        // This is an allocation comparison of list preparation, not Minecraft FPS.
        if(Boolean.getBoolean("endsight.profileBenchmark")) {
            var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long id=Thread.currentThread().threadId();
            for(int i=0;i<50;i++) { consumed=referenceGroups(values);consumed=prepared.groups(values); }
            long before=threads.getThreadAllocatedBytes(id),start=System.nanoTime();
            for(int i=0;i<200;i++) { var groups=referenceGroups(values);for(var group:groups) consumed=referenceMetrics(group.values()); }
            long oldNanos=System.nanoTime()-start,oldBytes=threads.getThreadAllocatedBytes(id)-before;
            for(var group:cached) prepared.metrics(group.values());
            before=threads.getThreadAllocatedBytes(id);start=System.nanoTime();
            for(int i=0;i<200;i++) for(var group:prepared.groups(values)) consumed=prepared.metrics(group.values());
            long newNanos=System.nanoTime()-start,newBytes=threads.getThreadAllocatedBytes(id)-before;
            System.out.println("PROFILE_PREPARATION_BENCHMARK old_ns="+oldNanos+" new_ns="+newNanos+" old_bytes="+oldBytes+" new_bytes="+newBytes+" iterations=200 rows=240");
        }
        System.out.println(checks+" profile presentation, search, ordering and formatting checks passed");
    }
}
