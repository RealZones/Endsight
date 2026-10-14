package com.endsight.dungeon;

import com.endsight.dragons.DragonTimer;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Higher Or Lower": kill the blazes in order of health. The chest shows which way.
 *
 * Everything here was read off a recording of the room rather than guessed, after two
 * rounds of guessing block ids from screenshots cost an evening on the Tic Tac Toe
 * board. What the recording showed:
 *
 *   blaze         -1 +8 +4    [Lv100] Blaze 225000/225000❤
 *   text_display  -6 +17 -7   Higher Or Lower
 *   text_display  -6 +17 -7   Highest health first.
 *
 * So the health is on the mob's own tag in the server's usual format, and the direction
 * is written on a floating label beside the title - not on the chest itself, whatever
 * the puzzle hint says. The label is read every tick because a room can say either.
 *
 * Only the next blaze is outlined. Marking all ten in order would be a decoration of
 * the room; one target is an instruction.
 */
public final class HigherOrLower {
    private static final String ID = "dungeon.higherOrLower";
    private static final String ENTRY = "PUZZLE! Higher Or Lower";

    private static boolean enabled = true, throughWalls = true, announce = true, active;
    private static ClientLevel world;
    private static HigherOrLowerLogic.Order order;
    /** Ticks the blaze count must hold before the first outline. */
    private static final int SETTLE_TICKS = 20;
    private static int lastCount = -1, settled;
    private static String announced;

    /**
     * Every blaze the room has shown, kept once the room has been read in full.
     *
     * The first version re-counted the blazes in range every tick and went back to
     * waiting whenever the count moved. Walking away let blazes drop out of range, the
     * count changed, and the outline vanished; every kill also blanked it for a second.
     * The wait is only there so a half-loaded room is never ordered, so it now happens
     * once: after that a blaze out of sight keeps its last reading and box, and only a
     * kill takes one off the list.
     */
    private record Seen(HigherOrLowerLogic.Blaze blaze, AABB box) { }
    private static final Map<UUID, Seen> roster = new LinkedHashMap<>();
    private static boolean locked;
    private static Vec3 room;                   // the middle of the blazes, once locked
    private static Entity target;               // the next blaze, while it is loaded
    private static AABB targetBox;              // where it was last seen, when it is not

    private HigherOrLower() { }

    public static Module module() {
        return new Module(ID, "Higher Or Lower", "Outlines the next blaze to kill, in the order the room asks for.",
                "Dungeons", () -> enabled, value -> { enabled = value; if (!value) reset(); }, List.of(
                new Setting.Toggle("Through walls", "Keep the outline visible through blocks.",
                        () -> throughWalls, value -> throughWalls = value),
                new Setting.Toggle("Say the order", "Name the direction in chat when the room starts.",
                        () -> announce, value -> announce = value)));
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || !DungeonContext.active()) return;
            String line = plain(message.getString());
            if (DragonTimer.isPlayerChat(line)) return;
            if (entry(line)) {
                reset();
                active = true;
                world = mc.level;
            } else if (ends(line)) {
                reset();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(HigherOrLower::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw());
    }

    static String plain(String raw) {
        return raw == null ? "" : raw.replaceAll("§.", "").trim();
    }

    /** The name identifies the puzzle; the sentence after it is flavour. */
    static boolean entry(String line) {
        String text = plain(line);
        return text.equals(ENTRY) || text.startsWith(ENTRY + " -") || text.startsWith(ENTRY + ":");
    }

    static boolean ends(String line) {
        String text = plain(line);
        return text.startsWith("PUZZLE SOLVED! Higher Or Lower") || text.startsWith("PUZZLE FAIL! Higher Or Lower")
                || text.startsWith("PUZZLE! ") && !entry(text);
    }

    private static boolean visible(Minecraft mc) {
        return enabled && active && mc.player != null && mc.level == world && DungeonContext.active();
    }

    private static void reset() {
        active = false; world = null; order = null; target = null; announced = null;
        lastCount = -1; settled = 0;
        roster.clear(); locked = false; room = null; targetBox = null;
    }

