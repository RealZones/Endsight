package com.endsight;

import com.endsight.casino.HuffPuff;
import com.endsight.dragons.DragonTimer;
import com.endsight.dragons.Protector;
import com.endsight.hud.Alert;
import com.endsight.hud.Toast;
import com.endsight.hud.Alerts;
import com.endsight.hud.Area;
import com.endsight.hud.ServerStats;
import com.endsight.qol.AbilitySpam;
import com.endsight.qol.CommandBinds;
import com.endsight.qol.DamageNumbers;
import com.endsight.qol.DebugOnJoin;
import com.endsight.qol.CopyChat;
import com.endsight.qol.EyeGuard;
import com.endsight.qol.LootAlerts;
import com.endsight.qol.LootFilter;
import com.endsight.qol.MathSolver;
import com.endsight.qol.Updates;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import com.endsight.slayers.Slayer;
import com.endsight.slayers.VoidgloomHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import com.endsight.storage.SnapshotStore;
import com.endsight.storage.Recipes;
import com.endsight.storage.StoragePreview;
import com.endsight.storage.StorageSearch;
import com.endsight.visual.Beacon;
import com.endsight.zealots.ZealotTracker;
import com.endsight.ui.EndsightDemo;
import com.endsight.ui.Keybinds;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
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
    /** Which bound keys were down last tick, so a held key fires once. */
    private final java.util.Set<Integer> heldBinds = new java.util.HashSet<>();

    /** Built once so a toggle survives closing and reopening the screen. */
    public static ModuleRegistry registry() {
        if (registry == null) {
            registry = EndsightDemo.registry();
            // Real modules overwrite their placeholders here rather than in the demo
            // registry, so EndsightDemo stays a pure list of things that do nothing.
            registry.replace(StoragePreview.module());
            registry.replace(VoidgloomHelper.module());
            registry.replace(DragonTimer.module());
            registry.replace(Protector.module());
            registry.replace(Slayer.killTimerModule());
            registry.replace(HuffPuff.module());
            registry.replace(ZealotTracker.module());
            registry.replace(Alerts.module());
            registry.replace(EyeGuard.module());
            registry.replace(DamageNumbers.module());
            registry.replace(Beacon.module());
            registry.replace(LootAlerts.module());
            registry.replace(DebugOnJoin.module());
            registry.replace(LootFilter.module());
            registry.replace(AbilitySpam.module());
            registry.replace(CopyChat.module());
            registry.replace(Recipes.module());
            registry.replace(ServerStats.module());
            registry.replace(MathSolver.module());
            registry.replace(CommandBinds.module());
            extra("register", registry);
            Config.load(registry);
        }
        return registry;
    }

    /**
     * Another mod may add modules to this browser: if a class of this name is on the
     * classpath it is asked to register and initialise them. Looked up by name so this
     * compiles without it, and with it absent the lookup fails quietly and the mod is
     * simply what is in the repository.
     */
    private static void extra(String method, ModuleRegistry registry) {
        try {
            Class<?> hook = Class.forName("com.endsight.dev.Dev");
            if (registry != null) hook.getMethod(method, ModuleRegistry.class).invoke(null, registry);
            else hook.getMethod(method).invoke(null);
        } catch (ReflectiveOperationException ignored) {
            // Not built in.
        }
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        StoragePreview.init();
        SnapshotStore.init();
        StorageSearch.init();
        VoidgloomHelper.init();
        DragonTimer.init();
        Protector.init();
        EyeGuard.init();
        DamageNumbers.init();
        DebugOnJoin.init();
        LootFilter.init();
        AbilitySpam.init();
        CopyChat.init();
        Recipes.init();
        ServerStats.init();
        Area.init();
        LootAlerts.init();
        Alerts.init();
        MathSolver.init();
        Updates.init();
        Beacon.init();
        Slayer.init();
        HuffPuff.init();
        ZealotTracker.init();
        extra("init", null);

        // One popup for the whole mod, drawn last so it sits over every readout. Any
        // module can raise it; only one shows at a time, because two things shouting
        // at once is the same as neither.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "alert"),
                (g, delta) -> Alert.draw(g));

        // Last of all, so a toast sits over every readout including the popup above.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "toast"),
                (g, delta) -> Toast.draw(g));

        // Saved on exit, and again whenever an Endsight screen closes - quitting the
        // game is not the only way a session ends, and a crash after an hour of tuning
        // should not cost the hour.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Config.save(registry()));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof EndsightScreen || screen instanceof SettingsScreen) {
                ScreenEvents.remove(screen).register(s -> Config.save(registry()));
            }
        });

        // Build the registry now rather than on the first Right Shift, because building
        // it is what loads the config. Left lazy, every saved toggle and slider sat at
        // its default until the menu was first opened - a module saved on stayed off
        // all session and then came alive the moment the menu closed. After the inits
        // above, since restoring HUD positions needs the readouts registered first.
        registry();
    }

    private void onTick(Minecraft client) {
        if (client.getWindow() == null) return;

        // Polled rather than registered as a KeyMapping on purpose: a KeyMapping only
        // fires while no screen is open, and this has to be openable from the pause
        // menu later. The edge check is what stops one press opening it every tick.
        boolean down = InputConstants.isKeyDown(client.getWindow(), OPEN_KEY);
        if (down && !keyWasDown && client.screen == null && client.level != null) {
            // Back to wherever it was closed from: the browser as it was scrolled, or the
            // settings page that was open, with the browser behind it for Back.
            EndsightScreen browser = new EndsightScreen("Endsight", registry());
            String open = SettingsScreen.openModule();
            Module page = open == null ? null
                    : registry().all().stream().filter(m -> m.id().equals(open)).findFirst().orElse(null);
            client.setScreen(page == null ? browser : new SettingsScreen(browser, "Endsight", page));
        }
        keyWasDown = down;

        // Bound keys are polled here too, and only with no screen open - otherwise the
        // press that binds a key would also fire whatever it was just bound to.
        if (client.screen == null && client.level != null) fireBinds(client);
    }

    /**
     * Run whatever the bound keys are for, once per press.
     *
     * Walks the registry rather than keeping a second map of id to action: the ids are
     * built from the modules themselves, so a module renamed or removed cannot leave a
     * key pointing at something that is no longer there.
     */
    private void fireBinds(Minecraft client) {
        if (Keybinds.all().isEmpty()) return;
        java.util.Set<Integer> down = new java.util.HashSet<>();
        for (int key : Keybinds.all().values()) {
            if (Keybinds.isDown(client.getWindow(), key)) down.add(key);
        }
        for (Module m : registry().all()) {
            if (pressed(down, Keybinds.get(Keybinds.moduleId(m))) && m.implemented()) {
                m.toggle();
                Toast.toggled(m.title(), m.isEnabled());
            }
        }
        for (Setting.Command c : CommandBinds.all()) {
            if (pressed(down, Keybinds.get(c.id()))) CommandBinds.fire(c);
        }
        heldBinds.clear();
        heldBinds.addAll(down);
    }

    private boolean pressed(java.util.Set<Integer> down, int key) {
        return key != Keybinds.NONE && down.contains(key) && !heldBinds.contains(key);
    }

}
