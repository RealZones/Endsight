package com.endsight.dungeon;

import com.endsight.hud.Alert;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A Wither or Blood Key lying in the dungeon: an alert when one appears, and a box on it
 * until someone picks it up - red for Blood, black for Wither.
 *
 * Dragsim says nothing when a key drops - the only chat line is "<name> has obtained Wither
 * Key!", once it is already in someone's hands - so the key is found by looking at what is
 * in the world, by name: a dropped item, an item display, an armour stand wearing it, or a
 * floating label over one. On Dragsim it is the armour stand: a "Wither Key" name over a
 * black head, worn by a stand you cannot see.
 *
 * That is why the highlight is a drawn box and not the game's glow outline. On an invisible
 * stand the glow traces the whole stand - a stick figure round the key, checked in the test
 * client - and the first version, glow only, showed Fear nothing at all. The box goes round
 * the head the stand wears, or round whatever sits under a bare label.
 *
 * The box is hidden by walls like anything else. Fear wants the key easier to see, not seen
 * through the dungeon: a highlight that shows through walls is ESP, and that is a cheat.
 */
public final class KeyAlert {
    private static final Logger LOG = LoggerFactory.getLogger("endsight");
    private static final String ID = "dungeon.keys";

    enum Kind {
        // The alert text stays light on the Wither one: black letters vanish on a dark screen.
        WITHER("WITHER KEY", 0xFFC8C8D0, 0x0A0A0A),
        BLOOD("BLOOD KEY", 0xFFFF5555, 0xFF3030);

        final String title;
        final int alertColour, outline;
        Kind(String title, int alertColour, int outline) { this.title = title; this.alertColour = alertColour; this.outline = outline; }
    }

    /** What to highlight for one key: the entity that carries it, found once per scan. */
    private record Mark(Kind kind, int carrier) { }

    private static boolean enabled = true, highlight = true;
    /** Keyed by the carrier's id, so an item and the label over it make one mark. */
    private static final Map<Integer, Mark> marks = new LinkedHashMap<>();
    private static final Set<Integer> logged = new HashSet<>();
    private static Object world;
    private static int countdown;

    private KeyAlert() { }

    public static Module module() {
        return new Module(ID, "Key Alerts", "Alerts when a Wither or Blood Key drops and outlines it.", "Dungeons",
                () -> enabled, value -> { enabled = value; if (!value) marks.clear(); }, List.of(
                new Setting.Toggle("Outline keys", "Box the key so it is easier to spot.",
                        () -> highlight, value -> highlight = value)));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(KeyAlert::tick);
        // Drawn from the client tick, not the frame's gizmo event. Boxes added in that event
        // never appeared in the test client, while ones added during a tick are kept by the
        // game for the whole tick and drawn every frame of it; a key lies still, so 20
        // updates a second loses nothing.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> draw());
    }

    /** Whether this entity is the one a key's highlight is drawn on. */
    static boolean marked(Entity entity) {
        return marks.containsKey(entity.getId());
    }

