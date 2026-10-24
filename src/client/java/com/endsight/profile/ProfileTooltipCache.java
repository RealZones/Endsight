package com.endsight.profile;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Cache content only; Minecraft still owns tooltip placement, font layout and rendering. */
public final class ProfileTooltipCache {
    private ProfileData.Item item;
    private ProfileItems source;
    private JsonObject estimate;
    private boolean catalog;
    private int muted,dim;
    private List<Component> lines=List.of();

    public List<Component> get(ProfileItems source,ProfileData.Item item,JsonObject estimate,boolean catalog,int muted,int dim) {
        if(this.item==item && this.source==source && this.estimate==estimate && this.catalog==catalog && this.muted==muted && this.dim==dim) return lines;
        List<Component> built=new ArrayList<>();built.add(source.nameLine(item));built.addAll(source.lore(item));
        if(catalog) built.add(Component.literal("Not owned · Catalog entry").withColor(muted & 0xFFFFFF));
        else {
            List<Component> priced=TooltipPrices.lines(estimate,item.count());built.add(Component.empty());
            if(priced.isEmpty()) built.add(Component.literal("No reliable price").withColor(dim & 0xFFFFFF));
            else built.addAll(priced);
        }
        built.add(Component.literal("Click to inspect").withColor(muted & 0xFFFFFF));
        this.item=item;this.source=source;this.estimate=estimate;this.catalog=catalog;this.muted=muted;this.dim=dim;
        lines=List.copyOf(built);return lines;
    }
}
