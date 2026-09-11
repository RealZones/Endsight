package com.endsight;

import com.endsight.dragons.DragonTimer;
import com.endsight.dragons.Protector;
import com.endsight.hud.Alert;
import com.endsight.hud.Alerts;
import com.endsight.qol.DamageNumbers;
import com.endsight.qol.EyeGuard;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import com.endsight.slayers.Slayer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import com.endsight.storage.SnapshotStore;
import com.endsight.storage.StoragePreview;
import com.endsight.storage.StorageSearch;
import com.endsight.visual.Beacon;
import com.endsight.visual.NukubiHighlight;
import com.endsight.zealots.ZealotTracker;
import com.endsight.ui.EndsightDemo;
import com.endsight.ui.EndsightScreen;
import com.endsight.ui.SettingsScreen;
import com.endsight.ui.ModuleRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Opens the module browser. That is the whole mod so far.
 *
 * The registry lives here rather than inside the UI package: com.endsight.ui knows
 * how to draw modules and nothing about which ones exist, which is what lets the
 * same package serve this mod and any other. Registering a real module means adding
 * a line to {@link #registry()} - the browser picks it up with no layout changes and
 * a new category needs nothing but a new string.
 */
public class EndsightClient implements ClientModInitializer {

    /** Right Shift by default - away from anything vanilla binds and easy to hit. */
    private static final int OPEN_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;

    private static ModuleRegistry registry;
    private boolean keyWasDown = false;

    /** Built once so a toggle survives closing and reopening the screen. */
    public static ModuleRegistry registry() {
        if (registry == null) {
            registry = EndsightDemo.registry();
            // Real modules overwrite their placeholders here rather than in the demo
            // registry, so EndsightDemo stays a pure list of things that do nothing.
            registry.replace(StoragePreview.module());
            registry.replace(StorageSearch.module());
            registry.replace(NukubiHighlight.module());
            registry.replace(DragonTimer.module());
            registry.replace(Protector.module());
            registry.replace(Slayer.killTimerModule());
            registry.replace(ZealotTracker.module());
            registry.replace(Alerts.module());
            registry.replace(EyeGuard.module());
            registry.replace(DamageNumbers.module());
            registry.replace(Beacon.module());
            Config.load(registry);
        }
        return registry;
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        StoragePreview.init();
        SnapshotStore.init();
        StorageSearch.init();
        NukubiHighlight.init();
        DragonTimer.init();
        Protector.init();
        EyeGuard.init();
        DamageNumbers.init();
        Beacon.init();
        Slayer.init();
        ZealotTracker.init();

        // One popup for the whole mod, drawn last so it sits over every readout. Any
        // module can raise it; only one shows at a time, because two things shouting
        // at once is the same as neither.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "alert"),
                (g, delta) -> Alert.draw(g));

        // Saved on exit, and again whenever an Endsight screen closes - quitting the
        // game is not the only way a session ends, and a crash after an hour of tuning
        // should not cost the hour.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Config.save(registry()));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof EndsightScreen || screen instanceof SettingsScreen) {
                ScreenEvents.remove(screen).register(s -> Config.save(registry()));
            }
        });
    }

    private void onTick(Minecraft client) {
        if (client.getWindow() == null) return;

        // Polled rather than registered as a KeyMapping on purpose: a KeyMapping only
        // fires while no screen is open, and this has to be openable from the pause
        // menu later. The edge check is what stops one press opening it every tick.
        boolean down = InputConstants.isKeyDown(client.getWindow(), OPEN_KEY);
        if (down && !keyWasDown && client.screen == null && client.level != null) {
            client.setScreen(new EndsightScreen("Endsight", registry()));
        }
        keyWasDown = down;
    }
}
