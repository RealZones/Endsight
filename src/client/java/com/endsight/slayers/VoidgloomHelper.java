package com.endsight.slayers;

import com.endsight.hud.Project;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Marks Nukubi with a box and a tracer, drawn on the HUD rather than in the world.
 *
 * Screen-space on purpose, and not for lack of trying the obvious route first. Two
 * things ruled the alternatives out:
 *
 * - Fabric API 0.147 for 26.1.2 ships no WorldRenderEvents. The render rewrite has not
 *   been ported, so there is no supported hook for putting geometry in the world; the
 *   only way in is a mixin into a renderer that was rewritten this version, which is
 *   the most fragile thing available.
 * - Vanilla's glow (setGlowingTag) outlines the WHOLE entity. Nukubi is a head on an
 *   armour stand, so glowing it draws the stand too - which is exactly the part that
 *   should be left out. It cannot be told to outline only the head.
 *
 * Projecting the head position to 2D and drawing with fill sidesteps both, keeps this
 * inside the one primitive the rest of the mod already trusts across versions, and
 * makes "just the head" a choice of which point to project rather than a bounding box
 * we are stuck with.
 */
public final class VoidgloomHelper {

    private VoidgloomHelper() {
    }

    /**
     * What counts as Nukubi.
     *
     * A guess, and flagged as one: nothing has been dumped from the live server yet, so
     * this matches on the display name containing the word. If that is wrong, the
     * "Dump nearby entities" button on this module's page writes every nearby entity to
     * a file and the fix is this one string - or a different test entirely.
     */
    private static final String MATCH = "Nukubi";

    private static boolean enabled = false;
    private static boolean onlyMine = true;
    private static double range = 64;
    private static boolean tracer = true;
    private static boolean fillBox = true;

    /** Yang Glyph: the beacon the boss drops at your feet from tier 2, with five seconds to reach it. */
    private static boolean glyph = true;
    private static final int GLYPH_RANGE = 12;
    private static final List<BlockPos> beacons = new ArrayList<>();

