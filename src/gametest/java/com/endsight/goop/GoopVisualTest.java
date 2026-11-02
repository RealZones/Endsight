package com.endsight.goop;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.chat.Component;

/**
 * The Goop Tracker card as the game draws it, from Fear's two goops of 2026-11-01 (20:59 and
 * 21:01) replayed through real chat events, then one placed by somebody else. "The rift falls
 * silent." is not in his log - Ability Spam hid it, so it went to GAME_CANCELED unlogged - and
 * is put back here; every other line is the server's own, colour codes and all.
 */
public final class GoopVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(2);
                mc.resizeGui();
            });
            String placed = "§5§l" + context.computeOnClient(mc -> mc.player.getName().getString())
                    + " §dsqueezes the goop into the altar.";
            fight(context, placed, "The rift falls silent.",
                    "§d§l+1 Crypt Cache§7, §a16 Rift Goo §7and §a2 Gelatinous Membranes §6(top three)");
            fight(context, placed, "The rift falls silent.",
                    "§d§l+1 Crypt Cache§7, §a12 Rift Goo §7and §a2 Gelatinous Membranes §6(top three)",
                    "§5§lTHE DISTURBED DROPPED", "§7  §5Training Weights");
            context.takeScreenshot("goop-card-yours");
            say(context, "§5§l_nephis §dsqueezes the goop into the altar.");
            context.waitTicks(5);
            context.takeScreenshot("goop-card-since");
            context.runOnClient(mc -> mc.getConnection().sendCommand("drops goop session"));
            context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("", false));
            context.waitTicks(5);
            context.takeScreenshot("goop-command");
            context.runOnClient(mc -> mc.setScreen(null));
            System.out.println("GOOP VISUAL CHECKS DONE");
        }
    }

    /** One fight's lines, then long enough for its payout window to close and book it. */
    private static void fight(ClientGameTestContext context, String... lines) {
        say(context, lines);
        // The payout window is 5 s of wall-clock time; 130 ticks is 6.5 s.
        context.waitTicks(130);
    }

    private static void say(ClientGameTestContext context, String... lines) {
        context.runOnClient(mc -> {
            for (String line : lines) mc.getChatListener().handleSystemMessage(Component.literal(line), false);
            mc.gui.getChat().clearMessages(false);
        });
    }
}
