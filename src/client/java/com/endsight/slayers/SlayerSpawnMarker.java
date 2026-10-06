package com.endsight.slayers;

import com.endsight.dragons.DragonTimer;
import com.endsight.hud.Alert;
import com.endsight.hud.Project;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Where your slayer boss is about to land: a ring, an arrow and a countdown at the spot.
 *
 * It reads the spawn burst as it happens and keeps nothing on disk.
 */
public final class SlayerSpawnMarker {
    private SlayerSpawnMarker() { }

    private static boolean enabled = true;
    private static boolean hideParticles = true;
    /** Bursts further off than this are someone else's boss. */
    private static final double RANGE_SQR = 30 * 30;

    public static Module module() {
        return new Module("slayer.spawnMarker", "Slayer Spawn Marker",
                "Marks where your slayer boss will spawn.", "Visual",
                () -> enabled, v -> {
                    enabled = v;
                    if (!v) clear();
                },
                List.of(new Setting.Toggle("Hide spawn particles", "Keep the spawn burst from covering the marker.",
                                () -> hideParticles, v -> hideParticles = v),
                        new Setting.Action("Preview", "Show the marker near you for ten seconds.",
                                "Preview", SlayerSpawnMarker::preview)));
    }

    /**
     * The marker's colours come from the theme accent, read each frame. They were a fixed
     * neon pink; Fear asked for them to follow the theme he has set, like every other
     * world overlay in the mod. The light and deep shades are the accent mixed toward white
     * and black, so any palette keeps the same lit edges and shaded faces.
     */
    private static int main() { return 0xFF000000 | (Theme.accent() & 0x00FFFFFF); }

    /**
     * The accent as a neon tube would show it: its hue at full brightness and strong
     * saturation. A muted theme accent drawn as-is read as a dull line on the ground, not
     * the glowing ring of the Nebula reference; the hue is the theme's, the glow is neon's.
     */
    private static int neon() { return neon(0f); }