    private static void tick(Minecraft mc) {
        if (!visible(mc)) { target = null; targetBox = null; return; }

        // The direction label, re-read each tick: a later room can ask for the other end,
        // and a stale order would point at the exactly wrong blaze.
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > 40 * 40) continue;
            var label = entity instanceof Display.TextDisplay text ? text.getText() : entity.getCustomName();
            if (label == null) continue;
            HigherOrLowerLogic.Order said = HigherOrLowerLogic.order(label.getString());
            if (said != null) order = said;
        }
        if (order == null) { target = null; return; }
        say(mc);

        Map<UUID, Seen> live = new LinkedHashMap<>();
        Map<UUID, Entity> loaded = new HashMap<>();
        List<UUID> dead = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player) continue;
            // Before the room is read, "the room" is what is near you; after, it is what
            // is near the blazes, so a blaze elsewhere in the dungeon never joins in.
            Vec3 from = room != null ? room : mc.player.position();
            if (entity.position().distanceToSqr(from) > 40 * 40) continue;
            var label = entity.getCustomName();
            HigherOrLowerLogic.Blaze blaze = HigherOrLowerLogic.read(label == null ? null : label.getString());
            if (blaze == null) continue;
            if (blaze.health() <= 0 || entity instanceof LivingEntity living && living.isDeadOrDying()) {
                dead.add(entity.getUUID());
                continue;
            }
            live.put(entity.getUUID(), new Seen(blaze, entity.getBoundingBox()));
            loaded.put(entity.getUUID(), entity);
        }
        if (!locked) {
            // Wait for the room to finish arriving.
            //
            // Blazes stream in as entities over several ticks, so the first few samples are
            // a partial set - and "lowest health of the three that have loaded" is often a
            // different blaze from "lowest of ten". Marking one early is worse than marking
            // none: it is confidently wrong at exactly the moment the room is read. So the
            // count has to hold steady before anything is drawn, and any change restarts it.
            if (live.size() != lastCount) { lastCount = live.size(); settled = 0; target = null; return; }
            if (++settled < SETTLE_TICKS || live.isEmpty()) { target = null; return; }
            locked = true;
            double x = 0, y = 0, z = 0;
            for (Seen seen : live.values()) { Vec3 c = seen.box().getCenter(); x += c.x; y += c.y; z += c.z; }
            room = new Vec3(x / live.size(), y / live.size(), z / live.size());
        }
        roster.putAll(live);                    // fresh health and position, and late arrivals
        dead.forEach(roster::remove);
        // Out of sight is not dead: the server stops sending a blaze once you are far
        // enough away. Gone while you stand close to where it was, it was killed.
        roster.entrySet().removeIf(e -> !live.containsKey(e.getKey())
                && e.getValue().box().getCenter().distanceToSqr(mc.player.position()) < 24 * 24);

        List<HigherOrLowerLogic.Blaze> read = roster.values().stream().map(Seen::blaze).toList();
        HigherOrLowerLogic.Blaze next = HigherOrLowerLogic.next(read, order);
        target = null;
        targetBox = null;
        if (next == null) return;
        for (Map.Entry<UUID, Seen> e : roster.entrySet()) {
            if (e.getValue().blaze() != next) continue;
            target = loaded.get(e.getKey());
            targetBox = e.getValue().box();
            break;
        }
    }

    private static void say(Minecraft mc) {
        if (!announce || mc.player == null) return;
        String text = order == HigherOrLowerLogic.Order.HIGHEST_FIRST ? "highest health first" : "lowest health first";
        if (text.equals(announced)) return;
        announced = text;
        mc.player.sendSystemMessage(Component.literal("§5Higher Or Lower §7- §f" + text));
    }

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc)) return;
        AABB box = target != null && !target.isRemoved() ? target.getBoundingBox() : targetBox;
        if (box == null) return;
        var outline = Gizmos.cuboid(box.inflate(.08),
                GizmoStyle.strokeAndFill(0xFF55FF55, 2.5f, 0x4055FF55));
        if (throughWalls) outline.setAlwaysOnTop();
    }
}