    private static void tick(Minecraft mc) {
        if (mc.level != world) { world = mc.level; marks.clear(); logged.clear(); }
        if (!enabled || mc.level == null || mc.player == null || !DungeonContext.active()) { marks.clear(); return; }
        // Four times a second is plenty for something that lies on the floor until it is
        // picked up, and keeps a name check off every entity every tick.
        if (--countdown > 0) return;
        countdown = 5;
        Map<Integer, Mark> now = new LinkedHashMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            List<Component> names = names(entity);
            Kind kind = null;
            for (Component name : names) if ((kind = kind(name)) != null) break;
            note(entity, names, kind);
            if (kind == null) continue;
            Entity carrier = carrier(mc, entity);
            now.putIfAbsent(carrier.getId(), new Mark(kind, carrier.getId()));
        }
        // One alert per key kind appearing, not per entity: the label and the stand under
        // it both read "Wither Key".
        Set<Kind> before = EnumSet.noneOf(Kind.class), after = EnumSet.noneOf(Kind.class);
        marks.values().forEach(mark -> before.add(mark.kind()));
        now.values().forEach(mark -> after.add(mark.kind()));
        for (Kind kind : after) {
            if (before.contains(kind)) continue;
            Alert.show(kind.title, "dropped", kind.alertColour, 1.1f, 3);
            mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, kind == Kind.BLOOD ? 0.8f : 1.4f);
        }
        marks.clear();
        marks.putAll(now);
    }

    /**
     * The entity that actually shows the key. A name with nothing to show - a text display,
     * or a stand holding no item - labels whatever item-bearing entity sits just under it.
     */
    private static Entity carrier(Minecraft mc, Entity named) {
        if (bearsItem(named)) return named;
        Entity best = null;
        double nearest = Double.MAX_VALUE;
        for (Entity other : mc.level.getEntities(named, named.getBoundingBox().inflate(1, 3, 1))) {
            if (!bearsItem(other)) continue;
            double dx = other.getX() - named.getX(), dz = other.getZ() - named.getZ(), dy = named.getY() - other.getY();
            if (dx * dx + dz * dz > 1 || dy < -0.5 || dy > 3) continue;
            double d = dx * dx + dz * dz + dy * dy;
            if (d < nearest) { nearest = d; best = other; }
        }
        return best == null ? named : best;
    }

    private static boolean bearsItem(Entity entity) {
        if (entity instanceof ItemEntity || entity instanceof Display.ItemDisplay) return true;
        return entity instanceof LivingEntity living && !(entity instanceof Player)
                && (!living.getItemBySlot(EquipmentSlot.HEAD).isEmpty() || !living.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty());
    }

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || !highlight || marks.isEmpty() || mc.level == null) return;
        for (Mark mark : marks.values()) {
            Entity carrier = mc.level.getEntity(mark.carrier());
            if (carrier == null || carrier.isRemoved()) continue;
            int rgb = mark.kind().outline & 0xFFFFFF;
            // Depth-tested on purpose: no setAlwaysOnTop (see the class comment).
            Gizmos.cuboid(box(carrier), GizmoStyle.strokeAndFill(0xFF000000 | rgb, 2.5f, 0x38000000 | rgb));
        }
    }

    /** Round a dropped item, the head a stand wears, the item a display shows, or under a bare label. */
    private static AABB box(Entity entity) {
        Vec3 at = entity.position();
        // A dropped item is drawn floating above its own box, so centre on the drawn item.
        if (entity instanceof ItemEntity) return AABB.ofSize(at.add(0, .2, 0), .5, .5, .5);
        if (entity instanceof Display.ItemDisplay) return AABB.ofSize(at, .6, .6, .6);
        if (entity instanceof LivingEntity living) {
            if (!living.getItemBySlot(EquipmentSlot.HEAD).isEmpty())
                return AABB.ofSize(at.add(0, entity.getEyeHeight(), 0), .66, .66, .66);
            if (!living.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty())
                return AABB.ofSize(at.add(0, entity.getBbHeight() * .5, 0), .5, .5, .5);
        }
        return AABB.ofSize(at.add(0, -.9, 0), .6, .6, .6);
    }

    private static List<Component> names(Entity entity) {
        List<Component> names = new ArrayList<>(3);
        if (entity.getCustomName() != null) names.add(entity.getCustomName());
        if (entity instanceof ItemEntity item) names.add(item.getItem().getHoverName());
        else if (entity instanceof Display.ItemDisplay display) names.add(display.getItemStack().getHoverName());
        else if (entity instanceof Display.TextDisplay text) names.add(text.getText());
        else if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
            // An armour stand holding the key on its head or in its hand.
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.MAINHAND}) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) names.add(stack.getHoverName());
            }
        }
        return names;
    }

    static Kind kind(Component name) {
        return name == null ? null : kind(name.getString());
    }

    static Kind kind(String raw) {
        String text = raw.replaceAll("§.", "").toLowerCase(Locale.ROOT);
        if (text.contains("wither key")) return Kind.WITHER;
        if (text.contains("blood key")) return Kind.BLOOD;
        return null;
    }

    /**
     * Writes anything that mentions a key to the log, once each, up to 20 a world: the real
     * shape of a dropped key, for when it changes. It used to log every item-like entity,
     * and a dungeon's 40 decoration stands used the whole allowance before the key dropped.
     */
    private static void note(Entity entity, List<Component> names, Kind kind) {
        if (logged.size() >= 20) return;
        boolean mentionsKey = kind != null || names.stream().anyMatch(n -> n.getString().toLowerCase(Locale.ROOT).contains("key"));
        if (!mentionsKey || !logged.add(entity.getId())) return;
        String stand = entity instanceof ArmorStand a ? " marker=" + a.isMarker() + " small=" + a.isSmall() : "";
        LOG.info("[Endsight] Key entity: {} at {} names={} key={} invisible={} nameShown={}{} head={} vehicle={}",
                entity.getType().toShortString(), entity.blockPosition().toShortString(),
                names.stream().map(Component::getString).toList(), kind, entity.isInvisible(), entity.isCustomNameVisible(), stand,
                entity instanceof LivingEntity l ? l.getItemBySlot(EquipmentSlot.HEAD).getItem().toString() : "-",
                entity.getVehicle() == null ? "-" : entity.getVehicle().getType().toShortString());
    }
}
