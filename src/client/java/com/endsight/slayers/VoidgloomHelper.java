package com.endsight.slayers;

import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
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
 * What is deliberately not drawn: the radiation beams. They are "witch" particles,
 * indistinguishable in the packet from the boss's ordinary sparkle, so a beam renderer
 * drew itself at random round the boss and was dropped.
 */
public final class VoidgloomHelper {

    private VoidgloomHelper() {
    }

    private static boolean enabled = false;
    private static boolean onScreen = true;
    private static boolean highlight = true;
    private static boolean heads = true;
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
                "Your boss's hits and health on screen, boss and Yang Glyph highlights.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Toggle("On screen",
                                "Hits left on the shield, big; the boss's health when the shield is down.",
                                () -> onScreen, v -> onScreen = v),
                        new Setting.Toggle("Highlight boss",
                                "A box round the boss and a line to it, so it is never lost in the crowd.",
                                () -> highlight, v -> highlight = v),
                        new Setting.Toggle("Highlight glyph",
                                "Box and tracer to the Yang Glyph beacon, so you reach it inside the five seconds.",
                                () -> glyph, v -> glyph = v),
                        new Setting.Toggle("Highlight Nukekubi",
                                "A solid box and a tracer on each head while your boss is up.",
                                () -> heads, v -> heads = v),
                        new Setting.Action("Move readout",
                                "Drag it, and every other readout, where you want.",
                                "Move", HudPlacementScreen::open)));
    }

    public static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "voidgloom"),
                (g, delta) -> draw(g));
        // A gizmo lives one frame, so the marks are added again every frame, just
        // before the game draws them.
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> gizmos());
        ClientTickEvents.END_CLIENT_TICK.register(VoidgloomHelper::tick);
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
            return;
        }
        Entity e = bossEntity;
        if (e == null || e.isRemoved()) e = claim(mc, bossPos == null ? mc.player.position() : bossPos);
        if (e == null) return;
        String plain = plainName(e);
        if (plain == null) return;
        Matcher m = BOSS.matcher(plain);
        if (!m.find()) return;
        bossTier = m.group(1);
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

    // ── on the screen ─────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled || !onScreen) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        // Only while a boss has been seen in the last couple of seconds.
        if (bossSeen == 0 || System.currentTimeMillis() - bossSeen >= 2000) return;

        HudLayout.draw("slayer.voidgloom", g, mc.font, false);
    }
}
