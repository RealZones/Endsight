package com.endsight.visual;

import com.endsight.EndsightClient;
import com.endsight.ui.EndsightScreen;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * The Camera page at the real window, then each of its controls measured on the live camera:
 * distance from the eye, a wall behind, the F5 cycle, and the ease out, which moves the camera
 * out and never turns it.
 */
public final class CameraVisualTest implements FabricClientGameTest {
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
            Module camera = EndsightClient.registry().all().stream().filter(m -> m.id().equals("visual.camera")).findFirst().orElseThrow();
            // The run directory keeps its config, so the last run's values come back: start clean.
            context.runOnClient(mc -> {
                camera.setEnabled().accept(true);
                slider(camera, "Distance").set().accept(4);
                slider(camera, "Smoothing").set().accept(0);
                slider(camera, "Height").set().accept(0);
                slider(camera, "Tilt").set().accept(0);
                toggle(camera, "Through blocks").set().accept(false);
            });
            context.runOnClient(mc -> open(mc));
            context.waitTicks(10);
            context.takeScreenshot("camera-page");
            context.runOnClient(mc -> mc.screen.mouseScrolled(1100, 700, 0, -10));
            context.waitTicks(10);
            context.takeScreenshot("camera-page-end");
            context.runOnClient(mc -> mc.setScreen(null));

            int[] at = new int[3];
            context.runOnClient(mc -> {
                mc.player.setYRot(0);
                mc.player.setXRot(0);
                at[0] = mc.player.getBlockX(); at[1] = mc.player.getBlockY(); at[2] = mc.player.getBlockZ();
                slider(camera, "Distance").set().accept(12);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            });
            context.waitTicks(5);
            context.runOnClient(mc -> {
                double d = gap(mc);
                require(Math.abs(d - 12) < 0.3, "distance 12 put the camera " + d + " away");
            });
            context.takeScreenshot("camera-12-blocks");

            // Height lifts the camera; Tilt angles it. Neither turns the player.
            context.runOnClient(mc -> slider(camera, "Height").set().accept(1.5));
            context.waitTicks(2);
            context.runOnClient(mc -> {
                double up = mc.gameRenderer.getMainCamera().position().y - mc.player.getEyePosition().y;
                require(Math.abs(up - 1.5) < 0.05, "Height 1.5 put the camera " + up + " above the eyes");
                slider(camera, "Height").set().accept(0);
                slider(camera, "Tilt").set().accept(20);
            });
            context.waitTicks(2);
            context.runOnClient(mc -> {
                float pitch = mc.gameRenderer.getMainCamera().xRot();
                require(Math.abs(pitch - 20) < 0.05 && mc.player.getXRot() == 0, "Tilt 20 drew at " + pitch + ", player at " + mc.player.getXRot());
            });
            context.takeScreenshot("camera-tilt-20");
            context.runOnClient(mc -> slider(camera, "Tilt").set().accept(0));

            // A wall three blocks behind: vanilla pulls in to it, Through blocks does not.
            world.getServer().runCommand("fill " + (at[0] - 3) + " " + at[1] + " " + (at[2] - 3) + " "
                    + (at[0] + 3) + " " + (at[1] + 4) + " " + (at[2] - 3) + " stone");
            context.waitTicks(5);
            context.runOnClient(mc -> require(gap(mc) < 3.5, "the wall did not pull the camera in: " + gap(mc)));
            context.runOnClient(mc -> toggle(camera, "Through blocks").set().accept(true));
            context.waitTicks(2);
            context.runOnClient(mc -> require(Math.abs(gap(mc) - 12) < 0.3, "Through blocks still stopped at the wall: " + gap(mc)));
            context.runOnClient(mc -> toggle(camera, "Through blocks").set().accept(false));

