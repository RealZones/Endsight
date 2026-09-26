package com.endsight.slayers;

import com.endsight.hud.Alert;
import com.endsight.hud.Alerts;
import com.endsight.hud.HudLayout;
import com.endsight.hud.Project;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Voidgloom fight: the boss's numbers on screen, a mark on the boss, a mark on the
 * Yang Glyph.
 *
 * The marks are gizmos - the game's own 3D debug shapes, added fresh every frame from
 * Fabric's BEFORE_GIZMOS - so they sit in the world with depth and thickness. The first
 * version projected corners to the screen and drew with fill, and a flat box over a
 * moving boss read as a HUD glitch rather than an outline.
 *
 * The Nukekubi heads are marked too, box filled solid and a tracer to each, but only
 * while your own boss is up: no head has ever been caught in an entity dump, so they
 * are taken to be what a head is on every server that has copied this fight - an
 * armour stand wearing a player head - or anything named for them. Outside your fight
 * nothing is looked for, which is what keeps a decorative head in the End from
 * being marked as a threat.
 *
 * Radiation beams come from invisible guardians aimed at invisible stands. Their
 * actual endpoints define the lines; normal boss sparkle must never define a beam.
 */
public final class VoidgloomHelper {

    private VoidgloomHelper() {
    }

    private static boolean enabled = false;
    private static boolean onScreen = true;
    private static boolean highlight = true;
    private static boolean heads = true;
    private static boolean beams = true;
    private static boolean themeBeams = true;
    private static int beamColor = 0xFFFF26CB;
    private static double beamWidth = 4.5;
    private static boolean radiationTimer = true;
    private static boolean bossRadiating;
    private static final long RADIATION_NS = 8_200_000_000L;
    private static long radiationStarted;
    private static boolean radiationVehicleSeen;
    /**
     * The beam phase opens with "BROKEN HEART RADIATION! Stay within 15 blocks and dodge
     * the beams!", once per phase. Not anchored: the line arrives behind a skull icon and
     * two spaces, which stripping the colour codes leaves in place.
     */
    private static final Pattern BEAMS = Pattern.compile("BROKEN HEART RADIATION");
    private static final double HEAD_RANGE = 40;

    /** Yang Glyph: the beacon the boss drops at your feet from tier 2, with five seconds to reach it. */
    private static boolean glyph = true;
    private static final int GLYPH_RANGE = 12;
    private static final List<BlockPos> beacons = new ArrayList<>();

    /**
     * The boss, read off its own name tag: "Voidgloom Seraph IV 817.8M/2.5B<3 57 Hits".
     * The hits are only there while the hitshield is up, which is exactly when they are
     * the number you need and the hardest to read in the crowd - so they go on screen
     * big, and the health takes their place the rest of the fight.
     */
    private static final Pattern BOSS = Pattern.compile(
            "Voidgloom Seraph (\\S+)\\s+([\\d.,]+[kKmMbB]?)/([\\d.,]+[kKmMbB]?)❤(?:\\s+(\\d+) Hits?)?");
    private static Entity bossEntity;
    private static String bossHp = "", bossMax = "", bossTier = "";
    private static int bossHits = -1;
    private static double bossDist = -1;
    private static long bossSeen;