    public static Module module() {
        return new Module("slayer.boss", "Voidgloom Helper",
                "Marks the Nukekubi heads and the Yang Glyph beacon.", "Slayers",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Section("Yang Glyph"),
                        new Setting.Toggle("Beacon",
                                "Box and tracer to the beacon, so you reach it inside the five seconds.",
                                () -> glyph, v -> glyph = v),
                        new Setting.Section("Nukekubi"),
                        new Setting.Slider("Range",
                                "How far out to highlight heads.",
                                8, 256, 8, () -> range, v -> range = v, "m"),
                        new Setting.Toggle("Only mine",
                                "Ignore heads tagged as another player's.",
                                () -> onlyMine, v -> onlyMine = v),
                        new Setting.Toggle("Tracer",
                                "Line from the bottom of the screen to each.",
                                () -> tracer, v -> tracer = v),
                        new Setting.Toggle("Fill box",
                                "Tint the inside of the box, not just its edges.",
                                () -> fillBox, v -> fillBox = v),
                        new Setting.Action("Dump nearby entities",
                                "Writes nearby entities and unusual blocks to a file.",
                                "Dump", VoidgloomHelper::dumpNearby)));
    }

    public static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "nukubi"),
                (g, delta) -> draw(g));
        ClientTickEvents.END_CLIENT_TICK.register(VoidgloomHelper::scanBeacons);
    }

    /**
     * Beacons within a dozen blocks, once a tick. The glyph lands at your feet and is
     * a plain beacon block; scanning a small cube each tick is cheap, and once a frame
     * would not be.
     */
    private static void scanBeacons(Minecraft mc) {
        beacons.clear();
        if (!enabled || !glyph || mc.player == null || mc.level == null) return;
        BlockPos at = mc.player.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(at.offset(-GLYPH_RANGE, -6, -GLYPH_RANGE),
                at.offset(GLYPH_RANGE, 6, GLYPH_RANGE))) {
            if (mc.level.getBlockState(b).is(Blocks.BEACON)) beacons.add(b.immutable());
        }
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui) return;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == player || !matches(e)) continue;
            if (onlyMine && !mine(e)) continue;
            if (e.position().distanceTo(player.position()) > range) continue;

            // The head, not the entity. An armour stand's box is mostly empty pole, so
            // its centre sits in the pole and its top is the top of the head - a short
            // box hung off the top is the head and nothing else.
            AABB bb = e.getBoundingBox();
            double headY = bb.maxY - 0.12;
            Vec3 head = new Vec3((bb.minX + bb.maxX) / 2, headY, (bb.minZ + bb.maxZ) / 2);

            double[] p = Project.toScreen(head, sw, sh);
            if (p == null) continue;

            // Box scaled by distance, so it frames the head instead of being a fixed
            // square that swallows it up close and vanishes far away.
            double[] top = Project.toScreen(head.add(0, 0.34, 0), sw, sh);
            int half = top == null ? 8 : (int) Math.max(4, Math.abs(p[1] - top[1]));

            int cx = (int) p[0], cy = (int) p[1];
            drawBox(g, cx - half, cy - half, half * 2, half * 2);
            if (tracer) drawTracer(g, sw / 2, sh, cx, cy + half);
        }

        // The glyph: a box round the whole block and always a tracer, because the whole
        // point is getting to it in time.
        for (BlockPos b : beacons) {
            Vec3 c = new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            double[] p = Project.toScreen(c, sw, sh);
            if (p == null) continue;
            double[] top = Project.toScreen(c.add(0, 0.5, 0), sw, sh);
            int half = top == null ? 10 : (int) Math.max(6, Math.abs(p[1] - top[1]));
            int cx = (int) p[0], cy = (int) p[1];
            drawBox(g, cx - half, cy - half, half * 2, half * 2);
            drawTracer(g, sw / 2, sh, cx, cy + half);
        }
    }

    private static void drawBox(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (fillBox) Draw.rect(g, x, y, w, h, Draw.alpha(Theme.accent(), 0.16f));
        int c = Theme.accent();
        Draw.rect(g, x, y, w, 1, c);
        Draw.rect(g, x, y + h - 1, w, 1, c);
        Draw.rect(g, x, y, 1, h, c);
        Draw.rect(g, x + w - 1, y, 1, h, c);
    }

    /**
     * A line, stepped one pixel at a time along its longer axis.
     *
     * There is no line primitive here, same as there is no rounded rect - and the same
     * answer applies. A few hundred one-pixel fills is nothing, and it survives the
     * render API changing under it, which a vertex buffer would not.
     */
    private static void drawTracer(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0 || steps > 4000) return;
        int c = Draw.alpha(Theme.accent(), 0.75f);
        for (int i = 0; i <= steps; i++) {
            int x = x0 + dx * i / steps;
            int y = y0 + dy * i / steps;
            g.fill(x, y, x + 1, y + 1, c);
        }
    }

    /**
     * Whether this one belongs to you.
     *
     * The live dump settled this: the server writes the owner into the name itself, as
     * "[Lv100] Voidling Devotee (SomePlayer's soul) 18M/40M". So ownership is an exact
     * string test, not the distance guess it was going to have to be.
     *
     * Not everything carries the tag though - the boss itself comes through as
     * "☠ Voidgloom Seraph IV 2.5B/2.5B❤ Radiation" with no owner at all. An untagged
     * entity is treated as YOURS rather than hidden: "cannot tell" must not silently
     * hide the thing the module was turned on to see, which is the one failure here
     * that would go unnoticed.
     */
    private static boolean mine(Entity e) {
        String plain = plainName(e);
        if (plain == null) return false;
        if (!plain.contains("'s soul")) return true;      // untagged - cannot tell, so keep

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return true;
        String me = mc.player.getName().getString();
        return plain.toLowerCase().contains(("(" + me + "'s soul)").toLowerCase());
    }

    /** Display name with the server's literal section codes taken out, or null. */
    private static String plainName(Entity e) {
        Component name = e.getCustomName();
        if (name == null) name = e.getDisplayName();
        if (name == null) return null;
        return name.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "");
    }

    /**
     * The unusual blocks around you, appended to the same dump.
     *
     * Two passes on purpose. Naming every block in a 41x13x41 box is tens of thousands
     * of lines of end stone, which is not data - it is a haystack. So the first pass
     * counts block types and the second only prints the RARE ones, on the reasoning
     * that a dragon altar is by definition not what the ground is made of.
     *
     * States print whole ("Block{minecraft:end_portal_frame}[eye=true,facing=north]")
     * rather than just the block name, because for this job the property IS the answer:
     * whether a podium holds an eye is a value on the state, not a separate lookup.
     */
    private static void dumpBlocks(Minecraft mc, List<String> out) {
        if (mc.player == null || mc.level == null) return;
        BlockPos origin = mc.player.blockPosition();
        int r = 20, vr = 6;

        Map<String, Integer> counts = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -vr, -r), origin.offset(r, vr, r))) {
            BlockState st = mc.level.getBlockState(pos);
            if (st.isAir()) continue;
            counts.merge(st.getBlock().toString(), 1, Integer::sum);
        }

        out.add("");
        out.add("# --- blocks within " + r + " (rare types only) ---");
        out.add("# type tally:");
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(25)
                .forEach(e -> out.add("#   " + e.getValue() + "  " + e.getKey()));
        out.add("");

        int printed = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -vr, -r), origin.offset(r, vr, r))) {
            BlockState st = mc.level.getBlockState(pos);
            if (st.isAir()) continue;
            String block = st.getBlock().toString();
            boolean interesting = counts.getOrDefault(block, 0) <= 40
                    || block.contains("portal") || block.contains("frame")
                    || block.contains("skull") || block.contains("head");
            if (!interesting || printed++ > 160) continue;
            out.add(String.format("%-22s %s", pos.toShortString(), st));
        }
    }

    private static boolean matches(Entity e) {
        String plain = plainName(e);
        return plain != null && plain.toLowerCase().contains(MATCH.toLowerCase());
    }

    // ── the dump ──────────────────────────────────────────────────────────────

    /**
     * Every entity around you, written to a file.
     *
     * Exists because nobody knows yet what actually identifies Nukubi - the name above
     * it might be the entity's, or a separate armour stand floating over it, and the
     * head might be equipment or a block. Guessing costs a build and a round trip each
     * time; one dump answers it. Written next to the game rather than logged because a
     * log line per entity is unreadable at fifty entities.
     */
    private static void dumpNearby() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        List<String> out = new ArrayList<>();
        out.add("# Endsight entity dump");
        out.add("# you at " + mc.player.position());
        out.add("");

        for (Entity e : mc.level.entitiesForRendering()) {
            double dist = e.position().distanceTo(mc.player.position());
            if (dist > 48) continue;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%-28s d=%5.1f  ", e.getType().toString(), dist));
            sb.append("custom=").append(e.getCustomName() == null
                    ? "-" : "\"" + e.getCustomName().getString() + "\"");
            sb.append("  display=\"").append(e.getDisplayName() == null
                    ? "-" : e.getDisplayName().getString()).append("\"");
            sb.append("  nameVisible=").append(e.isCustomNameVisible());
            sb.append("  bb=").append(String.format("%.2f", e.getBoundingBox().getYsize()));

            // A text_display carries no custom name at all - its text is a separate
            // synched field - so without this it dumps as the useless "Text Display"
            // and every floating hologram on the server looks identical in the file.
            if (e instanceof Display.TextDisplay td) {
                sb.append("  text=\"")
                        .append(td.getEntityData().get(Display.TextDisplay.DATA_TEXT_ID).getString())
                        .append("\"");
            }

            if (e instanceof LivingEntity le) {
                ItemStack head = le.getItemBySlot(EquipmentSlot.HEAD);
                if (!head.isEmpty()) {
                    sb.append("  head=").append(head.getItem())
                            .append(" \"").append(head.getHoverName().getString()).append("\"");
                }
            }
            out.add(sb.toString());
        }

        dumpBlocks(mc, out);

        Path file = mc.gameDirectory.toPath().resolve("endsight-entity-dump.txt");
        try {
            Files.write(file, out);
            mc.player.sendSystemMessage(
                    Component.literal("[Endsight] wrote " + (out.size() - 3)
                            + " entities to " + file.getFileName()));
        } catch (IOException ex) {
            mc.player.sendSystemMessage(
                    Component.literal("[Endsight] dump failed: " + ex.getMessage()));
        }
    }
}