            context.runOnClient(mc -> {
                Setting.Choice views = (Setting.Choice) camera.settings().stream().filter(s -> s.label().equals("F5 views")).findFirst().orElseThrow();
                views.set().accept("Skip front");
                require(CameraType.THIRD_PERSON_BACK.cycle() == CameraType.FIRST_PERSON, "Skip front still went to the front view");
                views.set().accept("Skip back");
                require(CameraType.FIRST_PERSON.cycle() == CameraType.THIRD_PERSON_FRONT, "Skip back still went to the back view");
                views.set().accept("All");
                require(CameraType.THIRD_PERSON_BACK.cycle() == CameraType.THIRD_PERSON_FRONT, "All skipped a view");
            });

            // Smoothing: out of first person, the camera slides out rather than appearing.
            world.getServer().runCommand("fill " + (at[0] - 3) + " " + at[1] + " " + (at[2] - 3) + " "
                    + (at[0] + 3) + " " + (at[1] + 4) + " " + (at[2] - 3) + " air");
            context.runOnClient(mc -> {
                slider(camera, "Smoothing").set().accept(50);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            });
            context.waitTicks(3);
            context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.waitTicks(1);
            double[] early = new double[1];
            context.runOnClient(mc -> early[0] = gap(mc));
            context.waitTicks(40);
            context.runOnClient(mc -> {
                double late = gap(mc);
                require(early[0] < 11 && Math.abs(late - 12) < 0.3, "smoothing did not ease out: " + early[0] + " then " + late);
                System.out.println("CAMERA ease out " + early[0] + " -> " + late);
                mc.player.setYRot(mc.player.getYRot() + 90);
            });
            // A turn reaches the camera on the next frame, whatever Smoothing says.
            context.waitTicks(1);
            context.runOnClient(mc -> {
                float lag = net.minecraft.util.Mth.wrapDegrees(mc.gameRenderer.getMainCamera().yRot() - mc.player.getYRot());
                require(Math.abs(lag) < 0.01f, "Smoothing eased the camera's turn: " + lag + " degrees behind");
                camera.setEnabled().accept(false);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            });
            // The switch is the camera: on goes to the view F5 views allows, off puts back
            // first person. Free Look goes to the same view.
            context.runOnClient(mc -> {
                Setting.Choice views = (Setting.Choice) camera.settings().stream().filter(s -> s.label().equals("F5 views")).findFirst().orElseThrow();
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                camera.setEnabled().accept(true);
                require(mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK, "the Camera switch went to " + mc.options.getCameraType());
                camera.setEnabled().accept(false);
                require(mc.options.getCameraType() == CameraType.FIRST_PERSON, "the Camera switch left " + mc.options.getCameraType());
                views.set().accept("Skip back");
                camera.setEnabled().accept(true);
                require(mc.options.getCameraType() == CameraType.THIRD_PERSON_FRONT, "Skip back still opened the back view");
                camera.setEnabled().accept(false);
                var free = Perspective.module();
                free.setEnabled().accept(true);
                require(mc.options.getCameraType() == CameraType.THIRD_PERSON_FRONT, "Free Look ignored F5 views");
                free.setEnabled().accept(false);
                views.set().accept("All");
                // F5 back to first person turns the switch off, as the key would.
                camera.setEnabled().accept(true);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            });
            context.waitTicks(2);
            context.runOnClient(mc -> require(!camera.isEnabled(), "the switch stayed on in first person"));
            System.out.println("CAMERA VISUAL CHECKS DONE");
        }
    }

    private static double gap(Minecraft mc) {
        return mc.gameRenderer.getMainCamera().position().distanceTo(mc.player.getEyePosition());
    }

    private static Setting.Slider slider(Module m, String label) {
        return (Setting.Slider) m.settings().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    private static Setting.Toggle toggle(Module m, String label) {
        return (Setting.Toggle) m.settings().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    /** The menu's first open restores its saved view over these, so open it once first. */
    private static void open(Minecraft mc) {
        mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry()));
        mc.setScreen(null);
        try {
            for (var e : java.util.Map.<String, Object>of("category", "General", "expandedId", "page.camera", "query", "", "scroll", 0).entrySet()) {
                var f = EndsightScreen.class.getDeclaredField(e.getKey());
                f.setAccessible(true);
                f.set(null, e.getValue());
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        mc.setScreen(new EndsightScreen("Endsight", EndsightClient.registry()));
    }
}
