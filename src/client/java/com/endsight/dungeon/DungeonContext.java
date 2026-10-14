package com.endsight.dungeon;

import com.endsight.dragons.DragonTimer;
import com.endsight.hud.Area;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Dungeon helpers follow live world/sidebar evidence, rather than a remembered End area. */
public final class DungeonContext {
    private static final State STATE = new State();
    private static long lastRead;
    private DungeonContext() { }

    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && STATE.activeAt(mc.level, System.currentTimeMillis());
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) STATE.message(Minecraft.getInstance().level, message.getString(), System.currentTimeMillis());
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            if (mc.level == null || mc.player == null) { STATE.clear(); lastRead = 0; return; }
            if (STATE.world != mc.level || now - lastRead >= 250) {
                STATE.sidebar(mc.level, Area.sidebarLines(mc), now);
                lastRead = now;
            }
        });
    }

    static final class State {
        private Object world;
        private long evidenceAt;
        private boolean seen;

        void clear() { world = null; evidenceAt = 0; seen = false; }

        private void world(Object current) {
            if (world != current) { clear(); world = current; }
        }

        void sidebar(Object current, List<String> lines, long now) {
            world(current);
            if (current == null) return;
            for (String line : lines) {
                if (Area.placeOf(line) == Area.Where.DUNGEON) { seen = true; evidenceAt = now; return; }
            }
            for (String line : lines) {
                if (Area.placeOf(line) != Area.Where.UNKNOWN) { seen = false; evidenceAt = 0; return; }
            }
        }

        void message(Object current, String raw, long now) {
            world(current);
            if (current == null) return;
            String line = Zealots.strip(raw).trim();
            if (DragonTimer.isPlayerChat(line)) return;
            // Puzzle entry is a short bridge while the new sidebar arrives. Names
            // in general NPC dialogue never enable dungeon features in the village.
            if (line.startsWith("PUZZLE! ") || line.startsWith("PUZZLE SOLVED! ")) {
                seen = true;
                evidenceAt = now;
            }
            if (line.startsWith("Warping to") || line.startsWith("Sending you to")
                    || line.startsWith("Moving you to") || line.startsWith("Teleporting to")) {
                seen = false;
                evidenceAt = 0;
            }
        }

        boolean activeAt(Object current, long now) {
            // A proxy can reuse the world object on a transfer. A known outside
            // sidebar clears immediately; absent evidence expires instead of sticking.
            return current != null && current == world && seen && now >= evidenceAt && now - evidenceAt < 60_000;
        }
    }
}
