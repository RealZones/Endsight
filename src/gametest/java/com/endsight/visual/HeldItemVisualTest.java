package com.endsight.visual;

import com.endsight.EndsightClient;
import com.endsight.ui.EndsightScreen;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/**
 * Animations with a sword in hand: each swing style caught a third of the way through, the
 * block-hit styles checked in and out of their pose, then the held item moved and resized.
 */
public final class HeldItemVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1920, 1009);
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("time set noon");
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(4);
                mc.resizeGui();
            });
            context.waitTicks(40);
            world.getServer().runCommand("give @a minecraft:iron_sword");
            context.waitTicks(10);
            Module anim = module("visual.animations");
            context.runOnClient(mc -> {
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.player.setYRot(0);
                mc.player.setXRot(10);
                anim.setEnabled().accept(true);
                for (String label : new String[]{"Size", "Swing speed"}) slider(anim, label).set().accept(1);
                for (String label : new String[]{"Side", "Height", "Depth"}) slider(anim, label).set().accept(0);
                // Slow, so a third of the way is four ticks in.
                slider(anim, "Swing speed").set().accept(0.5);
            });
            for (String style : new String[]{"Vanilla", "Spin", "Stab", "1.7", "Exhibition"}) {
                boolean block = style.equals("1.7") || style.equals("Exhibition");
                String name = "swing-" + style.replace('.', '_');
                context.runOnClient(mc -> choice(anim, "Style").set().accept(style));
                context.waitTicks(15);
                // No right click: the block-hit is on the swing itself.
                context.runOnClient(mc -> mc.player.swing(InteractionHand.MAIN_HAND));
                context.waitTicks(4);
                context.takeScreenshot(name);
                if (!block) continue;
                context.runOnClient(mc -> require(blend() > .9f, style + " was not in the blocking pose mid-swing: " + blend()));
                context.waitTicks(30);
                context.runOnClient(mc -> require(blend() < .01f, style + " stayed in the blocking pose after the swing: " + blend()));
                context.takeScreenshot(name + "-after");
            }
            context.runOnClient(mc -> {
                choice(anim, "Style").set().accept("Vanilla");
                slider(anim, "Size").set().accept(0.6);
                slider(anim, "Height").set().accept(-0.1);
                slider(anim, "Side").set().accept(0.1);
            });
            context.waitTicks(15);
            context.takeScreenshot("held-smaller-lower");
            context.runOnClient(mc -> open(mc, "visual.animations"));
            context.waitTicks(10);
            context.runOnClient(mc -> mc.screen.mouseScrolled(1100, 700, 0, -10));
            context.waitTicks(10);
            context.takeScreenshot("animations-page");
            context.runOnClient(mc -> {
                mc.setScreen(null);
                anim.setEnabled().accept(false);
            });
            System.out.println("HELD ITEM VISUAL CHECKS DONE");
        }
    }

    private static float blend() {
        try {
            var f = Animations.class.getDeclaredField("blend");
            f.setAccessible(true);
            return f.getFloat(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Module module(String id) {
        return EndsightClient.registry().all().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private static Setting.Slider slider(Module m, String label) {
        return (Setting.Slider) m.settings().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    private static Setting.Choice choice(Module m, String label) {
        return (Setting.Choice) m.settings().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    /** The menu's first open restores its saved view over these, so open it once first. */
    private static void open(Minecraft mc, String expanded) {
        mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry()));
        mc.setScreen(null);
        try {
            for (var e : java.util.Map.<String, Object>of("category", "General", "expandedId", expanded, "query", "", "scroll", 0).entrySet()) {
                var f = EndsightScreen.class.getDeclaredField(e.getKey());
                f.setAccessible(true);
                f.set(null, e.getValue());
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry()));
    }
}