    /** The neon accent with its hue turned by {@code shift} (a fraction of the wheel). */
    private static int neon(float shift) {
        int c = main();
        float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, b = (c & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        if (d < 1e-4f) return 0xFFFFFFFF;                // a grey accent has no hue to push
        float h = max == r ? ((g - b) / d + (g < b ? 6 : 0)) : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        h = ((h / 6f + shift) % 1f + 1f) % 1f;
        float sat = Math.max(0.82f, d / max);
        float[] rgb = new float[3];
        for (int i = 0; i < 3; i++) {
            float k = (5 - i * 2 + h * 6) % 6;             // HSV to RGB, channel by channel
            rgb[i] = 1 - sat * Math.max(0, Math.min(Math.min(k, 4 - k), 1));
        }
        return 0xFF000000 | Math.round(rgb[0] * 255) << 16 | Math.round(rgb[1] * 255) << 8 | Math.round(rgb[2] * 255);
    }

    private static final long FADE_NS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long PREDICT_NS = TimeUnit.MILLISECONDS.toNanos(1300);
    private static final long DETECT_NS = TimeUnit.MILLISECONDS.toNanos(35);
    private static final long DETECT_TIMEOUT_NS = TimeUnit.MILLISECONDS.toNanos(220);
    private static final SlayerSpawnSignal SIGNAL = new SlayerSpawnSignal();
    private static Vec3 ground;
    private static ClientLevel level;
    private static long shownNs;
    private static long deadlineNs;
    private static long armedNs;
    private static ClientLevel armedLevel;

    public static void init() {
        // Only your own boss sends this line, so it needs no other gate: Crypts, Void
        // Sepulture or the Rift, a warning is a warning.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled) return;
            String line = Zealots.strip(message.getString()).trim();
            if (DragonTimer.isPlayerChat(line) || !line.contains("SLAYER BOSS SPAWNING")) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) arm(System.nanoTime(), mc.level);
        });
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> render());
        ClientTickEvents.END_CLIENT_TICK.register(SlayerSpawnMarker::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "slayer_spawn_marker"),
                (graphics, delta) -> drawTimer(graphics));
    }

    static void arm(long warningNs, ClientLevel level) {
        ground = null;
        armedNs = warningNs;
        armedLevel = level;
        SIGNAL.arm(warningNs);
    }

    /**
     * Every particle the client is about to make, from the particle hook: seen first, then
     * hidden only if it is the burst under the marker. True means do not make it.
     */
    public static boolean particle(ParticleOptions particle, double x, double y, double z) {
        if (!enabled || (armedNs == 0 && ground == null)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.distanceToSqr(x, y, z) > RANGE_SQR) return false;
        long now = System.nanoTime();
        String type = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType()));
        if (armedNs != 0) SIGNAL.observe(now, type, x, y, z);
        return hideParticles && hideParticle(now, type, x, y, z);
    }

    private static boolean hideParticle(long at, String type, double x, double y, double z) {
        if (!spawnParticle(type)) return false;
        if (armedNs != 0 && at - armedNs >= 0 && at - armedNs <= DETECT_TIMEOUT_NS) return true;
        if (ground == null || at > deadlineNs || Minecraft.getInstance().level != level) return false;
        double dx = x - ground.x, dz = z - ground.z;
        return dx * dx + dz * dz <= 2.8 * 2.8 && Math.abs(y - ground.y) <= 3.5;
    }

    private static boolean spawnParticle(String type) {
        return type.equals("minecraft:enchant") || type.equals("minecraft:portal")
                || type.equals("minecraft:witch");
    }

    static void clear() {
        armedNs = 0;
        if (ground != null) deadlineNs = Math.min(deadlineNs, System.nanoTime());
    }

    private static void tick(Minecraft mc) {
        if (ground != null && mc.level == level && System.nanoTime() < deadlineNs && bossArrived(mc)) clear();
        if (armedNs == 0) return;
        long now = System.nanoTime();
        long elapsed = now - armedNs;
        if (mc.level != armedLevel || elapsed > DETECT_TIMEOUT_NS) {
            armedNs = 0;
            return;
        }
        if (elapsed < DETECT_NS) return;
        SlayerSpawnSignal.Position position = SIGNAL.estimate();
        if (position == null) return;
        ground = new Vec3(position.x(), position.y(), position.z());
        level = armedLevel;
        shownNs = armedNs;
        deadlineNs = armedNs + PREDICT_NS;
        RevenantSpawnTiles.onBurst(ground, armedLevel);
        armedNs = 0;
    }

    /** The boss has landed on the marker: its name is up within three blocks of it, so the countdown is done. */
    private static boolean bossArrived(Minecraft mc) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.distanceToSqr(ground) > 9) continue;
            String n = e instanceof Display.TextDisplay d ? d.getText().getString()
                    : e.getCustomName() == null ? "" : e.getCustomName().getString();
            n = Zealots.strip(n);
            if (n.contains("Horror") || n.contains("Seraph")) return true;
        }
        return false;
    }

    /** Manual preview, independent of the burst detector. */
    public static void show(Vec3 position, double seconds) {
        Minecraft mc = Minecraft.getInstance();
        if (position == null || mc.level == null || !Double.isFinite(seconds) || seconds <= 0) return;
        armedNs = 0;
        ground = position;
        level = mc.level;
        shownNs = System.nanoTime();
        deadlineNs = shownNs + (long) (seconds * 1_000_000_000L);
    }

    public static void preview() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Vec3 look = mc.player.getLookAngle();
        double length = Math.hypot(look.x, look.z);
        if (length < 0.1) return;
        Vec3 player = mc.player.position();
        show(new Vec3(player.x + look.x / length * 3, player.y + 0.03,
                player.z + look.z / length * 3), 10);
    }

    private static boolean visible(Minecraft mc, long now) {
        if (ground == null) return false;
        if (mc.level != level || now >= deadlineNs + FADE_NS) {
            ground = null;
            return false;
        }
        return mc.player != null && !mc.options.hideGui;
    }

    private static int tint(int rgb, int alpha, double fade) {
        return ((int) Math.round(alpha * fade) << 24) | (rgb & 0x00FFFFFF);
    }

    private static final int SEGMENTS = 48;

    private static void ring(Vec3 center, double radius, double start, double sweep,
                             int color, float width) {
        int steps = Math.max(2, (int) Math.ceil(SEGMENTS * sweep / (Math.PI * 2)));
        Vec3 last = point(center, radius, start);
        for (int i = 1; i <= steps; i++) {
            Vec3 next = point(center, radius, start + sweep * i / steps);
            Gizmos.line(last, next, color, width);
            last = next;
        }
    }

    /** Neon in four passes: a wide faint bloom, a tighter glow, the saturated tube, a near-white core. */
    private static void neonRing(Vec3 center, double radius, int neon, double fade, float tube) {
        int hot = Draw.lerp(neon, 0xFFFFFFFF, 0.62f);
        ring(center, radius, 0, Math.PI * 2, tint(neon, 14, fade), tube * 5.2f);
        ring(center, radius, 0, Math.PI * 2, tint(neon, 34, fade), tube * 3.0f);
        ring(center, radius, 0, Math.PI * 2, tint(neon, 255, fade), tube);
        ring(center, radius, 0, Math.PI * 2, tint(hot, 235, fade), tube * 0.38f);
    }

    private static void line(Vec3 from, Vec3 to, double fade, int rgb) {
        Gizmos.line(from, to, tint(rgb, 18, fade), 12f);
        Gizmos.line(from, to, tint(rgb, 46, fade), 6.5f);
        Gizmos.line(from, to, tint(rgb, 250, fade), 2.6f);
        Gizmos.line(from, to, tint(Draw.lerp(rgb, 0xFFFFFFFF, 0.62f), 230, fade), 1f);
    }

    private static Vec3 point(Vec3 center, double radius, double angle) {
        return center.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }

    /**
     * The arrow's outline from top to bottom, as (height above the ring, radius): a flat
     * cap, a long thin shaft, a flat shoulder and a small cone ending in a sharp point.
     *
     * Clean and sharp on purpose. The version before had a domed cap, bevels and a rounded
     * tip on a head three and a half times the shaft's width - Fear's word was that the tip
     * was way too big for the long part. The head is now under three times the shaft and
     * about half its length. Its tip stays clear of the timer just above the ring, even at
     * the bottom of its bob.
     */
    private static final double[][] PROFILE = {
            {1.35, 0.000}, {1.35, 0.070}, {0.80, 0.070}, {0.80, 0.190}, {0.50, 0.000}};
    /**
     * The timer's height: just above the ring, under the arrow's tip. It was at neck height
     * (1.55) and before that 2.17; Fear wanted it lower, where you are already looking when
     * you watch the ring, so it is easier to read.
     */
    private static final double TIMER_Y = 0.22;
    private static final int SIDES = 28;
    /** Light from above and a little in front: lit tops, shaded undersides. */
    private static final double LX = -0.35, LY = 0.82, LZ = -0.45;

    private record Face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int colour, double depth) { }

    /**
     * A smooth 3D arrow pointing down at the ring, built as a solid of revolution from
     * PROFILE and shaded per face against a fixed light, in the ring's own sweeping colour.
     *
     * Drawn on top of everything so mobs never hide it, which means no depth test - so faces
     * turned away from the camera are dropped and the rest drawn far to near, the way a
     * depth buffer would have settled them. A slightly larger, faint shell goes down first
     * as the glow round its edge, the arrow's version of the ring's soft outer band.
     */
    private static void arrow(Minecraft mc, Vec3 base, double fade, java.util.function.DoubleFunction<Integer> sweep) {
        Vec3 eye = mc.gameRenderer.getMainCamera().position();
        java.util.List<Face> glow = new java.util.ArrayList<>(), solid = new java.util.ArrayList<>();
        double ll = Math.sqrt(LX * LX + LY * LY + LZ * LZ);
        for (int shell = 0; shell < 2; shell++) {
            double grow = shell == 0 ? 0.03 : 0;
            for (int p = 0; p + 1 < PROFILE.length; p++) {
                double y0 = PROFILE[p][0], r0 = PROFILE[p][1], y1 = PROFILE[p + 1][0], r1 = PROFILE[p + 1][1];
                double nr = -(y1 - y0), ny = r1 - r0, nl = Math.hypot(nr, ny);
                if (nl < 1e-9) continue;
                nr /= nl; ny /= nl;
                double g0 = r0 + grow * (r0 > 0 ? 1 : 0.6), g1 = r1 + grow * (r1 > 0 ? 1 : 0.6);
                double yy0 = y0 + (p == 0 ? grow : 0), yy1 = y1 - (p + 2 == PROFILE.length ? grow : 0);
                for (int i = 0; i < SIDES; i++) {
                    double t0 = Math.PI * 2 * i / SIDES, t1 = Math.PI * 2 * (i + 1) / SIDES, tm = (t0 + t1) / 2;
                    double nx = nr * Math.cos(tm), nz = nr * Math.sin(tm);
                    Vec3 mid = base.add(Math.cos(tm) * (g0 + g1) / 2, (yy0 + yy1) / 2, Math.sin(tm) * (g0 + g1) / 2);
                    Vec3 toEye = eye.subtract(mid);
                    if (nx * toEye.x + ny * toEye.y + nz * toEye.z <= 0) continue;   // facing away
                    Vec3 a = base.add(Math.cos(t0) * g0, yy0, Math.sin(t0) * g0);
                    Vec3 b = base.add(Math.cos(t1) * g0, yy0, Math.sin(t1) * g0);
                    Vec3 c = base.add(Math.cos(t1) * g1, yy1, Math.sin(t1) * g1);
                    Vec3 d = base.add(Math.cos(t0) * g1, yy1, Math.sin(t0) * g1);
                    int hue = sweep.apply(tm);
                    int colour;
                    if (shell == 0) {
                        colour = (30 << 24) | (hue & 0xFFFFFF);
                    } else {
                        double lit = Math.max(0, (nx * LX + ny * LY + nz * LZ) / ll);
                        double rim = 1 - Math.abs(nx * toEye.x + ny * toEye.y + nz * toEye.z) / toEye.length();
                        int shaded = Draw.lerp(Draw.lerp(hue, 0xFF000000, 0.42f), Draw.lerp(hue, 0xFFFFFFFF, 0.5f),
                                (float) Math.min(1, 0.18 + 0.82 * lit));
                        // a bright edge where the surface turns away: the neon glow at its outline
                        colour = 0xFF000000 | (Draw.lerp(shaded, Draw.lerp(hue, 0xFFFFFFFF, 0.35f), (float) Math.pow(rim, 3)) & 0xFFFFFF);
                    }
                    (shell == 0 ? glow : solid).add(new Face(a, b, c, d, colour, toEye.lengthSqr()));
                }
            }
        }
        java.util.Comparator<Face> farFirst = java.util.Comparator.comparingDouble(Face::depth).reversed();
        glow.sort(farFirst);
        solid.sort(farFirst);
        Gizmos.addGizmo((primitives, opacity) -> {
            for (java.util.List<Face> layer : java.util.List.of(glow, solid)) {
                for (Face f : layer) {
                    int col = tint(f.colour(), (int) ((f.colour() >>> 24) * fade * opacity), 1);
                    primitives.addQuad(f.a(), f.b(), f.c(), f.d(), col);
                    primitives.addQuad(f.d(), f.c(), f.b(), f.a(), col);
                }
            }
        }).setAlwaysOnTop();
    }

    private static void render() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (!visible(mc, now)) return;
        double fade = Math.min(1, (deadlineNs + FADE_NS - now) / (double) FADE_NS);
        double elapsed = (now - shownNs) / 1e9;
        double pulse = 0.5 + 0.5 * Math.sin(elapsed * 6.5);
        Vec3 base = ground.add(0, 0.02, 0);
        int neon = neon();

        // The hue drifts round the ring between the accent and its neighbour a twelfth of the
        // wheel away - the two-tone sweep of Nebula's wormhole ring, in the theme's own colour.
        int partner = neon(1f / 12f);
        double spin = elapsed * 1.6;
        java.util.function.DoubleFunction<Integer> sweep = a ->
                Draw.lerp(neon, partner, (float) (0.5 + 0.5 * Math.cos(a - spin)));
        // The glowing neon line ring. A flat band lying on the ground was tried after it and
        // Fear put the two side by side in game: this one was "10x better".
        // Glow spilling onto the ground inside the ring, brightest at the tube and fading in.
        Gizmos.circle(base, 0.83f, GizmoStyle.fill(tint(neon, 16, fade)));
        ring(base.add(0, 0.003, 0), 0.76, 0, Math.PI * 2, tint(neon, 46, fade), 5f);
        ring(base.add(0, 0.003, 0), 0.66, 0, Math.PI * 2, tint(neon, 22, fade), 5f);
        neonRing(base.add(0, 0.006, 0), 0.83, neon, fade, 4.4f);
        ring(base.add(0, 0.012, 0), 0.83, elapsed * 0.75, Math.PI * 0.40,
                tint(0xFFFFFFFF, (int) (150 + pulse * 90), fade), 2.4f);
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2;
            line(point(base, 0.97, angle), point(base, 1.11, angle), fade * 0.8, neon);
        }

        arrow(mc, ground.add(0, Math.sin(elapsed * 4.4) * 0.10, 0), fade, sweep);
        tracer(mc, base, fade, neon);
    }

    /**
     * A line from just under the crosshair to the ring, for a burst that lands off to one
     * side or behind you. Starts where the mod's other tracers start - a little ahead of the
     * eye and below it, so it reads as rising from the bottom of the screen rather than
     * stabbing out of the crosshair - and lasts only as long as the countdown. Left out
     * when the ring is at your feet, where a line would only point straight down.
     */
    private static void tracer(Minecraft mc, Vec3 target, double fade, int neon) {
        var cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.position();
        double dx = target.x - eye.x, dz = target.z - eye.z;
        if (dx * dx + dz * dz < 2.5 * 2.5) return;
        var f = cam.forwardVector();
        Vec3 from = eye.add(f.x() * 0.8, f.y() * 0.8 - 0.45, f.z() * 0.8);
        int hot = Draw.lerp(neon, 0xFFFFFFFF, 0.62f);
        Gizmos.line(from, target, tint(neon, 16, fade), 11f).setAlwaysOnTop();
        Gizmos.line(from, target, tint(neon, 44, fade), 5.5f).setAlwaysOnTop();
        Gizmos.line(from, target, tint(neon, 250, fade), 2.2f).setAlwaysOnTop();
        Gizmos.line(from, target, tint(hot, 220, fade), 0.9f).setAlwaysOnTop();
    }

    private static void drawTimer(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (!visible(mc, now)) return;
        double[] screen = Project.toScreen(ground.add(0, TIMER_Y, 0),
                graphics.guiWidth(), graphics.guiHeight());
        if (screen == null) return;
        double x = screen[0], y = screen[1];
        double middleY = graphics.guiHeight() / 2.0;
        if (Alert.active() && Math.abs(x - graphics.guiWidth() / 2.0) < 170
                && y > middleY - 85 && y < middleY + 75) {
            y = y < middleY ? middleY - 94 : middleY + 86;
        }
        if (x < 24 || x > graphics.guiWidth() - 24
                || y < 18 || y > graphics.guiHeight() - 18) return;
        double remaining = Math.max(0, (deadlineNs - now) / 1e9);
        int tenths = (int) Math.ceil(remaining * 10);
        String label = (tenths / 10) + "." + (tenths % 10) + "s";
        double fade = Math.min(1, (deadlineNs + FADE_NS - now) / (double) FADE_NS);
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate((float) x, (float) y);
        pose.scale(1.4f, 1.4f);
        graphics.text(mc.font, label, -mc.font.width(label) / 2, -mc.font.lineHeight / 2,
                tint(0xFFFFFFFF, 255, fade), true);
        pose.popMatrix();
    }
}
