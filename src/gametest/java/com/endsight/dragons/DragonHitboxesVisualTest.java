package com.endsight.dragons;

import com.endsight.EndsightClient;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.gizmos.CuboidGizmo;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.decoration.ArmorStand;

/** Both modes compared against vanilla geometry, menu colours and normal debug behavior. */
public final class DragonHitboxesVisualTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.NEVER);
                DragonHitboxes.module().setEnabled().accept(false);
                require(mode().get().get().equals("Simple box"), "new setting did not default to Simple box");
                require(mode().options().equals(java.util.List.of("Simple box", "Detailed hitboxes")), "mode options missing");
                var page = EndsightClient.registry().grouped("The End", "hitboxes").values().stream()
                        .flatMap(java.util.List::stream).findFirst().orElseThrow();
                require(page.id().equals("page.dragonTools"), "hitbox search did not find Dragon tools");
                Setting.Toggle control = page.settings().stream().filter(s -> s instanceof Setting.Toggle
                        && s.label().equals("Dragon Hitboxes")).map(s -> (Setting.Toggle) s).findFirst().orElseThrow();
                control.set().accept(true);
                require(DragonHitboxes.module().isEnabled(), "browser toggle is not wired to the renderer");
                control.set().accept(false);
            });
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var level = player.level();
                player.connection.teleport(player.getX(), player.getY() + 5, player.getZ(), 0, -12);
                player.setNoGravity(true);
                var dragon = new EnderDragon(EntityType.ENDER_DRAGON, level);
                dragon.setPos(player.getX(), player.getY() + 3, player.getZ() + 22);
                dragon.getPhaseManager().setPhase(EnderDragonPhase.HOVERING);
                level.addFreshEntity(dragon);
                var stand = new ArmorStand(level, player.getX() - 3, player.getY(), player.getZ() + 6);
                stand.setNoGravity(true);
                level.addFreshEntity(stand);
            });
            context.waitTicks(30);
            context.runOnClient(mc -> {
                DragonHitboxes.module().setEnabled().accept(true);
                verifyGeometry(mc);
                DragonHitboxes.module().setEnabled().accept(false);
            });
            // Keep comparison captures on exactly the same pose instead of showing
            // different wing/head animations between the custom and vanilla images.
            world.getServer().runOnServer(server -> server.tickRateManager().setFrozen(true));
            context.waitTicks(5);
            context.runOnClient(mc -> {
                mc.player.setNoGravity(true);
                mc.gui.getChat().clearMessages(false);
                require(collect().isEmpty(), "disabled feature emitted hitboxes");
                DragonHitboxes.module().setEnabled().accept(true);
                var dragon = dragon(mc);
                require(mc.level.getEntitiesOfClass(ArmorStand.class, mc.player.getBoundingBox().inflate(32)).size() == 1,
                        "non-dragon comparison entity missing");
                verifyGeometry(mc);
                var originalTheme = Theme.palette();
                try {
                    for (var palette : Theme.palettes()) {
                        Theme.set(palette);
                        verifyGeometry(mc);
                    }
                } finally {
                    Theme.set(originalTheme);
                }
                for (var option : mode().options()) {
                    mode().set().accept(option);
                    dragon.setInvisible(true);
                    require(collect().isEmpty(), option + " boxed an invisible dragon");
                    dragon.setInvisible(false);
                    mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.ALWAYS_ON);
                    require(collect().isEmpty(), option + " duplicated F3+B");
                    mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.NEVER);
                    require(collect().size() == (option.equals("Simple box") ? 1 : 9), option + " did not resume after F3+B");
                }
                // Exercise the actual config path, including the grouped browser's
                // backend owner, rather than merely checking a local mode variable.
                com.endsight.Config.save(EndsightClient.registry());
                mode().set().accept("Simple box");
                com.endsight.Config.load(EndsightClient.registry());
                require(mode().get().get().equals("Detailed hitboxes"), "mode did not survive config reload");
                mode().set().accept("Simple box");
                com.endsight.Config.save(EndsightClient.registry());
                require(!mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES), "feature enabled global hitboxes");
            });
            context.waitTicks(2);
            screenshot(context, "dragon-hitboxes-enabled");
            context.runOnClient(mc -> mode().set().accept("Detailed hitboxes"));
            context.waitTicks(2);
            screenshot(context, "dragon-hitboxes-detailed");
            context.runOnClient(mc -> DragonHitboxes.module().setEnabled().accept(false));
            context.waitTicks(2);
            screenshot(context, "dragon-hitboxes-disabled");
            context.runOnClient(mc -> {
                require(collect().isEmpty(), "turning off the feature left hitboxes behind");
                mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.ALWAYS_ON);
            });
            context.waitTicks(2);
            screenshot(context, "dragon-hitboxes-vanilla");
            context.runOnClient(mc -> {
                mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.NEVER);
                mode().set().accept("Simple box");
                com.endsight.Config.save(EndsightClient.registry());
            });
            System.out.println("DRAGON HITBOXES CLIENT CHECKS PASSED: both modes, exact vanilla outer/part bounds moving and frozen, all menu themes, config persistence, dragon-only, depth, toggle, search, invisible and F3+B coexistence");
        }
    }

    private static EnderDragon dragon(Minecraft mc) {
        for (var entity : mc.level.entitiesForRendering()) if (entity instanceof EnderDragon dragon) return dragon;
        throw new AssertionError("server dragon did not reach client");
    }

    private static java.util.List<SimpleGizmoCollector.GizmoInstance> collect() {
        var collector = new SimpleGizmoCollector();
        try (var scope = Gizmos.withCollector(collector)) { DragonHitboxes.draw(); }
        return collector.getGizmos();
    }

    private static Setting.Choice mode() {
        return DragonHitboxes.module().settings().stream().filter(setting -> setting instanceof Setting.Choice)
                .map(setting -> (Setting.Choice) setting).findFirst().orElseThrow();
    }

    private static void verifyGeometry(Minecraft mc) {
        var dragon = dragon(mc);
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(!mc.level.tickRateManager().isEntityFrozen(dragon));
        var vanilla = nativeBoxes(mc, dragon, partial);
        // Vanilla's white outer and red eye-height boxes are distinct from the
        // green damageable parts. The eye band and look arrow are debug decoration.
        var parts = vanilla.stream().filter(box -> box.style().stroke() != 0xFFFFFFFF
                && box.style().stroke() != 0xFFFF0000).toList();
        require(parts.size() == 8, "vanilla reference did not expose eight dragon parts");
        for (var option : mode().options()) {
            mode().set().accept(option);
            var expected = new java.util.ArrayList<net.minecraft.world.phys.AABB>();
            expected.add(vanilla.getFirst().aabb());
            boolean detailed = option.equals("Detailed hitboxes");
            if (detailed) parts.forEach(box -> expected.add(box.aabb()));
            var actual = collect();
            require(actual.size() == expected.size(), option + " has extra or missing boxes");
            for (int i = 0; i < actual.size(); i++) {
                var box = (CuboidGizmo) actual.get(i).gizmo();
                require(box.aabb().equals(expected.get(i)), option + " differs from native box " + i);
                require(!actual.get(i).isAlwaysOnTop() && !box.style().hasFill(), "unexpected x-ray or fill");
                require(box.style().strokeWidth() == 2, "unexpected line width");
                int alpha = detailed && i == 0 ? 0x66000000 : 0xFF000000;
                require(box.style().stroke() == ((Theme.accent() & 0xFFFFFF) | alpha), option + " did not follow theme colour");
            }
        }
        mode().set().accept("Simple box");
    }

    private static java.util.List<CuboidGizmo> nativeBoxes(Minecraft mc, EnderDragon dragon, float partial) {
        var collector = new SimpleGizmoCollector();
        try (var scope = Gizmos.withCollector(collector)) {
            var renderer = new net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer(mc);
            var draw = renderer.getClass().getDeclaredMethod("showHitboxes", net.minecraft.world.entity.Entity.class,
                    float.class, boolean.class);
            draw.setAccessible(true);
            draw.invoke(renderer, dragon, partial, false);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not compare against vanilla hitbox renderer", failure);
        }
        return collector.getGizmos().stream().map(SimpleGizmoCollector.GizmoInstance::gizmo)
                .filter(gizmo -> gizmo instanceof CuboidGizmo).map(gizmo -> (CuboidGizmo) gizmo).toList();
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        var saved = new java.util.concurrent.CompletableFuture<Void>();
        context.runOnClient(mc -> {
            // The test API renders screenshots during a tick, without Minecraft's
            // per-frame collector. Its default capture therefore drops even F3+B.
            try (var scope = mc.levelRenderer.collectPerFrameGizmos()) {
                mc.gameRenderer.extract(net.minecraft.client.DeltaTracker.ONE, true);
                mc.gameRenderer.render(net.minecraft.client.DeltaTracker.ONE, true);
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                    try (image) {
                        var path = mc.gameDirectory.toPath().resolve("screenshots/" + name + ".png");
                        java.nio.file.Files.createDirectories(path.getParent());
                        image.writeToFile(path);
                        saved.complete(null);
                    } catch (Exception failure) { saved.completeExceptionally(failure); }
                });
            }
        });
        context.waitFor(mc -> saved.isDone());
        saved.join();
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