    public static Module module() {
        return new Module("slayer.boss", "Voidgloom Helper",
                "Hits, phase and health display. Boss and glyph highlight and tracer.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Section("Readout"),
                        new Setting.Toggle("On screen",
                                "Shield hits left, then boss health.",
                                () -> onScreen, v -> onScreen = v),
                        new Setting.Toggle("Radiation timer",
                                "Seconds left above your Voidgloom boss.",
                                () -> radiationTimer, v -> radiationTimer = v),
                        new Setting.Section("Highlights"),
                        new Setting.Toggle("Highlight boss",
                                "Box and line to your boss.",
                                () -> highlight, v -> highlight = v),
                        new Setting.Toggle("Highlight glyph",
                                "Box and line to the Yang Glyph.",
                                () -> glyph, v -> glyph = v),
                        new Setting.Toggle("Highlight Nukekubi",
                                "Box and line to each Nukekubi head.",
                                () -> heads, v -> heads = v),
                        new Setting.Section("Radiation beams"),
                        new Setting.Toggle("Beams alert",
                                "Highlight radiation beams and alert when they start.",
                                () -> beams, v -> beams = v),
                        new Setting.Toggle("Use theme color",
                                "Match the beams to your Endsight accent.",
                                () -> themeBeams, v -> themeBeams = v),
                        new Setting.Color("Beam color",
                                "Used when theme color is off.",
                                () -> beamColor, v -> beamColor = v),
                        new Setting.Slider("Beam width", "Thickness of the radiation beams.",
                                2, 8, 0.5, () -> beamWidth,
                                v -> beamWidth = Double.isFinite(v) ? Mth.clamp(v, 2, 8) : 4.5, "px")));
    }

    public static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "voidgloom"),
                (g, delta) -> draw(g));
        // A gizmo lives one frame, so the marks are added again every frame, just
        // before the game draws them.
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> gizmos());
        ClientTickEvents.END_CLIENT_TICK.register(VoidgloomHelper::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled) return;
            if (!BEAMS.matcher(message.getString()).find()) return;
            if (Slayer.bossUp()) {
                radiationStarted = System.nanoTime();
                radiationVehicleSeen = false;
            }
            if (!beams) return;
            Alert.show("BEAMS", "stay within 15 blocks", beamTint(), 1.15f, Alerts.seconds());
            Minecraft mc = Minecraft.getInstance();
            if (Alerts.sound() && mc.player != null) {
                mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1.6f);
            }
        });
        HudLayout.register("slayer.voidgloom", "Voidgloom Boss", 0.5f, 0.2f,
                (g, font, x, y, sample) -> drawBoss(g, font, x, y, sample));
    }

    /**
     * The big number: hits while the shield is up, health otherwise, tier and distance
     * under it. Distance goes red past fifteen blocks, which is the radiation rule.
     */
    private static int[] drawBoss(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        boolean shield = sample || bossHits >= 0;
        String big = sample ? "57 HITS" : shield ? bossHits + " HITS" : bossHp + " / " + bossMax;
        String small = sample ? "Seraph IV  -  9m" : "Seraph " + bossTier + "  -  " + Math.round(bossDist) + "m";
        float scale = 2.5f;
        int w = (int) Math.max(font.width(big) * scale, font.width(small)) + 8;
        int h = (int) (9 * scale) + 14;
        if (g != null) {
            int colour = shield ? 0xFF55FFFF : 0xFFFF5555;
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(x + w / 2f, y);
            pose.scale(scale, scale);
            Draw.textCentered(g, font, big, 0, 0, colour);
            pose.popMatrix();
            boolean far = !sample && bossDist > 15;
            Draw.textCentered(g, font, small, x + w / 2, y + (int) (9 * scale) + 4,
                    far ? 0xFFFF5555 : Theme.muted());
        }
        return new int[]{w, h};
    }

    private static void tick(Minecraft mc) {
        readBoss(mc);
        scanBeacons(mc);
    }

    /**
     * Your boss, and only yours.
     *
     * The name tag carries no owner - the dump shows "☠ Voidgloom Seraph IV 2.5B/2.5B❤
     * Radiation", nothing like the "(Fear's soul)" the devotees get - so ownership
     * cannot be read off the entity. What can be read is timing and place: your boss
     * spawns at your feet in the second after YOUR "SLAYER BOSS SPAWNING" line, which
     * nobody else's chat prints. So while your quest has a boss up, the nearest Seraph
     * within twenty blocks is taken as yours and then HELD by entity for the rest of
     * the fight, rather than re-picked every tick - re-picking is how the first version
     * wandered onto whoever's boss walked closest. If the server re-sends the entity
     * mid-fight it is picked up again from where the old one last stood.
     */
    private static final double CLAIM_RANGE = 20;
    private static Vec3 bossPos;

    private static void readBoss(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null || !Slayer.bossUp()) {
            bossEntity = null;
            bossPos = null;
            bossSeen = 0;
            radiationStarted = 0;
            radiationVehicleSeen = false;
            bossRadiating = false;
            return;
        }
        bossRadiating = false;
        Entity e = bossEntity;
        if (e == null || e.isRemoved() || e.level() != mc.level) e = claim(mc, bossPos == null ? mc.player.position() : bossPos);
        if (e == null) return;
        String plain = plainName(e);
        if (plain == null) return;
        Matcher m = BOSS.matcher(plain);
        if (!m.find()) return;
        bossTier = m.group(1);
        bossRadiating = plain.contains("Radiation");
        bossHp = m.group(2);
        bossMax = m.group(3);
        bossHits = m.group(4) == null ? -1 : Integer.parseInt(m.group(4));
        bossDist = e.position().distanceTo(mc.player.position());
        bossEntity = e;
        bossPos = e.position();
        bossSeen = System.currentTimeMillis();
    }

    /** The nearest Seraph to a point, within claiming range, or null. */
    private static Entity claim(Minecraft mc, Vec3 near) {
        Entity best = null;
        double bestD = CLAIM_RANGE * CLAIM_RANGE;
        for (Entity e : mc.level.entitiesForRendering()) {
            String plain = plainName(e);
            if (plain == null || !plain.contains("Voidgloom Seraph")) continue;
            double d = e.position().distanceToSqr(near);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * Beacons within a dozen blocks, once a tick. The glyph lands at your feet and is
     * a plain beacon block; scanning a small cube each tick is cheap, and once a frame
     * would not be.
     */
    private static void scanBeacons(Minecraft mc) {
        beacons.clear();
        // Only while a boss is up. The dragon leaves a beacon on its loot too, and the
        // first version marched the glyph box over to that instead.
        if (!enabled || !glyph || !Slayer.bossUp() || mc.player == null || mc.level == null) return;
        BlockPos at = mc.player.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(at.offset(-GLYPH_RANGE, -6, -GLYPH_RANGE),
                at.offset(GLYPH_RANGE, 6, GLYPH_RANGE))) {
            if (mc.level.getBlockState(b).is(Blocks.BEACON)) beacons.add(b.immutable());
        }
    }

    /** Display name with the server's literal section codes taken out, or null. */
    private static String plainName(Entity e) {
        Component name = e.getCustomName();
        if (name == null) name = e.getDisplayName();
        if (name == null) return null;
        return name.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "");
    }

    // ── in the world ──────────────────────────────────────────────────────────

    /** Where a tracer starts: a little ahead of and below the eye, so it reads as rising from the bottom of the screen. */
    private static Vec3 tracerFrom(Minecraft mc) {
        var cam = mc.gameRenderer.getMainCamera();
        var f = cam.forwardVector();
        return cam.position().add(f.x() * 0.8, f.y() * 0.8 - 0.45, f.z() * 0.8);
    }

    private static void mark(Minecraft mc, AABB box, float width) {
        mark(mc, box, width, 0x28);
    }

    private static void mark(Minecraft mc, AABB box, float width, int fillAlpha) {
        int c = Theme.accent() | 0xFF000000;
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(c, width, (c & 0x00FFFFFF) | (fillAlpha << 24))).setAlwaysOnTop();
        Vec3 foot = new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
        Gizmos.line(tracerFrom(mc), foot, c, 1.5f).setAlwaysOnTop();
    }

    /** A Nukekubi head: named for one, or an armour stand wearing a player head during your fight. */
    private static boolean isHead(Entity e) {
        String plain = plainName(e);
        if (plain != null) {
            String n = plain.toLowerCase();
            if (n.contains("nukekubi") || n.contains("nukubi")) return true;
        }
        if (!(e instanceof ArmorStand stand)) return false;
        ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty() || !head.is(Items.PLAYER_HEAD)) return false;
        String hn = Zealots_strip(head.getHoverName().getString()).toLowerCase();
        if (hn.contains("nukekubi") || hn.contains("nukubi")) return true;
        // A bare head on a nameless stand, in your fight: the shape of one on every server that has this boss.
        return e.getCustomName() == null;
    }

    private static String Zealots_strip(String s) {
        return s.replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "");
    }

    private static void gizmos() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;

        if (highlight && bossEntity != null && !bossEntity.isRemoved()
                && System.currentTimeMillis() - bossSeen < 2000) {
            mark(mc, bossEntity.getBoundingBox(), 2f);
        }

        drawBeams(mc);

        for (BlockPos b : beacons) mark(mc, new AABB(b), 2.5f);

        if (heads && Slayer.bossUp()) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == mc.player || !isHead(e)) continue;
                if (e.position().distanceTo(mc.player.position()) > HEAD_RANGE) continue;
                // The head, not the whole stand: its box is mostly empty pole.
                AABB bb = e.getBoundingBox();
                double cx = (bb.minX + bb.maxX) / 2, cz = (bb.minZ + bb.maxZ) / 2;
                mark(mc, new AABB(cx - 0.35, bb.maxY - 0.7, cz - 0.35, cx + 0.35, bb.maxY, cz + 0.35), 2f, 0x70);
            }
        }
    }

    private static boolean showBeams(Minecraft mc) {
        return enabled && beams && !mc.options.hideGui && mc.level != null && mc.player != null
                && Slayer.bossUp() && bossRadiating && "IV".equals(bossTier)
                && bossEntity != null && !bossEntity.isRemoved() && bossEntity.level() == mc.level
                && System.currentTimeMillis() - bossSeen < 250;
    }

    private static RadiationBeams.Point point(Vec3 v) {
        return new RadiationBeams.Point(v.x, v.y, v.z);
    }

    private static RadiationBeams.Line beamLine(Guardian guardian, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (!showBeams(mc) || guardian.level() != mc.level || guardian.isRemoved()) return null;
        var target = guardian.getActiveAttackTarget();
        if (target == null || target.isRemoved()) return null;
        Vec3 from = guardian.getEyePosition(partial);
        // Match GuardianRenderer's target interpolation, including its half-height anchor.
        Vec3 to = new Vec3(Mth.lerp(partial, target.xOld, target.getX()),
                Mth.lerp(partial, target.yOld, target.getY()) + target.getBbHeight() * 0.5,
                Mth.lerp(partial, target.zOld, target.getZ()));
        return RadiationBeams.match(true, point(bossEntity.getPosition(partial)),
                new RadiationBeams.Candidate(guardian.hasActiveAttackTarget(), guardian.isInvisible(),
                        target instanceof ArmorStand, target.isInvisible(), point(from), point(to)));
    }

    /** Only replace validated radiation beams; disabling the helper restores vanilla rendering. */
    public static boolean replacesBeam(Guardian guardian, float partial) {
        return beamLine(guardian, partial) != null;
    }

    private static void drawBeams(Minecraft mc) {
        if (!showBeams(mc)) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        int color = beamTint();
        float width = (float) beamWidth;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Guardian guardian)) continue;
            RadiationBeams.Line line = beamLine(guardian, partial);
            if (line == null) continue;
            Vec3 from = new Vec3(line.from().x(), line.from().y(), line.from().z());
            Vec3 to = new Vec3(line.to().x(), line.to().y(), line.to().z());
            // Keep depth testing: a beam behind the wall should stay behind the wall.
            Gizmos.line(from, to, (color & 0x00FFFFFF) | 0x44000000, width * 2);
            Gizmos.line(from, to, color, width);
        }
        Gizmos.circle(bossEntity.getPosition(partial).add(0, 0.03, 0), 15f,
                GizmoStyle.stroke((color & 0x00FFFFFF) | 0x88000000, 1.5f));
    }

    private static int beamTint() {
        return 0xFF000000 | (themeBeams ? Theme.accent() : Setting.Color.live(beamColor));
    }

    // ── on the screen ─────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled || (!onScreen && !radiationTimer)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        // Only while a boss has been seen in the last couple of seconds.
        if (bossSeen == 0 || System.currentTimeMillis() - bossSeen >= 2000) return;

        if (onScreen) HudLayout.draw("slayer.voidgloom", g, mc.font, false);
        if (radiationTimer) drawRadiationTimer(g, mc);
    }

    private static void drawRadiationTimer(GuiGraphicsExtractor g, Minecraft mc) {
        if (radiationStarted == 0 || bossEntity == null || bossEntity.isRemoved()
                || !"IV".equals(bossTier)) return;

        double remaining = (RADIATION_NS - (System.nanoTime() - radiationStarted)) / 1e9;
        Entity vehicle = bossEntity.getVehicle();
        if (vehicle != null) {
            double vehicleRemaining = 8.2 - vehicle.tickCount / 20.0;
            if (vehicleRemaining > 0 && vehicleRemaining <= 8.2) {
                remaining = vehicleRemaining;
                radiationVehicleSeen = true;
            }
        } else if (radiationVehicleSeen) {
            radiationStarted = 0;
            return;
        }
        if (remaining <= 0) {
            radiationStarted = 0;
            return;
        }

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        double above = bossEntity instanceof ArmorStand ? 0.35 : bossEntity.getBbHeight() * 0.88 + 0.45;
        Vec3 point = bossEntity.getPosition(partial).add(0, above, 0);
        double[] screen = Project.toScreen(point, g.guiWidth(), g.guiHeight());
        if (screen == null || screen[0] < 20 || screen[0] > g.guiWidth() - 20
                || screen[1] < 15 || screen[1] > g.guiHeight() - 15) return;

        int tenths = (int) Math.ceil(remaining * 10);
        String label = (tenths / 10) + "." + (tenths % 10) + "s";
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) screen[0], (float) screen[1]);
        pose.scale(1.4f, 1.4f);
        g.text(mc.font, label, -mc.font.width(label) / 2, -mc.font.lineHeight / 2,
                0xFFFFFFFF, true);
        pose.popMatrix();
    }
}
