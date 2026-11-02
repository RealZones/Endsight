package com.endsight.ui;

import com.endsight.EndsightClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.KeyEvent;

/** Grouped settings pages as the browser draws them, found through its own search. */
public final class MenuVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(2);
                mc.resizeGui();
            });
            for (String page : new String[]{"slayer tracking", "countdowns", "boss indicators"}) {
                context.runOnClient(mc -> mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry())));
                context.waitTicks(2);
                context.runOnClient(mc -> {
                    for (int i = 0; i < 30; i++) mc.screen.keyPressed(new KeyEvent(259, 0, 0));
                });
                context.getInput().typeChars(page);
                context.waitTicks(2);
                // Enter opens the first result, the way a player gets to a page from search.
                context.runOnClient(mc -> mc.screen.keyPressed(new KeyEvent(257, 0, 0)));
                context.waitTicks(10);
                context.takeScreenshot("menu-" + page.replace(' ', '-'));
            }
            context.runOnClient(mc -> mc.setScreen(null));
            System.out.println("MENU VISUAL CHECKS DONE");
        }
    }
}
