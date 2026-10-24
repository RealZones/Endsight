package com.endsight.storage;

import com.endsight.dragons.BossDrops;
import com.endsight.ui.SettingsScreen;
import com.endsight.EndsightClient;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.lang.reflect.Field;

/** Replays the recorded private Cache result through real chat events in a test world. */
public final class CacheVisualTest implements FabricClientGameTest {
    private static Object field(Class<?> type, String name) {
        try { Field f=type.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
        catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1440,960);
        try(var world=context.worldBuilder().create()) {
            context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
            CacheLedger ledger=(CacheLedger)field(CacheTracker.class,"ledger");
            CacheVisibility visibility=(CacheVisibility)field(CacheTracker.class,"visibility");
            CacheLedger.Counts original=ledger.counts(true);
            String before=context.computeOnClient(mc -> new Gson().toJson(field(BossDrops.class,"session")));
            context.runOnClient(mc -> {
                CacheTracker.module().setEnabled().accept(true);
                ledger.restore(new CacheLedger.Counts()); ledger.resetSession(); ledger.resetSource();
                if(visibility.visible(System.currentTimeMillis())) throw new AssertionError("HUD visible before own placement");
                var pos=mc.player.blockPosition().offset(1,0,0);
                // Mimic the server-confirmed vault at the local player's click.
                mc.level.setBlock(pos,Blocks.VAULT.defaultBlockState(),3);
                mc.hitResult=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false);
                for(String row:new String[]{
                        "§5☠ §dYou set down a Crypt Cache. §7It opens in 5 seconds.",
                        "§5☠ §dThe Crypt Cache rolled like §fChaotic Dragon§d! §7Loot number: §f62.300",
                        "§5☠ §dCrypt Cache", "§7  §9Dragon Scale",
                        "§5☠ §dThe Crypt Cache rolled like §fEndstone Protector§d! §7Loot number: §f0.045",
                        "§5☠ §dCrypt Cache", "§7  §6Giant's Core",
                        "§5☠ §dThe Crypt Cache rolled like §fVoidgloom Seraph§d! §7Loot number: §f20.123",
                        "§5☠ §dCrypt Cache", "§7  §52x Summoning Eye", "§7  §b3x Null Atom"})
                    mc.getChatListener().handleSystemMessage(Component.literal(row),false);
                if(ledger.opened(false)!=3 || ledger.counts(false).drops.size()!=4) throw new AssertionError("Actual chat events failed to record Cache rewards");
                if(!visibility.visible(System.currentTimeMillis())) throw new AssertionError("Own placement did not show the HUD");
                if(!before.equals(new Gson().toJson(field(BossDrops.class,"session")))) throw new AssertionError("Cache loot polluted normal boss counts");
                mc.getChatListener().handleSystemMessage(Component.literal("§5☠ §dThe Crypt Cache rolled like §fSuperior Dragon§d! §7Loot number: §f1.000"),false);
                mc.getChatListener().handleSystemMessage(Component.literal("§5☠ §dCrypt Cache"),false);
                mc.getChatListener().handleSystemMessage(Component.literal("  [Lvl 1] ").withColor(0xAAAAAA)
                        .append(Component.literal("Ender Dragon").withColor(0xFFAA00)),false);
                if(!ledger.counts(false).drops.containsKey("[Lvl 1] Ender Dragon (Legendary)")) throw new AssertionError("Styled pet rarity lost in native chat event");
                mc.gui.getChat().clearMessages(false);
            });
            context.waitTicks(10); context.takeScreenshot("cache-tracker-hud");
            context.runOnClient(mc -> mc.getConnection().sendCommand("drops cache session"));
            context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("",false));
            context.waitTicks(5); context.takeScreenshot("cache-tracker-command");
            context.runOnClient(mc -> {
                if(mc.gui.getChat().allMessages.stream().noneMatch(m -> m.content().getString().contains("4 opened")))
                    throw new AssertionError("/drops cache session did not print the saved result");
                mc.setScreen(null);
                try {
                    var f=CacheTracker.class.getDeclaredField("ownBlock"); f.setAccessible(true);
                    var pos=(BlockPos)f.get(null); mc.level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
                } catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            context.waitTicks(70);
            context.runOnClient(mc -> { if(visibility.visible(System.currentTimeMillis())) throw new AssertionError("Cache HUD stayed visible after despawn grace"); });
            context.takeScreenshot("cache-tracker-hidden-after-despawn");
            context.runOnClient(mc -> {
                var module=EndsightClient.registry().all().stream().filter(m -> m.id().equals("tracker.caches")).findFirst().orElseThrow();
                mc.setScreen(new SettingsScreen(null,"Endsight",module));
            });
            context.waitTicks(5); context.takeScreenshot("cache-tracker-settings");
            context.runOnClient(mc -> { ledger.restore(original); ledger.resetSession(); ledger.resetSource(); visibility.reset(); mc.setScreen(null); });
        }
    }
}
