package com.endsight.dungeon;

import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.minecraft.client.renderer.RenderPipelines;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shows the server's live dungeon map without the held item's parchment and hands. */
public final class DungeonMap {
    private static final String ID = "dungeon.map";
    // The map sits in a 2px black frame over a see-through dark fill, the look Fear
    // picked: the server's map leaves the space between rooms transparent, so the fill
    // is what those gaps show, with the world faintly behind it. No caption above: a
    // header saying "map" costs 12px and tells the reader nothing the picture does not.
    private static final int SIZE = 128, BORDER = 2, PAD = 2, INSET = BORDER + PAD, OUTER = SIZE + 2 * INSET;
    // Markers are always shown and always glide: Fear found switches for either useless.
    private static boolean enabled = true, names, heads = true;
    /** Set once the boss speaks; the map is no help in the arena, so it stays hidden till the run ends. */
    private static boolean boss;
    private static ClientLevel world;
    private static MapId selected;
    private static MapItemSavedData data;
    private static int readTicks, selectedSlot = -1;
    private static final DungeonMapMotion motion = new DungeonMapMotion();
    /** Rooms Dragsim has named, placed on the map; and names still waiting for a place. */
    private static final List<Label> labels = new ArrayList<>();
    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
    /** How long a name may wait for your marker to be certain and inside its room. */
    private static final long PLACE_MS = 6_000;

    private record Label(String name, DungeonRooms.Room room) { }
    private record Pending(String name, DungeonRooms.Kind kind, long at) { }

    private DungeonMap() {}

    public static Module module() {
        return new Module(ID, "Dungeon Map", "The server's room map and player markers, visible while exploring.",
                "Dungeons", () -> enabled, value -> { enabled = value; if (!value) clear(); }, List.of(
                new Setting.Toggle("Marker names", "Include names supplied with map markers.",
                        () -> names, value -> names = value),
                new Setting.Toggle("Player heads", "Draw player faces in place of the map's arrows.",
                        () -> heads, value -> heads = value),
                new Setting.Action("Position and size", "Move the map; scroll over it to resize.", "Move",
                        HudPlacementScreen::open)));
    }

