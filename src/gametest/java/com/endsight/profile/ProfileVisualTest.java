package com.endsight.profile;

import com.endsight.ui.ProfileScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import java.lang.reflect.Field;

/** Captures the actual renderer with a live profile in an isolated flat world. */
public final class ProfileVisualTest implements FabricClientGameTest {
    private static Object field(Object instance,String name) {
        try { Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance); }
        catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    private static void click(ClientGameTestContext context,int x,int y) {
        context.getInput().setCursorPos(x*2,y*2); context.getInput().pressMouse(0); context.waitTicks(5);
    }
    private static void set(Object instance,String name,Object value) {
        try { Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);field.set(instance,value); }
        catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1440,960);
        try(var world=context.worldBuilder().create()) {
            context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
            context.runOnClient(mc -> {
                // Feed a real command packet with the conflicting server grammar
                // through the mixin and Fabric's merge, rather than testing only
                // an independently constructed Brigadier tree.
                var tree=new com.mojang.brigadier.CommandDispatcher<String>();
                tree.register(com.mojang.brigadier.builder.LiteralArgumentBuilder.<String>literal("pv")
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<String,Integer>argument("player",com.mojang.brigadier.arguments.IntegerArgumentType.integer())));
                var packet=new net.minecraft.network.protocol.game.ClientboundCommandsPacket(tree.getRoot(),
                        new net.minecraft.network.protocol.game.ClientboundCommandsPacket.NodeInspector<String>() {
                            public net.minecraft.resources.Identifier suggestionId(com.mojang.brigadier.tree.ArgumentCommandNode<String,?> node) { return null; }
                            public boolean isExecutable(com.mojang.brigadier.tree.CommandNode<String> node) { return false; }
                            public boolean isRestricted(com.mojang.brigadier.tree.CommandNode<String> node) { return false; }
                        });
                mc.getConnection().handleCommands(packet);
                try {
                    Field history=ProfileClient.class.getDeclaredField("NAMES"); history.setAccessible(true);
                    ((ProfileNames)history.get(null)).include("Imfear");
                } catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
                mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/pv ",false));
            });
            context.getInput().typeChars("i"); context.waitTicks(6);
            context.runOnClient(mc -> {
                var source=mc.getConnection().getSuggestionsProvider();
                var dispatcher=mc.getConnection().getCommands();
                var parsed=dispatcher.parse("pv i",source);
                if(parsed.getReader().canRead()) throw new AssertionError("Chat still treats /pv as an integer command");
                var suggestions=dispatcher.getCompletionSuggestions(parsed).join().getList();
                if(suggestions.stream().noneMatch(s -> s.getText().equals("Imfear"))) throw new AssertionError("First-letter chat autocomplete is missing");
            });
            context.takeScreenshot("profile-chat-autocomplete");
            context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB); context.waitTicks(2);
            context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
            context.waitFor(mc -> mc.screen instanceof ProfileScreen,100);
            context.runOnClient(mc -> mc.setScreen(null));
            context.runOnClient(mc -> mc.getConnection().sendCommand("pv Imfear"));
            context.waitFor(mc -> mc.screen instanceof ProfileScreen && !(boolean)field(mc.screen,"loading"),1200);
            context.runOnClient(mc -> { if(field(mc.screen,"data")==null) throw new AssertionError("Profile failed: "+field(mc.screen,"error")); });
            context.waitFor(mc -> !(boolean)field(mc.screen,"pricesLoading"),1200);
            context.waitTicks(30);
            context.takeScreenshot("profile-overview");
            // Leaderboard pills: the real boards load beside the profile. The player is not in
            // either slayer top 25, so a copy places them there to show the slayer pills.
            context.waitFor(mc -> !((com.google.gson.JsonObject)field(mc.screen,"boards")).isEmpty(),600);
            com.google.gson.JsonObject realBoards=context.computeOnClient(mc -> (com.google.gson.JsonObject)field(mc.screen,"boards"));
            context.runOnClient(mc -> {
                ProfileData shown=(ProfileData)field(mc.screen,"data");
                com.google.gson.JsonObject slayer=ProfileData.object(shown.profile,"slayer"),fake=realBoards.deepCopy();
                for(String[] board:new String[][]{{"slayer_zombie_xp","zombie_xp","4"},{"slayer_enderman_xp","enderman_xp","1"}}) {
                    com.google.gson.JsonObject row=new com.google.gson.JsonObject();
                    row.addProperty("position",Integer.parseInt(board[2])); row.addProperty("username",shown.player);
                    row.addProperty("value",ProfileData.number(slayer,board[1],0));
                    com.google.gson.JsonArray rows=new com.google.gson.JsonArray(); rows.add(row); fake.add(board[0],rows);
                }
                set(mc.screen,"boards",fake);
            });
            context.waitTicks(5); context.takeScreenshot("profile-leaderboard-fixture");
            context.runOnClient(mc -> set(mc.screen,"boards",realBoards));
            context.getInput().resizeWindow(1920,1080);context.waitTicks(8);
            context.takeScreenshot("profile-fullhd");
            context.getInput().resizeWindow(3440,1440);context.waitTicks(8);
            context.takeScreenshot("profile-ultrawide");
            context.getInput().resizeWindow(1440,960);context.waitTicks(8);
            context.runOnClient(mc -> System.out.println("PROFILE_PREVIEW "+mc.screen.width+"x"+mc.screen.height+" data="+((ProfileData)field(mc.screen,"data")).assets.size()+" priced="+field(mc.screen,"pricedCount")+" priceError="+field(mc.screen,"priceError")));
            context.runOnClient(mc -> {
                var icon=com.endsight.storage.Recipes.icon("Blinding Sword");
                if(icon==null || TooltipPrices.read(icon)==null) throw new AssertionError("Real server NBT could not resolve its catalog identity");
                var raw=TooltipPrices.read(icon);
                if(!raw.get("entry").getAsString().equals("blinding_sword")) throw new AssertionError("Wrong NBT identity");
                if(!raw.getAsJsonObject("item_modifiers").getAsJsonPrimitive("recombobulated").isBoolean()) throw new AssertionError("NBT flag type did not normalize");
                System.out.println("PROFILE_TOOLTIP_ID "+raw.get("entry"));
            });
            int[] nav=context.computeOnClient(mc -> new int[]{(int)field(mc.screen,"px")+8,(int)field(mc.screen,"py")-29,Math.min(118,((int)field(mc.screen,"pw")-16)/5)});
            for(int i=1;i<=4;i++) {
                context.getInput().setCursorPos((nav[0]+i*nav[2]+20)*2,(nav[1]+12)*2);
                context.getInput().pressMouse(0); context.waitTicks(10);
                final int selected=i;
                context.runOnClient(mc -> { if(((Enum<?>)field(mc.screen,"view")).ordinal()!=selected) throw new AssertionError("Native tab did not navigate"); });
                context.takeScreenshot(new String[]{"","profile-items","profile-pets","profile-progression","profile-bestiary"}[i]);
            }
            context.getInput().setCursorPos(1200,750); context.getInput().scroll(-80); context.waitTicks(5);
            context.takeScreenshot("profile-rng-groups");
            click(context,nav[0]+nav[2]+20,nav[1]+12);
            int[] content=context.computeOnClient(mc -> new int[]{(int)field(mc.screen,"cx"),(int)field(mc.screen,"top"),(int)field(mc.screen,"cw")});
            click(context,content[0]+35,content[1]+85);
            context.getInput().typeChars("Frozen Scythe"); context.waitTicks(5);
            context.runOnClient(mc -> { if(!field(mc.screen,"filter").equals("Frozen Scythe")) throw new AssertionError("Item search did not accept text"); });
            context.getInput().setCursorPos((content[0]+10)*2,(content[1]+141)*2); context.waitTicks(8);
            context.takeScreenshot("profile-item-tooltip");
            click(context,content[0]+10,content[1]+141);
            context.runOnClient(mc -> { if(field(mc.screen,"pinned")==null) throw new AssertionError("Filtered item did not open inspector"); });
            context.takeScreenshot("profile-item-detail");
            context.getInput().resizeWindow(854,480); context.waitTicks(5);
            context.takeScreenshot("profile-item-detail-compact");
            context.getInput().setCursorPos(550,330); context.getInput().scroll(-4); context.waitTicks(5);
            context.takeScreenshot("profile-item-detail-compact-scrolled");
            context.runOnClient(mc -> { if((int)field(mc.screen,"scroll")<=0) throw new AssertionError("Compact item lore did not scroll"); });
            context.getInput().resizeWindow(1440,960); context.waitTicks(5);
            click(context,content[0]+20,content[1]+12);
            click(context,content[0]+content[2]*2/5+15,content[1]+52);
            context.runOnClient(mc -> { if(!field(mc.screen,"containerType").equals("storage") || !field(mc.screen,"filter").equals("")) throw new AssertionError("Storage category did not reset search"); });
            // Storage shows every page with items at once, so there is no pager to click.
            context.runOnClient(mc -> { if((int)field(mc.screen,"contentHeight")<=(int)field(mc.screen,"bottom")-(int)field(mc.screen,"top")) throw new AssertionError("Storage did not lay out its pages"); });
            context.takeScreenshot("profile-storage");
            click(context,content[0]+content[2]/5+15,content[1]+52);
            context.takeScreenshot("profile-ender");
            click(context,content[0]+15,content[1]+52);
            context.takeScreenshot("profile-inventory");
            click(context,content[0]+content[2]*3/5+15,content[1]+52);
            context.takeScreenshot("profile-accessories");
            click(context,content[0]+content[2]*4/5+15,content[1]+52);
            context.takeScreenshot("profile-wardrobe");
            int[] profileButton=context.computeOnClient(mc -> new int[]{(int)field(mc.screen,"px")+(int)field(mc.screen,"pw")-250,(int)field(mc.screen,"py")+40});
            click(context,profileButton[0],profileButton[1]);
            context.runOnClient(mc -> { if(!(boolean)field(mc.screen,"profileMenu")) throw new AssertionError("Profile picker did not open"); });
            context.takeScreenshot("profile-picker");
            int[] picker=context.computeOnClient(mc -> new int[]{(int)field(mc.screen,"px")+(int)field(mc.screen,"pw")-100,(int)field(mc.screen,"py")+53+4+2*25+10});
            click(context,picker[0],picker[1]);
            context.waitFor(mc -> !(boolean)field(mc.screen,"loading"),1200);
            context.runOnClient(mc -> { if(!ProfileData.text(((ProfileData)field(mc.screen,"data")).profile,"profile_mode","").equalsIgnoreCase("IRONMAN")) throw new AssertionError("Ironman profile did not load"); });
            click(context,nav[0]+20,nav[1]+12);
            context.takeScreenshot("profile-ironman");
            ProfileData realRank=context.computeOnClient(mc -> (ProfileData)field(mc.screen,"data"));
            context.runOnClient(mc -> {
                var root=realRank.root.deepCopy(); root.getAsJsonObject("player").addProperty("rank","MVP_PLUS");
                set(mc.screen,"data",new ProfileData(root));
            });
            context.waitTicks(5);context.takeScreenshot("profile-rank-badge-fixture");
            context.runOnClient(mc -> set(mc.screen,"data",realRank));
            click(context,nav[0]+nav[2]*4+20,nav[1]+12);
            context.getInput().resizeWindow(854,480); context.waitTicks(10);
            context.takeScreenshot("profile-compact");
            context.getInput().setCursorPos(650,310);
            context.getInput().scroll(-5); context.waitTicks(10);
            context.takeScreenshot("profile-compact-scrolled");
            context.runOnClient(mc -> { if((int)field(mc.screen,"scroll")<=0) throw new AssertionError("Compact content did not scroll"); });
            context.getInput().resizeWindow(1440,960); context.waitTicks(5);
            click(context,nav[0]+nav[2]+20,nav[1]+12);
            context.runOnClient(mc -> {
                var root=((ProfileData)field(mc.screen,"data")).root.deepCopy();
                root.getAsJsonObject("profile").addProperty("inventory_hidden",true);
                set(mc.screen,"data",new ProfileData(root)); set(mc.screen,"prices",null);
            });
            context.waitTicks(5); context.takeScreenshot("profile-private");
            context.setScreen(() -> new ProfileScreen(null,"!"));
            context.waitFor(mc -> !(boolean)field(mc.screen,"loading"),100);
            context.runOnClient(mc -> { if(field(mc.screen,"data")!=null || ((String)field(mc.screen,"error")).isBlank()) throw new AssertionError("Invalid username did not show an error"); });
            context.waitTicks(5); context.takeScreenshot("profile-error");
            context.setScreen(() -> null);
        }
    }
}
