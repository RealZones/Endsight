package com.endsight.dragons;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

import java.util.List;

/** Zap's overall box or the dragon's individual part bounds, in the current menu colour. */
public final class DragonHitboxes {
    private DragonHitboxes() { }

    private static boolean enabled;
    private static final String SIMPLE = "Simple box";
    private static final String DETAILED = "Detailed hitboxes";
    private static String mode = SIMPLE;
    private static double width = 2;

    public static Module module() {
        return new Module("dragon.hitboxes", "Dragon Hitboxes",
                "Simple or detailed dragon-only boxes, using the menu theme colour.", "The End",
                () -> enabled, value -> enabled = value, List.of(
                        new Setting.Choice("Mode", "One overall box, or the separate head, neck, body, wing and tail hitboxes.",
                                List.of(SIMPLE, DETAILED), () -> mode, value -> mode = value),
                        new Setting.Slider("Line width", "Thickness of the hitbox edges.",
                                1, 4, .5, () -> width, value -> width = value, "px")));
    }

    public static void init() {
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw());
    }

    static void draw() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // F3+B already draws both kinds of box. Avoid drawing them twice in that mode.
        if (mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES)) return;

        boolean detailed = DETAILED.equals(mode);
        int colour = Theme.accent();
        GizmoStyle parts = GizmoStyle.stroke(colour, (float) width);
        // Keep the overall bounds as a faint reference in detailed mode so the
        // individual hit areas remain the clearest lines, without another colour.
        GizmoStyle overall = detailed ? GizmoStyle.stroke((colour & 0xFFFFFF) | 0x66000000, (float) width) : parts;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EnderDragon dragon) || dragon.isRemoved() || dragon.isInvisible()) continue;
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(!mc.level.tickRateManager().isEntityFrozen(dragon));
            outline(dragon, partial, overall);
            if (detailed) for (var part : dragon.getSubEntities()) outline(part, partial, parts);
        }
    }

    private static void outline(Entity entity, float partial, GizmoStyle style) {
        // Vanilla interpolates every part separately; the head and tail can move
        // independently of the parent's position as the dragon turns.
        var box = entity.getBoundingBox().move(entity.getPosition(partial).subtract(entity.position()));
        Gizmos.cuboid(box, style);
    }
}