    public static void init() {
        HudLayout.register(ID, "Dungeon Map", .02f, .16f, DungeonMap::drawAt);
        ClientTickEvents.END_CLIENT_TICK.register(DungeonMap::tick);
        // Dragsim names a puzzle or trap only as you walk in, so that moment is when we
        // know which room it is: the one under your own marker.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled) return;
            String text = BloodRoomLogic.plain(message.getString());
            if (text.startsWith("PUZZLE! ")) {
                String name = text.substring(8).split(" - ", 2)[0].trim();
                if (!name.isEmpty()) pending.add(new Pending(name, DungeonRooms.Kind.PUZZLE, System.currentTimeMillis()));
            } else if (text.startsWith("TRAP ROOM!")) {
                // The entry line gives no trap name, so the room is just "Trap".
                pending.add(new Pending("Trap", DungeonRooms.Kind.TRAP, System.currentTimeMillis()));
            } else if (text.startsWith("[BOSS] ") && !text.startsWith("[BOSS] The Watcher") && DungeonContext.active()) {
                // The fight opens with "[BOSS] Maxor: WELL WELL WELL...". The Watcher speaks
                // as [BOSS] too, but from the blood room, mid-run; dragons talk the same way
                // outside dungeons, where the context check keeps them out.
                boss = true;
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "dungeon_map"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (enabled && !boss && mc.player != null && mc.level != null && !mc.options.hideGui && DungeonContext.active())
                HudLayout.draw(ID, g, mc.font, false);
        });
    }

    private static void tick(Minecraft mc) {
        if (mc.level != world || mc.player == null || !DungeonContext.active()) {
            clear();
            world = mc.level;
        }
        if (!enabled || mc.player == null || mc.level == null || !DungeonContext.active()) return;
        if (mc.options.hideGui) { readTicks = 0; return; }
        var inventory = mc.player.getInventory();
        // Map pixels/decorations stay live in MapItemSavedData. Finding which map to
        // show only needs an inventory pass four times a second, or a hotbar change.
        if (--readTicks > 0 && selectedSlot == inventory.getSelectedSlot()) {
            data = selected == null ? null : mc.level.getMapData(selected);
            place(mc);
            return;
        }
        readTicks = 5;
        selectedSlot = inventory.getSelectedSlot();
        List<Candidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            MapId id = stack.get(DataComponents.MAP_ID);
            if (id != null) candidates.add(new Candidate(id.id(), stack.getHoverName().getString(),
                    slot == inventory.getSelectedSlot()));
        }
        // Offhand maps may be outside an inventory implementation's ordinary slots.
        ItemStack offhand = mc.player.getOffhandItem();
        MapId offhandId = offhand.get(DataComponents.MAP_ID);
        if (offhandId != null)
            candidates.add(new Candidate(offhandId.id(), offhand.getHoverName().getString(), true));
        Integer id = choose(candidates, selected == null ? null : selected.id());
        selected = id == null ? null : new MapId(id);
        data = selected == null ? null : mc.level.getMapData(selected);
        place(mc);
    }

    /**
     * Puts waiting names on their rooms. Each waits until your marker is certain and the
     * room's colour is within reach of it; announced at the door, you may still be on the
     * door pixels for a tick or two. A name that finds no room in a few seconds is dropped
     * rather than guessed.
     */
    private static void place(Minecraft mc) {
        long now = System.currentTimeMillis();
        pending.removeIf(p -> now - p.at() > PLACE_MS);
        if (data == null || pending.isEmpty()) return;
        double[] self = ownMarker(mc);
        if (self == null) return;
        for (var it = pending.iterator(); it.hasNext(); ) {
            Pending next = it.next();
            if (labels.stream().anyMatch(l -> l.name().equalsIgnoreCase(next.name()))) { it.remove(); continue; }
            DungeonRooms.Room room = DungeonRooms.find(data.colors, self[0], self[1], next.kind());
            if (room == null) continue;
            labels.removeIf(l -> l.room().same(room));
            labels.add(new Label(next.name(), room));
            it.remove();
        }
    }

    /**
     * Your marker in map pixels, or null while it is not certain. The green FRAME marker
     * is you on Hypixel-style maps; a marker carrying your name is you; so is a lone one.
     * Among several unnamed markers, the only one facing the way you face is you.
     */
    private static double[] ownMarker(Minecraft mc) {
        List<MapDecoration> players = new ArrayList<>();
        for (MapDecoration decoration : data.getDecorations()) if (player(decoration)) players.add(decoration);
        String me = mc.player.getGameProfile().name();
        MapDecoration found = players.stream().filter(d -> d.type().equals(MapDecorationTypes.FRAME)).findFirst()
                .orElseGet(() -> players.stream().filter(d -> d.name()
                        .map(n -> n.getString().replaceAll("§.", "").trim().equalsIgnoreCase(me)).orElse(false)).findFirst().orElse(null));
        if (found == null && players.size() == 1) found = players.getFirst();
        if (found == null) {
            int facing = DungeonRooms.rot(mc.player.getYRot());
            List<MapDecoration> same = players.stream().filter(d -> (d.rot() & 15) == facing).toList();
            if (same.size() == 1) found = same.getFirst();
        }
        return found == null ? null : new double[]{64 + found.x() / 2.0, 64 + found.y() / 2.0};
    }

    private static void clear() {
        selected = null;
        data = null;
        readTicks = 0;
        selectedSlot = -1;
        motion.clear();
        labels.clear(); pending.clear();
        boss = false;
    }

    /**
     * The map's player markers. PLAYER is everyone on a vanilla map; FRAME (the green
     * marker) is how Hypixel-style dungeon maps mark you, so a FRAME marker gets your face.
     */
    private static boolean player(MapDecoration decoration) {
        return decoration.type().equals(MapDecorationTypes.PLAYER) || decoration.type().equals(MapDecorationTypes.FRAME);
    }

    private static List<DungeonMapMotion.Target> markerTargets() {
        if (data == null) return List.of();
        List<DungeonMapMotion.Target> targets = new ArrayList<>();
        for (MapDecoration decoration : data.getDecorations()) {
            if (!player(decoration)) continue;
            String name = decoration.name().map(component -> component.getString().replaceAll("§.", "").trim()).orElse(null);
            // Decoration coordinates are half map pixels about the centre, and the facing
            // is sixteenths of a turn; +180 so a player facing north (up) is drawn upright.
            targets.add(new DungeonMapMotion.Target(name == null || name.isBlank() ? null : name,
                    decoration.type().equals(MapDecorationTypes.FRAME),
                    64 + decoration.x() / 2.0, 64 + decoration.y() / 2.0, (decoration.rot() & 15) * 22.5 + 180));
        }
        return targets;
    }

    /** One map id can occupy several slots; those copies do not make selection ambiguous. */
    record Candidate(int id, String name, boolean held) {}

    static Integer choose(List<Candidate> candidates, Integer previous) {
        Map<Integer, Candidate> all = new LinkedHashMap<>();
        Map<Integer, Candidate> named = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            all.putIfAbsent(candidate.id(), candidate);
            if (dungeonName(candidate.name())) {
                Candidate old = named.get(candidate.id());
                if (old == null || candidate.held()) named.put(candidate.id(), candidate);
            }
        }
        if (named.size() == 1) return named.keySet().iterator().next();
        if (named.size() > 1) {
            List<Integer> held = named.values().stream().filter(Candidate::held).map(Candidate::id).toList();
            if (held.size() == 1) return held.getFirst();
            if (previous != null && named.containsKey(previous)) return previous;
            return null;
        }
        // A selected hotbar map alone is not proof: players can carry ordinary maps.
        // Without a dungeon name, require one distinct map in a confirmed dungeon.
        return all.size() == 1 ? all.keySet().iterator().next() : null;
    }

    static boolean dungeonName(String raw) {
        if (raw == null) return false;
        String name = raw.replaceAll("§.", "").strip().toLowerCase(Locale.ROOT);
        return name.equals("magical map") || name.equals("dungeon map") || name.equals("catacombs map");
    }

    private static void frame(GuiGraphicsExtractor g, int x, int y) {
        // Four strips and a separate fill, not one black rect under the fill: the fill is
        // see-through, so a solid rect beneath it would turn the whole map black.
        Draw.rect(g, x, y, OUTER, BORDER, 0xFF000000);
        Draw.rect(g, x, y + OUTER - BORDER, OUTER, BORDER, 0xFF000000);
        Draw.rect(g, x, y + BORDER, BORDER, OUTER - 2 * BORDER, 0xFF000000);
        Draw.rect(g, x + OUTER - BORDER, y + BORDER, BORDER, OUTER - 2 * BORDER, 0xFF000000);
        Draw.rect(g, x + BORDER, y + BORDER, OUTER - 2 * BORDER, OUTER - 2 * BORDER, 0xC00A0A0C);
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        boolean live = data != null && selected != null && world == Minecraft.getInstance().level;
        if (g == null) return sample || live ? new int[]{OUTER, OUTER} : new int[]{0, 0};
        if (sample && !live) {
            frame(g, x, y);
            g.text(font, "Live map appears", x + INSET + 18, y + INSET + 54, 0xFFAAAAAA, true);
            g.text(font, "inside a dungeon", x + INSET + 18, y + INSET + 66, 0xFFAAAAAA, true);
            return new int[]{OUTER, OUTER};
        }
        // No map, nothing drawn. This used to say "Waiting for dungeon map", which is
        // true and useless: in the dungeon hub there is no map to wait for, so the line
        // just sat on screen between runs. The map appearing is its own announcement.
        if (!live) {
            return new int[]{0, 0};
        }
        // The native extractor resolves the live texture and decoration sprites. No
        // guessed room grid or world-to-map conversion can drift from server updates.
        MapRenderState state = new MapRenderState();
        Minecraft.getInstance().getMapRenderer().extractRenderState(selected, data, state);
        if (heads) dropPlayerArrows(state);
        for (var decoration : state.decorations) {
            // GUI maps normally use the item-frame filter, which hides player arrows.
            // This state is private to this HUD, so enabling them cannot alter held maps.
            decoration.renderOnFrame = true;
            if (!names || heads) decoration.name = null;
        }
        frame(g, x, y);
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) (x + INSET), (float) (y + INSET));
            g.map(state);
            drawLabels(g, font);
            if (heads) drawHeads(g, font);
        } finally { pose.popMatrix(); }
        return new int[]{OUTER, OUTER};
    }

    /**
     * A head replaces its arrow rather than covering it. Left in, the arrow peeked out
     * round the head and jumped at each server step while the head glided, which was half
     * of the jitter. The render state does not keep the decoration type, but the extractor
     * adds exactly one state per decoration, in order, so the two lists walk together.
     */
    private static void dropPlayerArrows(MapRenderState state) {
        List<MapDecoration> source = new ArrayList<>();
        data.getDecorations().forEach(source::add);
        if (source.size() != state.decorations.size()) return;
        Iterator<MapRenderState.MapDecorationRenderState> shown = state.decorations.iterator();
        for (MapDecoration decoration : source) {
            shown.next();
            if (player(decoration)) shown.remove();
        }
    }

    /**
     * White names with a shadow, centred on their room, as on the reference map. The size
     * is a little over half of normal text (on the reference "Boulder" runs about 1.35
     * rooms wide), snapped so one letter pixel is a whole number of screen pixels at the
     * current GUI and HUD scale; in between, the strokes come out uneven and the text smears.
     */
    private static void drawLabels(GuiGraphicsExtractor g, Font font) {
        if (labels.isEmpty()) return;
        double screen = Minecraft.getInstance().getWindow().getGuiScale() * HudLayout.scale(ID);
        float size = (float) (Math.max(1, Math.round(.57 * screen)) / screen);
        for (Label label : labels) {
            List<String> lines = DungeonRooms.lines(label.name());
            int height = lines.size() * font.lineHeight - 1;
            var pose = g.pose(); pose.pushMatrix();
            try {
                pose.translate((float) label.room().centerX(), (float) label.room().centerY());
                pose.scale(size, size);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    g.text(font, line, -font.width(line) / 2, -height / 2 + i * font.lineHeight, 0xFFFFFFFF, true);
                }
            } finally { pose.popMatrix(); }
        }
    }

    private static void drawHeads(GuiGraphicsExtractor g, Font font) {
        Minecraft mc = Minecraft.getInstance();
        List<DungeonMapMotion.Target> targets = markerTargets();
        List<DungeonMapMotion.Marker> displayed = motion.update(targets, System.nanoTime());
        for (DungeonMapMotion.Marker marker : displayed) {
            if (marker.x() < 3 || marker.x() > 125 || marker.y() < 3 || marker.y() > 125) continue;
            var connection = mc.getConnection();
            var info = connection == null ? null
                    : marker.self() ? connection.getPlayerInfo(mc.player.getUUID())
                    : marker.name() == null ? null : connection.getPlayerInfoIgnoreCase(marker.name());
            var skin = info == null ? DefaultPlayerSkin.getDefaultSkin() : info.getSkin();
            var pose = g.pose(); pose.pushMatrix();
            try {
                // A 1px black border round the face, turned with the player, as on the
                // reference map: the face alone is lost against the brown rooms.
                pose.translate((float) marker.x(), (float) marker.y());
                pose.rotate((float) Math.toRadians(marker.angle()));
                Draw.rect(g, -5, -5, 10, 10, 0xFF000000);
                g.blit(RenderPipelines.GUI_TEXTURED, skin.body().texturePath(), -4, -4, 8f, 8f, 8, 8, 64, 64);
                g.blit(RenderPipelines.GUI_TEXTURED, skin.body().texturePath(), -4, -4, 40f, 8f, 8, 8, 64, 64);
            } finally { pose.popMatrix(); }
            if (names && marker.name() != null)
                g.text(font, marker.name(), (int) Math.round(marker.x()) + 7, (int) Math.round(marker.y()) - 4, 0xFFFFFFFF, true);
        }
    }
}
