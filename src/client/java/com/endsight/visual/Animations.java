package com.endsight.visual;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * The held item in first person: where it sits, how big it is, how fast and in what shape
 * it swings. Everything here is drawing only. The swing packet goes out exactly as before,
 * and nothing the server sees changes.
 *
 * The styles are the old 1.8 client ones. 1.7 and Exhibition are block-hit animations, and
 * swords cannot block any more. They first showed only while right click was held, when a
 * 1.8 sword would have been blocking; on a Hyperion that is also casting, and Fear never saw
 * them. Now they show while you swing, always; a toggle for the old way was asked away again.
 */
public final class Animations {
    private Animations() { }

    private static boolean on;
    private static String style = "Vanilla";
    private static final List<String> STYLES = List.of("Vanilla", "1.7", "Spin", "Stab", "Exhibition");
    private static double speed = 1, size = 1, side, height, depth;

    public static Module module() {
        return new Module("visual.animations", "Animations",
                "Swing style, speed and where your held item sits.", "Visual",
                () -> on, v -> on = v, List.of(
                new Setting.Choice("Style", "How the held item moves when you swing.", STYLES, () -> style, v -> style = v),
                // Down to a tenth: a 6 tick swing then takes three seconds. Not zero, which
                // would make the swing never end and leave the item frozen mid-arc.
                new Setting.Slider("Swing speed", "1 is normal.", 0.1, 3, 0.1, () -> speed, v -> speed = v, "x"),
                new Setting.Slider("Size", "How big the held item is.", 0.1, 2, 0.05, () -> size, v -> size = v, "x"),
                new Setting.Slider("Side", "Moves the held item left or right.", -1, 1, 0.05, () -> side, v -> side = v, ""),
                new Setting.Slider("Height", "Moves the held item up or down.", -1, 1, 0.05, () -> height, v -> height = v, ""),
                new Setting.Slider("Depth", "Moves the held item nearer or further.", -1, 1, 0.05, () -> depth, v -> depth = v, "")));
    }

    /**
     * The offset, applied before the arm puts the item in place, so it is in screen terms:
     * Side moves it across the screen and not along the blade. Mirrored for the off hand.
     */
    public static void place(PoseStack p, HumanoidArm arm) {
        if (!on || side == 0 && height == 0 && depth == 0) return;
        int s = arm == HumanoidArm.RIGHT ? 1 : -1;
        p.translate(s * (float) side, (float) height, (float) -depth);
    }

    /** Last, just before the item is drawn, so it grows about the grip and stays in the hand. */
    public static void size(PoseStack p) {
        if (on && size != 1) p.scale((float) size, (float) size, (float) size);
    }

    /**
     * Swing length in ticks. Vanilla's is 6, more with Mining Fatigue, less with Haste; this
     * scales whatever it is. Never under one tick, or the swing never shows at all.
     */
    public static int duration(int ticks) {
        return on && speed != 1 ? Math.max(1, Math.round(ticks / (float) speed)) : ticks;
    }

    /**
     * The swing for this frame, in place of vanilla's. Returns false to leave vanilla's.
     * Runs after the arm transform, with the item at the hand and no swing applied yet.
     */
    public static boolean swing(PoseStack p, HumanoidArm arm, float swing) {
        if (!on) return false;
        int s = arm == HumanoidArm.RIGHT ? 1 : -1;
        switch (style) {
            case "1.7" -> {
                float k = blockAmount(arm);
                if (k <= 0) return false;
                // 1.8 with the 1.7 setting: the normal swing arc, then the blocking pose on
                // top of it. The arc is what makes the blade punch forward and down while
                // blocking; 1.8 itself froze the blocking sword still.
                arc(p, s, swing);
                block(p, s, k);
            }
            case "Exhibition" -> {
                float k = blockAmount(arm);
                if (k <= 0) return false;
                // Exhibition's block-hit: lifted, then twisted out and back on a tilted axis
                // as the swing peaks, then the blocking pose. The rotations were written in
                // 1.8's held-item frame, which sat 45 degrees round from today's; the
                // outer pair puts them back in it.
                float v = Mth.sin(Mth.sqrt(swing) * Mth.PI);
                p.translate(0, 0.12f * k, 0);
                p.mulPose(Axis.YP.rotationDegrees(s * 45f));
                p.mulPose(new Quaternionf().rotationAxis(-v * 31f * Mth.DEG_TO_RAD, new Vector3f(1, 0, 2 * s).normalize()));
                p.mulPose(Axis.YP.rotationDegrees(s * -v * 33f));
                p.mulPose(Axis.YP.rotationDegrees(s * -45f));
                block(p, s, k);
            }
            // One full turn per swing about the upright through the hand. Turning about the
            // view axis, the first try, swung the blade round the grip and off the bottom of
            // the screen for most of the turn.
            case "Spin" -> p.mulPose(Axis.YP.rotationDegrees(s * -360f * swing));
            case "Stab" -> {
                // Straight out and back along the view, no arc: a thrust.
                float t = Mth.sin(swing * Mth.PI);
                p.translate(s * -0.08f * t, 0.06f * t, -0.5f * t);
                // The tip leans forward as it goes out, so it reads as a point going in
                // rather than an upright blade sliding away.
                p.mulPose(Axis.XP.rotationDegrees(-35f * t));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static float blend;
    private static long blendNs;

    /**
     * How far into the blocking pose the sword is, 0 to 1. It follows whether you are
     * swinging, eased over about 50 ms: in as the first swing starts, held while the clicks
     * keep coming, out after the last. It does not follow each swing's own progress, because
     * spam-clicking restarts the swing every couple of ticks, and the sword would flick out
     * of the pose and back on every click.
     */
    private static float blockAmount(HumanoidArm arm) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || arm != mc.player.getMainArm()) return 0;
        long now = System.nanoTime();
        float dt = Math.min((now - blendNs) / 1e9f, 0.1f);
        blendNs = now;
        float want = mc.player.swinging ? 1 : 0;
        blend += (want - blend) * Math.min(1f, dt * 20f);
        if (blend < 0.002f) blend = 0;
        return blend;
    }

    /** Vanilla's attack rotation, as ItemInHandRenderer has it. */
    private static void arc(PoseStack p, int s, float swing) {
        float f = Mth.sin(swing * swing * Mth.PI);
        p.mulPose(Axis.YP.rotationDegrees(s * (45f + f * -20f)));
        float g = Mth.sin(Mth.sqrt(swing) * Mth.PI);
        p.mulPose(Axis.ZP.rotationDegrees(s * g * -20f));
        p.mulPose(Axis.XP.rotationDegrees(g * -80f));
        p.mulPose(Axis.YP.rotationDegrees(s * -45f));
    }

    /**
     * The sword-blocking pose from 1.9 to 1.12, the last versions that drew one. Those
     * numbers were written for the same arm transform and sword model the game still uses,
     * so they land the blade across the screen as 1.8 players knew it. Scaled by k to ease
     * in and out of it: at 0 it is no change at all.
     */
    private static void block(PoseStack p, int s, float k) {
        p.translate(s * -0.14142136f * k, 0.08f * k, 0.14142136f * k);
        p.mulPose(Axis.XP.rotationDegrees(-102.25f * k));
        p.mulPose(Axis.YP.rotationDegrees(s * 13.365f * k));
        p.mulPose(Axis.ZP.rotationDegrees(s * 78.05f * k));
    }
}
