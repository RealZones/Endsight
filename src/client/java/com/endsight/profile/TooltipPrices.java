package com.endsight.profile;

import com.google.gson.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import com.mojang.serialization.JsonOps;
import java.util.*;
import static com.endsight.profile.ProfileData.*;

/** Delayed hovered-item requests; no network call or NBT copy on each tooltip frame. */
public final class TooltipPrices {
    private TooltipPrices() { }
    private static boolean enabled=true, inFlight;
    private static long changedAt, nextRequest, retryAt;
    private static String hovered="";
    private static Map<String,String> identities=Map.of();
    private static ProfileData.Item pending;
    private record Cached(ProfileClient.Prices prices, JsonObject value) { }
    private record Seen(CustomData component, Object profile, String name, JsonObject item) { }
    private static final Map<ItemStack,Seen> seen=new WeakHashMap<>();
    private static final Map<String,Cached> cache=new LinkedHashMap<>(128,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Cached> entry) { return size()>512; }
    };
    public static boolean enabled() { return enabled; }
    public static void enabled(boolean value) { enabled=value; }

    public static void init() {
        ProfileClient.catalog().thenAccept(catalog -> Minecraft.getInstance().execute(() -> {
            Map<String,String> names=new HashMap<>(); Set<String> ambiguous=new HashSet<>();
            object(catalog,"items").entrySet().forEach(entry -> {
                if(!entry.getValue().isJsonObject()) return;
                String name=plain(text(entry.getValue().getAsJsonObject(),"name",""));
                if(name.isBlank()) return;
                if(names.putIfAbsent(name,entry.getKey())!=null) ambiguous.add(name);
            });
            ambiguous.forEach(names::remove);
            identities=Map.copyOf(names); seen.clear();
        }));
        ItemTooltipCallback.EVENT.register((stack,context,flag,lines) -> {
            Minecraft mc=Minecraft.getInstance();
            if(!enabled || mc.level==null || stack.isEmpty() || mc.screen==null) return;
            JsonObject raw=read(stack);
            if(raw==null) { pending=null; hovered=""; return; }
            String key=raw.toString();
            if(!hovered.equals(key)) { hovered=key; changedAt=System.currentTimeMillis(); pending=null; }
            Cached found=cache.get(key);
            if(found!=null && found.prices().fresh()) {
                pending=null;
                List<Component> price=lines(found.value(),stack.getCount());
                if(price.isEmpty()) return;
                lines.add(Component.empty());
                lines.addAll(price);
                return;
            }
            pending=new ProfileData.Item("hover",0,raw);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now=System.currentTimeMillis();
            if(mc.screen==null || !enabled) { pending=null; hovered=""; return; }
            if(pending==null || inFlight || now<nextRequest || now<retryAt || now-changedAt<350) return;
            ProfileData.Item item=pending; pending=null; inFlight=true; nextRequest=now+750;
            String key=item.raw().toString();
            ProfileClient.prices(List.of(item)).whenComplete((result,error) -> mc.execute(() -> {
                inFlight=false;
                if(error!=null) { retryAt=System.currentTimeMillis()+30_000; return; }
                cache.put(key,new Cached(result,result.values().getOrDefault("hover:0",new JsonObject())));
            }));
        });
    }

    /** Tooltip lines for one answer from the price service: what the item is worth and where
     *  that number came from. "From craft cost" is a different claim from "from 20 matching
     *  sales", so the source is always shown. Empty when there is no price. An answer from
     *  the earlier price server, which had no "value", still reads through its median and
     *  craft fields. */
    public static List<Component> lines(JsonObject value,int count) {
        List<Component> out=new ArrayList<>();
        double unit=number(value,"value",Double.NaN);
        if(Double.isFinite(unit)) {
            out.add(Component.literal("Value: "+ProfileFormat.compact(unit)+" coins").withColor(0xB5A6FF));
            String method=text(value,"method","");
            if(!method.isEmpty()) out.add(Component.literal("From "+method).withColor(0x9699A7));
            // A skin, dye or pet skin on the item is part of its value; say which and how much.
            for(JsonElement applied:array(value,"applied")) if(applied.isJsonObject()) {
                JsonObject a=applied.getAsJsonObject(); double v=number(a,"value",Double.NaN);
                out.add(Component.literal("Includes "+text(a,"name","applied skin")+(Double.isFinite(v) ? " · "+ProfileFormat.compact(v) : " · no price")).withColor(0x9699A7));
            }
            if(bool(value,"cosmetic")) out.add(Component.literal("Skin or dye · not in networth").withColor(0x9699A7));
            if(count>1) out.add(Component.literal("Stack: "+ProfileFormat.compact(unit*count)+" coins").withColor(0x9699A7));
            return out;
        }
        double median=number(object(value,"matching_variant"),"median",Double.NaN);
        double craft=number(object(value,"craft_cost"),"value",Double.NaN);
        double base=number(object(object(value,"craft_cost"),"base"),"value",Double.NaN);
        if(Double.isFinite(median)) out.add(Component.literal("Median: "+ProfileFormat.compact(median)+" coins").withColor(0xB5A6FF));
        if(Double.isFinite(craft)) out.add(Component.literal("Craft: "+ProfileFormat.compact(craft)+" coins").withColor(0x71CBCD));
        else if(Double.isFinite(base)) {
            out.add(Component.literal("Base craft: "+ProfileFormat.compact(base)+" coins").withColor(0x71CBCD));
            out.add(Component.literal("Upgrades unpriced").withColor(0x9699A7));
        }
        return out;
    }

    static JsonObject read(ItemStack stack) {
        CustomData custom=stack.get(DataComponents.CUSTOM_DATA);
        if(custom==null) return null;
        String name=plain(stack.getHoverName().getString());
        Seen old=seen.get(stack);
        Object skinComponent=stack.get(DataComponents.PROFILE);
        if(old!=null && old.component()==custom && old.profile()==skinComponent && old.name().equals(name)) return old.item();
        JsonElement converted=NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE,custom.copyTag());
        if(!converted.isJsonObject()) return null;
        JsonObject raw=converted.getAsJsonObject();
        String entry=text(raw,"entry",text(raw,"item_id",""));
        // DragSim omits its entry ID from in-game NBT. Only an exact, unique
        // catalog match of the server's base item_name can restore it. The rendered
        // hover name includes reforges and is deliberately never used to guess.
        if(entry.isEmpty()) entry=identities.getOrDefault(plain(text(object(raw,"item_data"),"item_name","")),"");
        if(!entry.matches("[a-z0-9_]{1,100}") || !raw.has("item_data")) { seen.put(stack,new Seen(custom,skinComponent,name,null)); return null; }
        raw.addProperty("entry",entry); raw.addProperty("count",1);
        raw.addProperty("material",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        // Unrelated server bookkeeping is not a pricing modifier. Keep only the
        // documented item payload fields; unknown nested modifiers stay intact.
        Set<String> fields=Set.of("entry","count","material","item_data","item_stats","item_modifiers","extra","abilities","item_skin");
        raw.keySet().removeIf(k -> !fields.contains(k));
        // NBT encodes booleans as bytes; restore documented flags before matching
        // them with the website's JSON sale records. Numeric counters stay numeric.
        JsonObject mods=object(raw,"item_modifiers");
        for(String flag:List.of("art_of_war","recombobulated","enrichment")) flag(mods,flag);
        flag(object(raw,"item_data"),"skull");
        JsonObject extra=object(raw,"extra");
        flag(object(extra,"pet_data"),"active");
        flag(object(extra,"coating"),"coated");
        flag(object(extra,"drill"),"coated");
        var profile=stack.get(DataComponents.PROFILE);
        if(profile!=null) {
            var textures=profile.partialProfile().properties().get("textures");
            if(!textures.isEmpty()) {
                JsonObject skin=new JsonObject(); skin.addProperty("value",textures.iterator().next().value());
                raw.add("item_skin",skin);
            }
        }
        seen.put(stack,new Seen(custom,skinComponent,name,raw));
        return raw;
    }
    private static void flag(JsonObject data,String key) {
        JsonElement value=data.get(key);
        if(value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
            data.addProperty(key,value.getAsInt()!=0);
    }
}
