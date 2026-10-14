package com.endsight.dungeon;

import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shows the server's live dungeon map without the held item's parchment and hands. */
public final class DungeonMap {
    private static final String ID = "dungeon.map";
    // No header. The map is a map - a caption above it saying so costs 12px of screen
    // and tells the reader nothing the picture does not. The constant stays at 0 rather
    // than being deleted because the waiting and sample states still position against it.
    private static final int SIZE = 128, HEADER = 0;
    private static boolean enabled = true, markers = true, names;
    private static ClientLevel world;
    private static MapId selected;
    private static MapItemSavedData data;
    private static int readTicks, selectedSlot = -1;

    private DungeonMap() {}

    public static Module module() {
        return new Module(ID, "Dungeon Map", "The server's room map and player markers, visible while exploring.",
                "Dungeons", () -> enabled, value -> { enabled = value; if (!value) clear(); }, List.of(
                new Setting.Toggle("Player markers", "Show the markers supplied by the server.",
                        () -> markers, value -> markers = value),
                new Setting.Toggle("Marker names", "Include names supplied with map markers.",
                        () -> names, value -> names = value),
                new Setting.Action("Position and size", "Move the map; scroll over it to resize.", "Move",
                        HudPlacementScreen::open)));
    }

    public static void init() {
        HudLayout.register(ID, "Dungeon Map", .02f, .16f, DungeonMap::drawAt);
        ClientTickEvents.END_CLIENT_TICK.register(DungeonMap::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "dungeon_map"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (enabled && mc.player != null && mc.level != null && !mc.options.hideGui && DungeonContext.active())
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
    }

    private static void clear() {
        selected = null;
        data = null;
        readTicks = 0;
        selectedSlot = -1;
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

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        boolean live = data != null && selected != null && world == Minecraft.getInstance().level;
        if (g == null) return sample || live ? new int[]{SIZE, SIZE + HEADER} : new int[]{0, 0};
        if (sample && !live) {
            g.fill(x, y + HEADER, x + SIZE, y + HEADER + SIZE, Draw.alpha(Theme.bg(), .25f));
            g.text(font, "Live map appears", x + 18, y + HEADER + 54, 0xFFAAAAAA, true);
            g.text(font, "inside a dungeon", x + 18, y + HEADER + 66, 0xFFAAAAAA, true);
            return new int[]{SIZE, SIZE + HEADER};
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
        if (!markers) state.decorations.clear();
        else for (var decoration : state.decorations) {
            // GUI maps normally use the item-frame filter, which hides player arrows.
            // This state is private to this HUD, so enabling them cannot alter held maps.
            decoration.renderOnFrame = true;
            if (!names) decoration.name = null;
        }
        Draw.rect(g, x - 1, y + HEADER - 1, SIZE + 2, SIZE + 2, Draw.alpha(Theme.bg(), .65f));
        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) x, (float) (y + HEADER));
            g.map(state);
        } finally { pose.popMatrix(); }
        return new int[]{SIZE, SIZE + HEADER};
    }
}
