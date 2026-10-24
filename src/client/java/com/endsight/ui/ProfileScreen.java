package com.endsight.ui;

import com.endsight.profile.*;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import java.util.*;
import java.util.function.Function;
import static com.endsight.profile.ProfileData.*;
import static com.endsight.profile.ProfileFormat.*;

/** A profile dossier built for DragSim's data, with native items and explicit gaps. */
public final class ProfileScreen extends Screen {
    private enum View { OVERVIEW, ITEMS, PETS, PROGRESSION, BESTIARY }
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx>=x && mx<x+w && my>=y && my<y+h; }
    }
    private final Screen parent;
    private String requested, selectedProfile="", search="", filter="";
    private boolean searchFocused, filterFocused, loading=true, pricesLoading, profileMenu;
    private String error="", priceError="";
    private int generation, scroll, contentHeight, page;
    private View view=View.OVERVIEW;
    private String containerType="inventory";
    private String progressionSection="mining";
    private ProfileData data;
    private JsonObject catalog=new JsonObject();
    private JsonObject boards=new JsonObject();
    private ProfileItems items=new ProfileItems(catalog);
    private ProfileClient.Prices prices;
    private ProfileCollection collection;
    private ProfileViewData prepared;
    private final JsonObject noPrice=new JsonObject();
    private final ProfileTooltipCache tooltipCache=new ProfileTooltipCache();
    private ProfileData.Item pinned;
    private double knownItems;
    private int pricedCount, missingCount;
    private final List<Hit> hits=new ArrayList<>();
    private final Map<String,ItemStack> symbols=new HashMap<>();
    private int px,py,pw,ph,cx,cw,top,bottom,mx,my;
    private long nextRefresh;
    private long nextPrices;

    public ProfileScreen(Screen parent, String player) {
        super(Component.literal("Player profile"));
        this.parent=parent; requested=player; search=player;
    }

    @Override protected void init() { if (data==null && generation==0) load(false); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { generation++; Minecraft.getInstance().setScreen(parent); }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float delta) { }

    private void load(boolean force) {
        int request=++generation;
        loading=true; error=""; prices=null; pricesLoading=false; pinned=null; scroll=0; profileMenu=false;
        ProfileClient.catalog().whenComplete((value, problem) -> Minecraft.getInstance().execute(() -> {
            if (request!=generation || problem!=null) return;
            catalog=value; items=new ProfileItems(catalog);
        }));
        ProfileClient.boards().whenComplete((value, problem) -> Minecraft.getInstance().execute(() -> {
            if (request==generation && problem==null) boards=value;
        }));
        ProfileClient.profile(requested,selectedProfile,force).whenComplete((value,problem) -> Minecraft.getInstance().execute(() -> {
            if (request!=generation) return;
            loading=false;
            if (problem!=null) { data=null; error=ProfileClient.error(problem); return; }
            data=value; requested=data.player; search=data.player; selectedProfile=data.profileId;
            items=new ProfileItems(catalog); knownItems=0; pricedCount=0; missingCount=data.assets.size();
            if (data.hidden || data.assets.isEmpty()) return;
            refreshPrices(request);
        }));
    }

    private void refreshPrices(int request) {
        pricesLoading=true; priceError=""; nextPrices=System.currentTimeMillis()+30_000;
        ProfileClient.prices(data.assets).whenComplete((result,problem) -> Minecraft.getInstance().execute(() -> {
            if(request!=generation) return;
            pricesLoading=false;
            if(problem!=null) { priceError="Item prices unavailable"; return; }
            prices=result; nextPrices=System.currentTimeMillis()+90_000; summarize();
        }));
    }

    @Override public void tick() {
        if(!loading && data!=null && !data.hidden && !data.assets.isEmpty() && !pricesLoading && System.currentTimeMillis()>=nextPrices)
            refreshPrices(generation);
    }

    private void summarize() {
        knownItems=0; pricedCount=0; missingCount=0;
        if (data==null) return;
        for (ProfileData.Item item:data.assets) {
            JsonObject value=price(item);
            double unit=number(value,"suggested_networth_unit_value",Double.NaN);
            if (Double.isFinite(unit) && unit>=0) { knownItems+=unit*item.count(); pricedCount++; }
            else if (bool(value,"cosmetic")) {
                // Loose skins and dyes count only with the setting on; off, they are left out
                // on purpose, so they are not a gap either way.
                double cosmetic=number(value,"value",Double.NaN);
                if (ProfileClient.countCosmetics() && Double.isFinite(cosmetic)) { knownItems+=cosmetic*item.count(); pricedCount++; }
            }
            else missingCount++;
        }
    }

    private JsonObject price(ProfileData.Item item) {
        if(prices!=null && prices.fresh() && data!=null && item.location().equals("wardrobe/"+integer(object(data.profile,"wardrobe"),"equipped",-1)) && prices.values().containsKey("inventory:"+(41+item.slot())))
            return prices.values().getOrDefault("inventory:"+(41+item.slot()),noPrice);
        return prices!=null && prices.fresh() ? prices.values().getOrDefault(ProfileItems.key(item),noPrice) : noPrice;
    }
    private ProfileViewData prepared() {
        if(prepared==null || prepared.data!=data || prepared.catalog!=catalog) prepared=new ProfileViewData(data,catalog);
        return prepared;
    }
    private ProfileCollection collection() {
        if(collection==null || collection.data!=data || collection.catalog!=catalog) collection=new ProfileCollection(data,catalog);
        return collection;
    }
    private void choose(View next) { view=next; pinned=null; scroll=0; filter=""; filterFocused=false; profileMenu=false; }

    private void geometry() {
        pw=Math.min(850,Math.max(280,width-40)); ph=Math.min(500,Math.max(154,height-66));
        px=(width-pw)/2; py=(height-ph)/2+10;
        // No footer: the save age and Esc hint it held were noise, and the 14 rows go to content.
        cx=px+16; cw=pw-32; top=py+62; bottom=py+ph-10;
        if(compactInspector()) {
            // At large GUI scales the profile chrome would leave one visible lore
            // line. Inspection gets the canvas; Back restores the profile layout.
            pw=width-24; ph=height-24; px=12; py=12;
            cx=px+12; cw=pw-24; top=py+10; bottom=py+ph-12;
        }
        scroll=Math.clamp(scroll,0,Math.max(0,contentHeight-(bottom-top)));
    }
    private boolean compactInspector() { return pinned!=null && height<330; }

    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
        super.extractRenderState(g,mouseX,mouseY,delta);
        draw(g,mouseX,mouseY);
    }

    private void draw(GuiGraphicsExtractor g,int mouseX,int mouseY) {
        geometry(); mx=mouseX; my=mouseY; hits.clear();
        if (prices!=null && !prices.fresh()) { prices=null; summarize(); priceError="Item prices are updating"; }
        if (g!=null) {
            Draw.rect(g,0,0,width,height,ProfileStyle.scrim());
            Draw.roundedOutline(g,px,py,pw,ph,7,ProfileStyle.line(),ProfileStyle.bg());
            if(!compactInspector()) {
                Draw.roundedRect(g,px+1,py+1,pw-2,52,6,ProfileStyle.surface(),true,true,false,false);
                Draw.rect(g,cx,py+52,cw,1,ProfileStyle.hair());
            }
        }
        if(!compactInspector()) { header(g); navigation(g); }
        if (loading) {
            empty(g,"Opening "+requested,"Reading the latest saved profile."); contentHeight=0;
        } else if (data==null) {
            empty(g,"Profile unavailable",error);
            button(g,cx+18,top+85,78,22,"Try again",false,() -> load(true),false);
            contentHeight=0;
        } else if (pinned!=null) detail(g);
        else switch(view) {
            case OVERVIEW -> overview(g);
            case ITEMS -> inventory(g);
            case PETS -> pets(g);
            case PROGRESSION -> progression(g);
            case BESTIARY -> bestiary(g);
        }
        if (g!=null && contentHeight>bottom-top) {
            int track=bottom-top, thumb=Math.max(16,track*track/contentHeight);
            int y=top+Math.round((track-thumb)*scroll/(float)Math.max(1,contentHeight-track));
            Draw.roundedRect(g,px+pw-7,top,2,track,1,ProfileStyle.hair());
            Draw.roundedRect(g,px+pw-7,y,2,thumb,1,ProfileStyle.accent());
        }
        profileChoices(g);
    }

    private void header(GuiGraphicsExtractor g) {
        boolean small=pw<540;
        int searchW=small ? 96 : 142, searchX=px+pw-searchW-123;
        if (g!=null) {
            Draw.roundedRect(g,cx,py+10,30,30,4,ProfileStyle.raised());
            if(data!=null) g.item(items.playerHead(data.uuid,data.player),cx+7,py+17);
            String identity=data==null ? requested : (text(data.profile,"profile_mode","").equalsIgnoreCase("IRONMAN") ? "♲ " : "")+data.player;
            Draw.text(g,font,Draw.fit(font,identity,Math.max(40,searchX-cx-45)),cx+40,py+12,ProfileStyle.text());
            if(data==null) Draw.text(g,font,"Player profile",cx+40,py+29,ProfileStyle.muted());
            else ProfileRank.draw(g,font,data.rank,cx+40,py+26,Math.max(40,searchX-cx-45));
            Draw.roundedOutline(g,searchX,py+10,searchW,20,3,searchFocused ? ProfileStyle.accent() : ProfileStyle.line(),ProfileStyle.input());
            MenuIcons.search(g,searchX+6,py+14,ProfileStyle.muted());
            Draw.text(g,font,Draw.fit(font,search+(searchFocused && System.currentTimeMillis()%1000<500 ? "_" : ""),searchW-26),searchX+23,py+16,ProfileStyle.text());
        }
        hits.add(new Hit(searchX,py+10,searchW,20,() -> { searchFocused=true; filterFocused=false; }));
        button(g,searchX+searchW+5,py+10,28,20,"Go",false,this::searchPlayer,false);
        button(g,px+pw-85,py+10,54,20,"Refresh",false,() -> {
            if (System.currentTimeMillis()<nextRefresh) return;
            nextRefresh=System.currentTimeMillis()+5000; load(true);
        },false);
        button(g,px+pw-26,py+10,16,20,"x",false,this::onClose,false);
        if (data!=null) {
            int profileW=Math.min(110,searchW);
            profileSelector(g,searchX,py+33,profileW,15);
            // Where the Main/Beta switch was: the same profile on the website, through the
            // game's own open-link prompt so a stray click doesn't throw a browser up.
            int siteX=searchX+profileW+5;
            String site="https://dragsim.co/stats/"+data.player+(data.profileId.isEmpty() ? "" : "/"+data.profileId);
            button(g,siteX,py+33,px+pw-10-siteX,15,"View on dragsim.co",false,
                    () -> ConfirmLinkScreen.confirmLinkNow(this,site),false);
        }
        if(g!=null && inside(mx,my,px+pw-85,py+10,54,20))
            g.setTooltipForNextFrame(font,Component.literal("Refresh profile (R)"),mx,my);
    }

    private void profileSelector(GuiGraphicsExtractor g,int x,int y,int w,int h) {
        boolean hot=inside(mx,my,x,y,w,h);
        if(g!=null) {
            Draw.roundedRect(g,x,y,w,h,3,profileMenu ? ProfileStyle.raised() : hot ? ProfileStyle.hover() : ProfileStyle.input());
            Draw.text(g,font,Draw.fit(font,data.profileName,w-28),x+8,y+(h-font.lineHeight)/2,profileMenu || hot ? ProfileStyle.text() : ProfileStyle.muted());
            int center=x+w-11,cy=y+h/2-1,color=profileMenu || hot ? ProfileStyle.accent() : ProfileStyle.muted();
            for(int i=0;i<3;i++) {
                int yy=cy+(profileMenu ? 2-i : i);
                Draw.rect(g,center-3+i,yy,2,1,color);
                Draw.rect(g,center+2-i,yy,2,1,color);
            }
        }
        hits.add(new Hit(x,y,w,h,() -> { profileMenu=!profileMenu; searchFocused=false; filterFocused=false; }));
    }

    private void profileChoices(GuiGraphicsExtractor g) {
        if(!profileMenu || data==null) return;
        int w=Math.min(198,cw),x=px+pw-w-10,y=py+53,h=data.profiles.size()*25+8;
        if(g!=null) {
            g.nextStratum();
            Draw.roundedOutline(g,x,y,w,h,4,ProfileStyle.line(),ProfileStyle.raised());
        }
        int i=0;
        for(ProfileData.Choice choice:data.profiles) {
            int yy=y+4+i++*25;
            String label=(choice.mode().equalsIgnoreCase("IRONMAN") ? "♲ " : "")+choice.name()+(choice.mode().equals("NORMAL") ? "" : " · "+title(choice.mode()));
            button(g,x+4,yy,w-8,23,label,choice.id().equals(selectedProfile),() -> {
                selectedProfile=choice.id(); load(false);
            },false);
        }
    }

    private void navigation(GuiGraphicsExtractor g) {
        int w=Math.min(118,(pw-16)/5),y=py-29,x=px+8;
        String[] labels={"Overview","Items","Pets","Progression","Bestiary"};
        String[] icons={"compass","chest","bone","experience_bottle","writable_book"};
        for (View next:View.values()) {
            int nx=x+next.ordinal()*w;
            boolean selected=view==next;
            if (g!=null) {
                Draw.roundedRect(g,nx,y,w-3,27,4,selected ? ProfileStyle.surface() : inside(mx,my,nx,y,w-3,27) ? ProfileStyle.hover() : ProfileStyle.input());
                g.item(symbol(icons[next.ordinal()]),nx+6,y+5);
                String label=w<100 && next==View.PROGRESSION ? "Progress" : labels[next.ordinal()];
                if(w>=80) Draw.text(g,font,Draw.fit(font,label,w-32),nx+27,y+10,selected ? ProfileStyle.text() : ProfileStyle.muted());
                else if(inside(mx,my,nx,y,w-3,27)) g.setTooltipForNextFrame(font,Component.literal(labels[next.ordinal()]),mx,my);
                if (selected) Draw.rect(g,nx+5,y+25,w-13,2,ProfileStyle.accent());
            }
            hits.add(new Hit(nx,y,w-3,27,() -> choose(next)));
        }
    }

    private ItemStack symbol(String id) {
        if(Minecraft.getInstance().level==null) return ItemStack.EMPTY;
        return symbols.computeIfAbsent(id,key -> {
            var value=BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("minecraft",key));
            return new ItemStack(value==null || value==Items.AIR ? Items.PAPER : value);
        });
    }

    private void emblem(GuiGraphicsExtractor g,String id,int x,int y) {
        if(g!=null && y>=top && y+16<=bottom) g.item(symbol(id),x,y);
    }

    private int y(int offset) { return top+offset-scroll; }
    private void heading(GuiGraphicsExtractor g,String heading,String subtitle) {
        text(g,heading,cx,y(3),ProfileStyle.text(),cw);
        text(g,subtitle,cx,y(20),ProfileStyle.muted(),cw);
    }

    private void overview(GuiGraphicsExtractor g) {
        text(g,"PROFILE OVERVIEW",cx,y(1),ProfileStyle.dim(),cw);
        int gap=10, first=(cw-gap)*3/5,rest=cw-first-gap,yy=y(20);
        card(g,cx,yy,first,64,ProfileStyle.surface());
        emblem(g,"gold_ingot",cx+10,yy+10);
        int worthBadge=badge(g,"networth","networth",-1,cx+first-10,yy+13);
        text(g,"Known net worth",cx+32,yy+13,ProfileStyle.muted(),first-44-worthBadge);
        double coins=number(data.profile,"coins",Double.NaN);
        String value=data.hidden ? "Private" : compact(coins+(prices!=null ? knownItems : 0));
        strong(g,value,cx+12,yy+31,ProfileStyle.obs(),first-24);
        text(g,data.hidden ? "Inventory value is hidden" : pricesLoading ? "Pricing saved items..." : prices==null ? "Item prices unavailable" :
                pricedCount+" priced · "+missingCount+" unpriced stacks"+(ProfileClient.countCosmetics() ? " · skins and dyes counted" : ""),cx+12,yy+49,ProfileStyle.dim(),first-24);
        int rx=cx+first+gap;
        card(g,rx,yy,rest,64,ProfileStyle.surface());
        text(g,"Purse",rx+10,yy+11,ProfileStyle.muted(),rest-20);
        String purse=compact(coins);
        right(g,purse,rx+rest-10,yy+11,ProfileStyle.obs());
        badge(g,"balance","coins",coins,rx+rest-10-font.width(purse)-6,yy+11);
        text(g,"Dragon essence",rx+10,yy+29,ProfileStyle.muted(),rest-20);
        right(g,compact(number(data.profile,"dragon_essence",Double.NaN)),rx+rest-10,yy+29,ProfileStyle.text());
        text(g,"Bits",rx+10,yy+47,ProfileStyle.muted(),rest-20);
        right(g,compact(number(data.profile,"bits",0)),rx+rest-10,yy+47,ProfileStyle.text());
        // A single-column layout preserves native slot sizes at larger GUI scales.
        boolean narrow=cw<500;
        int left=narrow ? cw : (cw-gap)/2,right=narrow ? cw : cw-left-gap;
        int offset=96,gearHeight=130;
        yy=y(offset); card(g,cx,yy,left,gearHeight,ProfileStyle.surface());
        emblem(g,"iron_chestplate",cx+10,yy+8);
        text(g,"Equipped gear",cx+32,yy+12,ProfileStyle.text(),left-110);
        button(g,cx+left-65,yy+7,55,20,"Browse >",false,() -> { choose(View.ITEMS); containerType="inventory"; },true);
        if(data.hidden) text(g,"Inventory private",cx+12,yy+51,ProfileStyle.muted(),left-24);
        else {
            ProfileData.Container inventory=data.container("inventory");
            text(g,"Armor",cx+12,yy+34,ProfileStyle.dim(),left-24);
            for(int i=0;i<4;i++) slot(g,inventory==null ? null : inventory.slots().get(41+i),cx+12+i*27,yy+48,24);
            text(g,"Hotbar",cx+12,yy+82,ProfileStyle.dim(),left-24);
            int cell=Math.min(27,(left-22)/9);
            for(int i=0;i<9;i++) slot(g,inventory==null ? null : inventory.slots().get(i),cx+12+i*cell,yy+96,cell-2);
        }
        int sx=narrow ? cx : cx+left+gap,sy=narrow ? y(offset+gearHeight+10) : yy;
        card(g,sx,sy,right,gearHeight,ProfileStyle.surface());
        text(g,"Skills",sx+12,sy+12,ProfileStyle.text(),right-24);
        int row=sy+32,i=0;
        String[] skillIcons={"diamond_sword","diamond_pickaxe","bone","skeleton_skull"};
        for (String skill:List.of("combat","mining","taming","catacombs")) {
            ProfileData.Level level=prepared().skillLevel(skill);
            emblem(g,skillIcons[i++],sx+10,row-2);
            text(g,title(skill),sx+33,row,ProfileStyle.muted(),right-84);
            String lv=level.level()<0 ? "—" : "Lv "+level.level();
            right(g,lv,sx+right-12,row,ProfileStyle.text());
            if(skill.equals("mining")) badge(g,"mining_xp","Mining",number(object(data.profile,"skills"),"mining",-1),sx+right-12-font.width(lv)-6,row);
            bar(g,sx+33,row+13,right-45,level.progress(),ProfileStyle.accent()); row+=23;
        }
        offset+=gearHeight+12+(narrow ? gearHeight+10 : 0);
        long dragons=prepared().dragons;
        int cols=2,w=(cw-gap)/2;
        JsonObject slayer=object(data.profile,"slayer");
        String[][] cards={{"dragon_egg","Dragon progress","Heart tier "+integer(object(data.profile,"hotd"),"tier",0),compact(dragons)+" dragons defeated"},
                {"rotten_flesh","Slayer","Revenant "+integer(slayer,"zombie_level",0),"Voidgloom "+integer(slayer,"enderman_level",0)}};
        for(i=0;i<cards.length;i++) {
            int x=cx+i%cols*(w+gap); yy=y(offset+i/cols*77);
            card(g,x,yy,w,67,ProfileStyle.surface()); emblem(g,cards[i][0],x+9,yy+8);
            text(g,cards[i][1],x+30,yy+12,ProfileStyle.text(),w-39);
            text(g,cards[i][2],x+11,yy+34,ProfileStyle.muted(),w-22);
            text(g,cards[i][3],x+11,yy+49,ProfileStyle.dim(),w-22);
            if(i==1) {
                badge(g,"slayer_zombie_xp","Revenant Slayer",number(slayer,"zombie_xp",-1),x+w-11,yy+34);
                badge(g,"slayer_enderman_xp","Enderman Slayer",number(slayer,"enderman_xp",-1),x+w-11,yy+49);
            }
        }
        contentHeight=offset+(cards.length+cols-1)/cols*77;
    }

    private List<ProfileData.Container> pages() {
        return prepared().pages(containerType);
    }

    private void inventory(GuiGraphicsExtractor g) {
        heading(g,"Items",data.hidden ? "Inventory access is private." : "Browse saved items. Click one to inspect it.");
        if (data.hidden) { privacy(g); return; }
        int bx=cx,by=y(40),tabWidth=cw/5;
        String[][] tabs={{"inventory","Inventory","chest"},{"ender_chest","Ender","ender_chest"},{"storage","Storage","barrel"},{"accessories","Accessories","nether_star"},{"wardrobe","Wardrobe","leather_chestplate"}};
        for (String[] tab:tabs) {
            itemButton(g,bx,by,tabWidth-4,26,tab[2],tab[1],containerType.equals(tab[0]),() -> { containerType=tab[0]; page=0; scroll=0; pinned=null; filter=""; });
            bx+=tabWidth;
        }
        int offset=by-top+scroll+37;
        int searchY=y(offset),searchWidth=Math.max(80,cw-85);
        if(g!=null && searchY>=top && searchY+22<=bottom) {
            Draw.roundedOutline(g,cx,searchY,searchWidth,22,5,filterFocused ? ProfileStyle.accent() : ProfileStyle.line(),ProfileStyle.input());
            MenuIcons.search(g,cx+7,searchY+5,ProfileStyle.dim());
            Draw.text(g,font,Draw.fit(font,filter.isEmpty() ? "Search this section..." : filter+(filterFocused ? "_" : ""),searchWidth-32),cx+25,searchY+7,filter.isEmpty() ? ProfileStyle.dim() : ProfileStyle.text());
        }
        if(searchY>=top && searchY+22<=bottom) hits.add(new Hit(cx,searchY,searchWidth,22,() -> { filterFocused=true; searchFocused=false; }));
        offset+=32;
        if(containerType.equals("wardrobe")) { wardrobe(g,offset); return; }
        if(containerType.equals("accessories")) { accessories(g,offset); return; }
        if(!filter.isBlank()) {
            List<ProfileData.Item> matches=prepared().search(containerType,filter);
            text(g,matches.size()+" matching stacks",cx,y(offset),ProfileStyle.muted(),cw); offset+=22;
            int cell=Math.clamp(cw/9,20,30),cols=Math.max(1,cw/cell);
            for(int i=0;i<matches.size();i++) slot(g,matches.get(i),cx+(i%cols)*cell,y(offset+i/cols*cell),cell-3);
            contentHeight=offset+(matches.size()+cols-1)/cols*cell+20; return;
        }
        List<ProfileData.Container> pages=pages();
        if (pages.isEmpty()) { text(g,"No saved items in this section.",cx,y(offset+20),ProfileStyle.muted(),cw); contentHeight=offset+60; return; }
        if (!containerType.equals("inventory")) { pageGrid(g,pages,offset); return; }
        ProfileData.Container container=pages.get(0);
        // Armor stands left of the grid, as in the game's own inventory, instead of in a row
        // underneath: the grid is four rows tall, so the armor column costs no height at all.
        int cell=Math.clamp((cw-34)/10,16,30),gap=12,blockW=cell+gap+cell*9;
        int armorX=cx+(cw-blockW)/2,gridX=armorX+cell+gap;
        for (int i=0;i<4;i++) slot(g,container.slots().get(41+i),armorX,y(offset+i*cell+(i==3 ? 5 : 0)),cell-2);
        for (int row=0;row<4;row++) for (int col=0;col<9;col++) {
            int index=row==3 ? col : 9+row*9+col;
            slot(g,container.slots().get(index),gridX+col*cell,y(offset+row*cell+(row==3 ? 5 : 0)),cell-2);
        }
        offset+=4*cell+15;
        // Preserve unusual inventory slots rather than silently losing data.
        List<ProfileData.Item> extra=container.slots().values().stream().filter(it -> it.slot()>=36 && (it.slot()<41 || it.slot()>44)).toList();
        for (int i=0;i<extra.size();i++) slot(g,extra.get(i),gridX+i*cell,y(offset),cell-2);
        if (!extra.isEmpty()) offset+=cell+10;
        contentHeight=offset+6;
    }

    /** Storage and the ender chest used to show one 9-wide page alone in an 800-wide frame,
     *  mostly blank, with a pager to reach the other 26. Every page with something in it now
     *  shows at once as its own small grid, as many across as fit, and scrolling replaces
     *  the pager. Empty pages fold into one line instead of costing a grid of blank slots. */
    private void pageGrid(GuiGraphicsExtractor g,List<ProfileData.Container> pages,int offset) {
        List<ProfileData.Container> used=prepared().usedPages(containerType);
        if (used.isEmpty()) { text(g,"Nothing saved here.",cx,y(offset+6),ProfileStyle.muted(),cw); contentHeight=offset+40; return; }
        int cell=used.size()==1 ? Math.clamp((cw-22)/9,16,30) : 24,gap=14,pageW=9*cell;
        int cols=Math.max(1,Math.min(used.size(),(cw+gap)/(pageW+gap)));
        int gridX=cx+(cw-(cols*pageW+(cols-1)*gap))/2,rowY=offset;
        for (int i=0;i<used.size();i+=cols) {
            int rowH=0;
            for (int j=i;j<Math.min(used.size(),i+cols);j++) {
                ProfileData.Container c=used.get(j); int x=gridX+(j-i)*(pageW+gap),rows=(c.capacity()+8)/9;
                int pageHeight=(used.size()>1 ? 13 : 0)+rows*cell;
                rowH=Math.max(rowH,pageHeight);
                if(y(rowY)+pageHeight<=top || y(rowY)>=bottom) continue;
                if (used.size()>1) text(g,c.name(),x+1,y(rowY),ProfileStyle.muted(),pageW);
                int gy=rowY+(used.size()>1 ? 13 : 0);
                for (int s=0;s<c.capacity();s++) slot(g,c.slots().get(s),x+(s%9)*cell,y(gy+(s/9)*cell),cell-2);
                rowH=Math.max(rowH,gy-rowY+rows*cell);
            }
            rowY+=rowH+12;
        }
        int empty=pages.size()-used.size();
        if (empty>0) { text(g,empty+" empty page"+(empty==1 ? "" : "s")+" not shown",cx,y(rowY),ProfileStyle.dim(),cw); rowY+=18; }
        contentHeight=rowY+4;
    }

    private void pets(GuiGraphicsExtractor g) {
        heading(g,"Pets",data.hidden ? "Pets are private." : "Active first, then alphabetical. Hover or click for details.");
        if (data.hidden) { privacy(g); return; }
        List<ProfileData.Item> pets=collection().pets;
        int columns=Math.max(1,cw/155),w=(cw-(columns-1)*8)/columns;
        text(g,"Owned · "+pets.size(),cx,y(42),ProfileStyle.muted(),cw);
        for(int i=0;i<pets.size();i++) {
            ProfileData.Item pet=pets.get(i); int x=cx+(i%columns)*(w+8),yy=y(62+i/columns*45);
            if(yy+39<=top || yy>=bottom) continue;
            card(g,x,yy,w,39,ProfileStyle.input()); slot(g,pet,x+6,yy+7,24);
            text(g,pet.name().replace("[Lvl 100] ",""),x+37,yy+8,ProfileItems.color(pet.rarity()),w-43);
            JsonObject extra=object(object(pet.raw(),"extra"),"pet_data");
            text(g,"Lv 100"+(bool(extra,"active") ? " · Active" : ""),x+37,yy+24,ProfileStyle.muted(),w-43);
        }
        int offset=62+(pets.size()+columns-1)/columns*45+16;
        text(g,"Missing species · "+collection().missingPets.size(),cx,y(offset),ProfileStyle.muted(),cw); offset+=21;
        contentHeight=suggestionGrid(g,collection().missingPets,offset)+14;
    }

    private void wardrobe(GuiGraphicsExtractor g,int offset) {
        List<ProfileData.Container> sets=pages().stream().filter(c -> !c.slots().isEmpty())
                .filter(c -> filter.isBlank() || c.slots().values().stream().anyMatch(it -> it.name().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))).toList();
        int equipped=integer(object(data.profile,"wardrobe"),"equipped",-1);
        text(g,"Wardrobe · "+sets.size()+" sets"+(equipped>=0 ? " · Set "+(equipped+1)+" equipped" : ""),cx,y(offset),ProfileStyle.text(),cw); offset+=20;
        // Sets were narrow columns of bare icons in the middle of the frame. Each set is now a
        // card with its pieces named, up to four across, so a set reads without hovering every
        // icon and seven sets fit in two rows.
        int gap=10,cols=Math.max(1,Math.min(4,Math.min(sets.size(),(cw+gap)/(170+gap)))),w=(cw-(cols-1)*gap)/cols,cardH=4*24+28;
        for(int i=0;i<sets.size();i++) {
            var set=sets.get(i); int x=cx+i%cols*(w+gap),yy=y(offset+i/cols*(cardH+gap));
            if(yy+cardH<=top || yy>=bottom) continue;
            boolean active=set.id().equals("wardrobe/"+equipped);
            card(g,x,yy,w,cardH,active ? Draw.lerp(ProfileStyle.surface(),ProfileStyle.accent(),.10f) : ProfileStyle.surface());
            text(g,set.name().replace("Wardrobe ","Set ")+(active ? "  ·  Equipped" : ""),x+8,yy+8,active ? ProfileStyle.accent() : ProfileStyle.muted(),w-16);
            for(int piece=0;piece<4;piece++) {
                ProfileData.Item item=set.slots().get(piece); int pieceY=yy+24+piece*24;
                slot(g,item,x+6,pieceY,22);
                text(g,item==null ? "Empty" : item.name(),x+34,pieceY+7,item==null ? ProfileStyle.dim() : ProfileItems.color(item.rarity()),w-42);
            }
        }
        contentHeight=offset+(sets.size()+cols-1)/cols*(cardH+gap)+4;
    }

    private List<ProfileData.Item> filtered(List<ProfileData.Item> source) {
        return prepared().filter(source,filter,false);
    }
    private void accessories(GuiGraphicsExtractor g,int offset) {
        List<ProfileData.Item> owned=filtered(collection().accessories),missing=filtered(collection().missingAccessories);
        text(g,"Owned · "+owned.size()+" unique accessories",cx,y(offset),ProfileStyle.text(),cw); offset+=21;
        int cols=Math.max(1,cw/29);
        for(int i=0;i<owned.size();i++) slot(g,owned.get(i),cx+i%cols*29,y(offset+i/cols*29),26);
        offset+=(owned.size()+cols-1)/cols*29+19;
        text(g,"Missing / upgrades · "+missing.size(),cx,y(offset),ProfileStyle.text(),cw); offset+=17;
        text(g,"Highest catalog tier per family; includes accessories in storage.",cx,y(offset),ProfileStyle.dim(),cw); offset+=21;
        contentHeight=suggestionGrid(g,missing,offset)+14;
    }
    private int suggestionGrid(GuiGraphicsExtractor g,List<ProfileData.Item> source,int offset) {
        int cols=Math.max(1,cw/154),w=(cw-(cols-1)*8)/cols;
        for(int i=0;i<source.size();i++) {
            int x=cx+i%cols*(w+8),yy=y(offset+i/cols*36);
            if(yy+26<=top || yy>=bottom) continue;
            slot(g,source.get(i),x,yy,26);
            text(g,source.get(i).name(),x+33,yy+3,ProfileStyle.muted(),w-36);
            text(g,"Not owned",x+33,yy+17,ProfileStyle.dim(),w-36);
        }
        return offset+(source.size()+cols-1)/cols*36;
    }

    private void progression(GuiGraphicsExtractor g) {
        heading(g,"Progression","The parts of Dragsim that shape this profile.");
        String[][] sections={{"mining","Mining","diamond_pickaxe"},{"skills","Skills & slayer","diamond_sword"},{"dungeons","Dungeons","skeleton_skull"},{"forge","Forge","anvil"}};
        int tabW=cw/4;
        for(int i=0;i<sections.length;i++) {
            String[] section=sections[i];
            itemButton(g,cx+i*tabW,y(40),tabW-4,26,section[2],section[1],progressionSection.equals(section[0]),() -> { progressionSection=section[0];scroll=0; });
        }
        int offset=80;
        if(progressionSection.equals("mining")) { contentHeight=miningTree(g,offset); return; }
        if(progressionSection.equals("skills")) {
        text(g,"Skills",cx,y(offset),ProfileStyle.text(),cw); offset+=23;
        int cols=cw>=330 ? 2 : 1,sw=(cw-10*(cols-1))/cols;
        List<String> skillNames=List.of("combat","mining","taming","catacombs");
        for(int i=0;i<skillNames.size();i++) {
            String skill=skillNames.get(i); double xp=number(object(data.profile,"skills"),skill,0);
            ProfileData.Level level=prepared().skillLevel(skill);
            int sx=cx+(i%cols)*(sw+10),sy=y(offset+i/cols*65);
            card(g,sx,sy,sw,55,ProfileStyle.surface());
            emblem(g,new String[]{"diamond_sword","diamond_pickaxe","bone","skeleton_skull"}[i],sx+9,sy+8);
            text(g,title(skill),sx+32,sy+12,ProfileStyle.text(),sw-89);
            String lv=level.level()<0 ? "—" : "Lv "+level.level();
            right(g,lv,sx+sw-10,sy+10,ProfileStyle.accent());
            if(skill.equals("mining")) badge(g,"mining_xp","Mining",xp,sx+sw-10-font.width(lv)-6,sy+10);
            text(g,compact(xp)+" XP"+(level.maxed() ? " · Max level" : ""),sx+10,sy+28,ProfileStyle.muted(),sw-20);
            bar(g,sx+10,sy+44,sw-20,level.progress(),ProfileStyle.accent());
        }
        offset+=(skillNames.size()+cols-1)/cols*65+9;
        text(g,"Slayer",cx,y(offset),ProfileStyle.text(),cw); offset+=23;
        JsonObject slayer=object(data.profile,"slayer");
        String[][] slayers={{"Revenant","zombie"},{"Voidgloom","enderman"}};
        for(int i=0;i<slayers.length;i++) {
            int sx=cx+(i%cols)*(sw+10),sy=y(offset+i/cols*70);
            card(g,sx,sy,sw,60,ProfileStyle.surface());
            int slayerBadge=badge(g,"slayer_"+slayers[i][1]+"_xp",slayers[i][0]+" Slayer",number(slayer,slayers[i][1]+"_xp",-1),sx+sw-10,sy+10);
            text(g,slayers[i][0],sx+10,sy+10,ProfileStyle.text(),sw-20-slayerBadge);
            text(g,"Level "+integer(slayer,slayers[i][1]+"_level",0),sx+10,sy+27,ProfileStyle.accent(),sw-20);
            text(g,compact(number(slayer,slayers[i][1]+"_xp",0))+" total XP",sx+10,sy+44,ProfileStyle.muted(),sw-20);
        }
        offset+=(slayers.length+cols-1)/cols*70+9;
        contentHeight=offset; return;
        }
        if(progressionSection.equals("dungeons")) {
        JsonObject dungeon=object(data.profile,"dungeons");
        JsonObject dungeonSummary=prepared().dungeonSummary;
        offset=section(g,"Dungeons · "+title(text(dungeon,"class","Unselected")),dungeonSummary,offset,ProfileFormat::number);
        offset=section(g,"Floor completions",object(dungeon,"completions"),offset,ProfileFormat::number);
        offset=section(g,"Dungeon class XP",object(dungeon,"class_xp"),offset,ProfileFormat::compact);
        offset=section(g,"Essence",object(dungeon,"essence"),offset,ProfileFormat::compact);
        contentHeight=offset; return;
        }
        JsonArray forge=data.hidden ? new JsonArray() : array(data.profile,"forge");
        text(g,"Forge",cx,y(offset),ProfileStyle.text(),cw); offset+=21;
        if(forge.isEmpty()) { text(g,"No saved forge jobs.",cx+8,y(offset),ProfileStyle.muted(),cw-16); offset+=28; }
        for(JsonElement element:forge) if(element.isJsonObject()) {
            JsonObject job=element.getAsJsonObject(); int yy=y(offset); card(g,cx,yy,cw,36,ProfileStyle.surface());
            text(g,title(text(job,"recipe","Unknown recipe")),cx+10,yy+8,ProfileStyle.text(),cw-75);
            String state=text(job,"stage","");
            long remaining=(long)(number(job,"started",0)+number(job,"duration",0)-System.currentTimeMillis());
            String label=state.equals("READY") || remaining<=0 ? "Ready" : Math.max(1,remaining/60000)+"m";
            right(g,label,cx+cw-10,yy+8,label.equals("Ready") ? ProfileStyle.pos() : ProfileStyle.muted());
            text(g,"Slot "+(integer(job,"slot",0)+1),cx+10,yy+23,ProfileStyle.dim(),cw-20); offset+=43;
        }
        contentHeight=offset+12;
    }

    private int miningTree(GuiGraphicsExtractor g,int offset) {
        JsonObject hotd=object(data.profile,"hotd");
        int tier=integer(hotd,"tier",0),core=integer(hotd,"core",0);
        String ability=title(text(hotd,"ability","Unselected"));
        // Ten tiers under a three-line summary ran off the bottom and had to be scrolled. The
        // summary now sits in a panel beside the tree when there is room, and the row pitch
        // shrinks to the height left, so all ten tiers show at once.
        int panelW=190,gap=24,pitch=Math.clamp((bottom-top-offset-4)/10,22,30);
        boolean side=cw>=8*pitch+gap+panelW;
        int gridX;
        if(side) {
            gridX=cx+(cw-(8*pitch+gap+panelW))/2;
            int px0=gridX+8*pitch+gap,py0=y(offset);
            card(g,px0,py0,panelW,112,ProfileStyle.surface());
            text(g,"Heart of the Dragon",px0+10,py0+10,ProfileStyle.text(),panelW-20);
            text(g,"Tier "+tier+"  ·  Core "+core+"/10",px0+10,py0+24,ProfileStyle.muted(),panelW-20);
            String[][] rows={{"Ender powder",compact(number(hotd,"ender",0))},{"Void powder",compact(number(hotd,"void",0))},
                    {"Ability",ability},{"Tokens",Integer.toString(integer(hotd,"tokens",0))}};
            int[] colors={ProfileStyle.obs(),ProfileStyle.accent(),ProfileStyle.text(),ProfileStyle.text()};
            for(int i=0;i<rows.length;i++) {
                text(g,rows[i][0],px0+10,py0+46+i*15,ProfileStyle.dim(),panelW/2);
                right(g,Draw.fit(font,rows[i][1],panelW/2),px0+panelW-10,py0+46+i*15,colors[i]);
            }
        } else {
            text(g,"Heart of the Dragon · Tier "+tier,cx,y(offset),ProfileStyle.text(),cw-90);
            right(g,"Core "+core+"/10",cx+cw,y(offset),ProfileStyle.muted());
            offset+=18;
            text(g,"Ender "+compact(number(hotd,"ender",0))+"  ·  Void "+compact(number(hotd,"void",0))+"  ·  "+ability,cx,y(offset),ProfileStyle.muted(),cw);
            offset+=20;
            pitch=Math.clamp((bottom-top-offset-4)/10,22,30);
            gridX=cx+(cw-8*pitch)/2;
        }
        // A node is drawn smaller than its pitch so neighbours keep a gap. Below 28 there is
        // no room for the icon and the level under it, so the level sits over the icon's
        // corner the way an item count does.
        int node=pitch-4,treeTop=offset;
        boolean roomy=pitch>=28;
        // Tiers are a plain number beside a thin rail that fills up to the unlocked tier. They
        // were boxed tiles the same size as the nodes, which read as ten more nodes.
        int railX=gridX+node-3;
        for(int row=0;row<10;row++) {
            int nodeTier=10-row,yy=y(treeTop+row*pitch);
            boolean open=nodeTier<=tier;
            if(g!=null) {
                int vt=Math.max(top,yy-(pitch-node)),vb=Math.min(bottom,yy+node+1);
                if(vb>vt) Draw.rect(g,railX,vt,2,vb-vt,open ? Draw.alpha(ProfileStyle.pos(),.55f) : ProfileStyle.hair());
            }
            right(g,Integer.toString(nodeTier),railX-5,yy+(node-7)/2,open ? ProfileStyle.pos() : ProfileStyle.dim());
        }
        for(ProfileMining.Node n:ProfileMining.NODES) {
            int level=ProfileMining.level(n,hotd),x=gridX+n.column()*pitch,yy=y(treeTop+(10-n.tier())*pitch);
            boolean active=n.ability() && n.id().equalsIgnoreCase(text(hotd,"ability",""));
            String icon=n.id().equals("CORE_OF_THE_DRAGON") ? "nether_star" : n.ability() ? active ? "emerald_block" : "coal_block" : level>=n.maximum() ? "diamond" : level>0 ? "emerald" : "coal";
            card(g,x,yy,node,node+1,active ? Draw.lerp(ProfileStyle.input(),ProfileStyle.accent(),.2f) : ProfileStyle.input());
            String label=Integer.toString(level);
            if(roomy) {
                emblem(g,icon,x+(node-16)/2,yy+1);
                text(g,label,x+(node-font.width(label))/2,yy+node-8,level>0 ? ProfileStyle.text() : ProfileStyle.dim(),node);
            } else {
                emblem(g,icon,x+(node-16)/2,yy+(node+1-16)/2);
                if(level>0) right(g,label,x+node-1,yy+node-7,ProfileStyle.text());
            }
            if(g!=null && yy>=top && yy+node+1<=bottom && inside(mx,my,x,yy,node,node+1)) {
                List<Component> tip=new ArrayList<>();
                tip.add(Component.literal(n.name()).withColor(active ? 0x55FFFF : 0x55FF55));
                tip.add(Component.literal("Level "+level+"/"+n.maximum()).withColor(0xAAAAAA));
                tip.add(Component.literal("Heart of the Dragon tier "+n.tier()).withColor(0x777777));
                if(n.ability()) tip.add(Component.literal(active ? "Selected pickaxe ability" : "Pickaxe ability").withColor(0xFFAA00));
                else if(n.tier()>tier) tip.add(Component.literal("Tier locked").withColor(0xFF5555));
                else if(level==0) tip.add(Component.literal("Not allocated").withColor(0x777777));
                g.setTooltipForNextFrame(font,tip,Optional.empty(),mx,my);
            }
        }
        int end=treeTop+10*pitch+10;
        JsonObject unknown=prepared().unknownNodes;
        if(!unknown.isEmpty()) end=section(g,"Additional saved nodes",unknown,end,ProfileFormat::number);
        return end;
    }

    private int section(GuiGraphicsExtractor g,String name,JsonObject object,int offset,Function<Double,String> format) {
        text(g,name,cx,y(offset),ProfileStyle.text(),cw); offset+=21;
        // Highest first, by request: the biggest kill counts and collections are what a
        // profile is read for. Ties fall back to the name so the order is stable.
        List<ProfileViewData.Metric> values=prepared().metrics(object);
        if(values.isEmpty()) { text(g,"No recorded progress.",cx+9,y(offset),ProfileStyle.dim(),cw-18); return offset+32; }
        int columns=cw>=420 ? 2 : 1,w=(cw-10*(columns-1))/columns;
        for(int i=0;i<values.size();i++) {
            var value=values.get(i); int x=cx+(i%columns)*(w+10),yy=y(offset+i/columns*29);
            // Preserve full section height and ordering, but do no text, icon or
            // leaderboard preparation for rows clipped out of the viewport.
            if(yy+26<=top || yy>=bottom) continue;
            card(g,x,yy,w,26,ProfileStyle.surface());
            emblem(g,sectionSymbol(name,value.key()),x+7,yy+5);
            String amount=format.apply(value.value());
            String label=value.label();
            // Bestiary entries with a Dragsim board (Golden Dragons) carry their rank.
            int ranked=name.startsWith("Bestiary") ? badge(g,"bestiary_"+value.key(),label+" kills",
                    value.value(),x+w-10-font.width(amount)-6,yy+9) : 0;
            text(g,label,x+31,yy+9,ProfileStyle.muted(),w-45-font.width(amount)-ranked);
            right(g,amount,x+w-10,yy+9,ProfileStyle.text());
        }
        return offset+(values.size()+columns-1)/columns*29+15;
    }

    private String sectionSymbol(String section,String key) {
        String id=key.toLowerCase(Locale.ROOT);
        if(section.endsWith("Dragons")) return "dragon_head";
        id=id.replaceFirst("^mine_","");
        id=switch(id) { case "ender_stone" -> "end_stone"; case "summoning_eye" -> "ender_eye"; case "rough_amethyst" -> "amethyst_shard"; default -> id; };
        if(BuiltInRegistries.ITEM.containsKey(Identifier.fromNamespaceAndPath("minecraft",id))) return id;
        if(id.contains("dragon")) return "dragon_head";
        if(id.contains("enderman") || id.contains("void")) return "ender_pearl";
        if(id.contains("zombie") || id.contains("revenant")) return "rotten_flesh";
        if(id.contains("skeleton") || id.contains("skeletor")) return "bone";
        if(id.contains("blaze")) return "blaze_rod";
        if(id.contains("spider")) return "spider_eye";
        if(id.contains("zealot")) return "ender_eye";
        if(id.contains("ghoul")) return "zombie_head";
        if(id.contains("watcher")) return "skeleton_skull";
        if(id.contains("protector")) return "iron_block";
        if(id.contains("defender")) return "ender_pearl";
        if(id.contains("mithril") || id.contains("titanium")) return "prismarine_crystals";
        return section.equals("Collections") ? "diamond" : section.contains("XP") ? "experience_bottle" : section.contains("completions") ? "ladder" : "nether_star";
    }

    private void bestiary(GuiGraphicsExtractor g) {
        heading(g,"Bestiary & collections","Every recorded kill and gathered collection.");
        int offset=42;
        offset=section(g,"Collections",object(data.profile,"collections"),offset,ProfileFormat::compact);
        offset=groupedSection(g,"Bestiary",prepared().bestiary,offset);
        offset=groupedSection(g,"RNG meters",prepared().meters,offset);
        contentHeight=offset;
    }

    private int groupedSection(GuiGraphicsExtractor g,String heading,JsonObject values,int offset) {
        for(var group:prepared().groups(values)) offset=section(g,heading+" · "+group.name(),group.values(),offset,ProfileFormat::compact);
        return offset;
    }

    private void detail(GuiGraphicsExtractor g) {
        int yy=top+2;
        button(g,cx,yy,60,20,"< Back",false,() -> { pinned=null; scroll=0; },true);
        boolean catalogOnly=pinned.location().startsWith("catalog/");
        if(g!=null) g.item(items.icon(pinned),cx+72,yy+2);
        text(g,catalogOnly ? "Catalog preview · Not owned" : "Item preview",cx+96,yy+6,ProfileStyle.muted(),cw-100);
        JsonObject estimate=price(pinned);
        double value=number(estimate,"value",Double.NaN);
        String method=text(estimate,"method","");
        List<Component> lore=new ArrayList<>();
        lore.add(items.nameLine(pinned));
        lore.addAll(items.lore(pinned));
        boolean sideBySide=cw>=450;
        int natural=lore.stream().mapToInt(font::width).max().orElse(180);
        int textWidth=Math.clamp(natural,180,Math.min(286,cw-32));
        int tipW=textWidth+24,priceW=164,gap=20;
        int tipX=cx+Math.max(0,(cw-tipW-(sideBySide ? priceW+gap : 0))/2);
        int tipY=top+(sideBySide || catalogOnly ? 44 : 50);
        if(!sideBySide && !catalogOnly) {
            text(g,"Value "+money(value)+(method.isEmpty() ? "" : " · "+method),cx,top+32,ProfileStyle.obs(),cw);
        }
        List<net.minecraft.util.FormattedCharSequence> lines=new ArrayList<>();
        List<Integer> heights=new ArrayList<>();
        int loreHeight=0;
        for(int i=0;i<lore.size();i++) {
            Component line=lore.get(i);
            if(line.getString().isEmpty()) {
                lines.add(Component.empty().getVisualOrderText()); heights.add(5); loreHeight+=5;
            } else for(var part:font.split(line,textWidth)) {
                int height=i==0 ? 13 : 10;
                lines.add(part); heights.add(height); loreHeight+=height;
            }
        }
        int viewport=Math.max(20,bottom-tipY-5),tipH=Math.min(loreHeight+24,viewport);
        int maxScroll=Math.max(0,loreHeight+24-viewport);
        scroll=Math.clamp(scroll,0,maxScroll);
        // Use Minecraft's tooltip sprites so resource packs and the in-game item
        // framing carry through. Only the text inside this fixed frame scrolls.
        if(g!=null) TooltipRenderUtil.extractTooltipBackground(g,tipX+12,tipY+12,textWidth,tipH-24,null);
        int lineY=tipY+12-scroll;
        for(int i=0;i<lines.size();i++) {
            if(g!=null && lineY>=tipY+9 && lineY+font.lineHeight<=tipY+tipH-9)
                g.text(font,lines.get(i),tipX+12,lineY,0xFFFFFFFF,true);
            lineY+=heights.get(i);
        }
        if(sideBySide && !catalogOnly) {
            int x=tipX+tipW+gap,sy=tipY+5;
            text(g,"PRICING",x,sy,ProfileStyle.dim(),priceW);
            metric(g,"Value",money(value),x,sy+24,priceW);
            metric(g,"From",method.isEmpty() ? "—" : method,x,sy+65,priceW);
            String note=prices==null ? "Prices unavailable." : bool(estimate,"cosmetic") ? "Skins and dyes are not counted in networth."
                    : "Priced like dragsim.co, with craft cost and older sales where it has none.";
            int dy=sy+105;
            for(String line:Draw.wrap(font,note,priceW,4)) { text(g,line,x,dy,ProfileStyle.dim(),priceW); dy+=12; }
        }
        contentHeight=(bottom-top)+maxScroll;
    }

    private static String money(double value) { return Double.isFinite(value) ? compact(value)+" coins" : "—"; }

    /** This profile's place on a Dragsim leaderboard, or -1 (the site publishes the top 25). */
    private int position(String board,double value) {
        return data==null ? -1 : ProfileClient.position(boards,board,data.player,data.profileId,value);
    }
    /** A small "#N" pill ending at rightX, gold for the top three. Returns the width it took
     *  plus a gap, so the caller can set the value beside it; 0 when there is no position. */
    private int badge(GuiGraphicsExtractor g,String board,String label,double value,int rightX,int y) {
        int position=position(board,value);
        if(position<1) return 0;
        String text="#"+position; int w=font.width(text)+8;
        if(g!=null && y-2>=top && y+10<=bottom) {
            boolean podium=position<=3;
            Draw.roundedRect(g,rightX-w,y-2,w,12,3,podium ? Draw.lerp(ProfileStyle.input(),ProfileStyle.obs(),.22f) : ProfileStyle.raised());
            Draw.text(g,font,text,rightX-w+4,y,podium ? ProfileStyle.obs() : ProfileStyle.accent());
            if(inside(mx,my,rightX-w,y-2,w,12)) g.setTooltipForNextFrame(font,Component.literal("#"+position+" on Dragsim's "+label+" leaderboard"),mx,my);
        }
        return w+6;
    }
    private void privacy(GuiGraphicsExtractor g) { empty(g,"Inventory private","This player has hidden their items and pets."); contentHeight=90; }
    private void empty(GuiGraphicsExtractor g,String heading,String subtitle) {
        card(g,cx,top+15,cw,98,ProfileStyle.surface());
        text(g,heading,cx+18,top+35,ProfileStyle.text(),cw-36);
        text(g,subtitle,cx+18,top+56,ProfileStyle.muted(),cw-36);
    }

    private void slot(GuiGraphicsExtractor g,ProfileData.Item item,int x,int y,int size) {
        if(y+size<=top || y>=bottom) return;
        boolean hot=inside(mx,my,x,y,size,size) && my>=top && my<bottom;
        boolean catalogOnly=item!=null && item.location().startsWith("catalog/");
        card(g,x,y,size,size,hot ? ProfileStyle.hover() : ProfileStyle.input());
        if(item==null) return;
        if(g!=null && y>=top && y+size<=bottom) {
            int color=ProfileItems.color(item.rarity());
            Draw.roundedRect(g,x+3,y+size-3,size-6,1,1,Draw.alpha(color,.65f));
            int ix=x+(size-16)/2,iy=y+(size-16)/2;
            g.item(items.icon(item),ix,iy);
            if(catalogOnly) {
                Draw.rect(g,ix,iy,16,16,Draw.alpha(ProfileStyle.bg(),.36f));
                Draw.text(g,font,"?",x+size-8,y+size-10,ProfileStyle.muted());
            }
            if(item.count()>1) g.itemDecorations(font,items.icon(item),ix,iy,item.count()>999 ? compact(item.count()) : Integer.toString(item.count()));
            if(hot) {
                g.setTooltipForNextFrame(font,tooltip(item,catalogOnly),Optional.empty(),mx,my);
            }
        }
        int sy=Math.max(top,y),sh=Math.min(bottom,y+size)-sy;
        if(sh>0) hits.add(new Hit(x,sy,size,sh,() -> { pinned=item; scroll=0; }));
    }

    private List<Component> tooltip(ProfileData.Item item,boolean catalogOnly) {
        JsonObject estimate=catalogOnly ? noPrice : price(item);
        return tooltipCache.get(items,item,estimate,catalogOnly,ProfileStyle.muted(),ProfileStyle.dim());
    }

    private void card(GuiGraphicsExtractor g,int x,int y,int w,int h,int color) {
        if(g==null) return;
        int vt=Math.max(top,y),vb=Math.min(bottom,y+h);
        if(vb>vt) Draw.roundedRect(g,x,vt,w,vb-vt,4,color,y>=top,y>=top,y+h<=bottom,y+h<=bottom);
    }
    private void text(GuiGraphicsExtractor g,String value,int x,int y,int color,int w) {
        if(g!=null && y>=top && y+font.lineHeight<=bottom && w>0) Draw.text(g,font,Draw.fit(font,value,w),x,y,color);
    }
    private static String text(JsonObject object,String key,String fallback) { return ProfileData.text(object,key,fallback); }
    private void strong(GuiGraphicsExtractor g,String value,int x,int y,int color,int w) {
        if(g!=null && y>=top && y+font.lineHeight<=bottom) g.text(font,Component.literal(Draw.fit(font,value,Math.max(1,w-5))).withStyle(ChatFormatting.BOLD),x,y,color,false);
    }
    private void right(GuiGraphicsExtractor g,String value,int x,int y,int color) {
        if(g!=null && y>=top && y+font.lineHeight<=bottom) Draw.textRight(g,font,value,x,y,color);
    }
    private void metric(GuiGraphicsExtractor g,String label,String value,int x,int y,int w) {
        text(g,label,x,y,ProfileStyle.muted(),w); text(g,value,x,y+13,ProfileStyle.text(),w);
    }
    private void bar(GuiGraphicsExtractor g,int x,int y,int w,double progress,int color) {
        if(g==null || y<top || y+3>bottom || w<=0) return;
        Draw.roundedRect(g,x,y,w,3,1,ProfileStyle.hair());
        Draw.roundedRect(g,x,y,(int)Math.round(w*Math.clamp(progress,0,1)),3,1,color);
    }
    private void button(GuiGraphicsExtractor g,int x,int y,int w,int h,String label,boolean selected,Runnable action,boolean clipped) {
        if(w<=0 || clipped && (y<top || y+h>bottom)) return;
        boolean hot=inside(mx,my,x,y,w,h);
        if(g!=null) {
            Draw.roundedRect(g,x,y,w,h,4,selected ? Draw.lerp(ProfileStyle.raised(),ProfileStyle.accent(),.22f) : hot ? ProfileStyle.hover() : ProfileStyle.input());
            Draw.textCentered(g,font,Draw.fit(font,label,w-8),x+w/2,y+(h-font.lineHeight)/2,selected ? ProfileStyle.text() : ProfileStyle.muted());
        }
        hits.add(new Hit(x,y,w,h,() -> { searchFocused=false; filterFocused=false; action.run(); }));
    }
    private void itemButton(GuiGraphicsExtractor g,int x,int y,int w,int h,String icon,String label,boolean selected,Runnable action) {
        if(y<top || y+h>bottom) return;
        if(g!=null) {
            Draw.roundedRect(g,x,y,w,h,3,selected ? ProfileStyle.raised() : inside(mx,my,x,y,w,h) ? ProfileStyle.hover() : ProfileStyle.input());
            g.item(symbol(icon),x+6,y+(h-16)/2);
            if(w>=90) Draw.text(g,font,Draw.fit(font,label,w-31),x+27,y+(h-font.lineHeight)/2,selected ? ProfileStyle.text() : ProfileStyle.muted());
            else if(inside(mx,my,x,y,w,h)) g.setTooltipForNextFrame(font,Component.literal(label),mx,my);
            if(selected) Draw.rect(g,x+5,y+h-2,w-10,1,ProfileStyle.accent());
        }
        hits.add(new Hit(x,y,w,h,() -> { searchFocused=false; filterFocused=false; action.run(); }));
    }
    private static boolean inside(double mx,double my,int x,int y,int w,int h) { return mx>=x && mx<x+w && my>=y && my<y+h; }
    private void searchPlayer() {
        if(search.isBlank()) return;
        requested=search.trim(); selectedProfile=""; page=0; searchFocused=false; load(false);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice) {
        if(event.button()!=0) return true;
        draw(null,(int)event.x(),(int)event.y());
        for(int i=hits.size()-1;i>=0;i--) if(hits.get(i).contains(event.x(),event.y())) { hits.get(i).action().run(); return true; }
        searchFocused=false; filterFocused=false; profileMenu=false; return true;
    }
    @Override public boolean mouseScrolled(double mouseX,double mouseY,double horizontal,double vertical) {
        geometry();
        if(inside(mouseX,mouseY,cx,top,cw,bottom-top)) scroll=Math.clamp(scroll-(int)Math.round(vertical*28),0,Math.max(0,contentHeight-(bottom-top)));
        return true;
    }
    @Override public boolean charTyped(CharacterEvent event) {
        if(filterFocused && event.isAllowedChatCharacter() && filter.length()<60) { filter+=event.codepointAsString(); scroll=0; return true; }
        if(searchFocused && event.isAllowedChatCharacter() && search.length()<16 && event.codepointAsString().matches("[A-Za-z0-9_]")) search+=event.codepointAsString();
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        int key=event.key();
        if(key==256) {
            if(profileMenu) profileMenu=false;
            else if(searchFocused) searchFocused=false;
            else if(filterFocused || !filter.isEmpty()) { filterFocused=false; filter=""; scroll=0; }
            else if(pinned!=null) { pinned=null; scroll=0; }
            else onClose();
            return true;
        }
        if(filterFocused) {
            if(key==259 && !filter.isEmpty()) { filter=filter.substring(0,filter.offsetByCodePoints(filter.length(),-1)); scroll=0; }
            if(key==257) filterFocused=false;
            if(key==65 && (event.modifiers()&2)!=0) { filter=""; scroll=0; }
            return true;
        }
        if(searchFocused) {
            if(key==259 && !search.isEmpty()) search=search.substring(0,search.length()-1);
            if(key==257 || key==335) searchPlayer();
            if(key==65 && (event.modifiers()&2)!=0) search="";
            if(key==86 && (event.modifiers()&2)!=0) {
                String pasted=Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[^A-Za-z0-9_]","");
                search=pasted.substring(0,Math.min(16,pasted.length()));
            }
            return true;
        }
        if(key==70 && (event.modifiers()&2)!=0) { searchFocused=true; search=""; return true; }
        if(key==267) scroll=Math.min(Math.max(0,contentHeight-(bottom-top)),scroll+Math.max(28,bottom-top-24));
        if(key==266) scroll=Math.max(0,scroll-Math.max(28,bottom-top-24));
        if(key==268) scroll=0;
        if(key==269) scroll=Math.max(0,contentHeight-(bottom-top));
        if(key==82 && System.currentTimeMillis()>=nextRefresh) { nextRefresh=System.currentTimeMillis()+5000; load(true); }
        return true;
    }
}
