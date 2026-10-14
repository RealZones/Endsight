package com.endsight.dungeon;

import com.endsight.dragons.DragonTimer;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * How long until the Blood Room actually spawns something.
 *
 * The Watcher talks for ten seconds first, and the run is dead time until the last
 * line lands. This counts it down and says when the mob is really coming, taking the
 * door as the start and the named line as the truth.
 *
 * The countdown is a single HUD line while the Watcher talks.
 */
public final class BloodRoom {
    private static final String ID = "dungeon.bloodRoom";

    private static boolean enabled = true, countdown = true;
    private static long openedAt;

    private BloodRoom() { }

    public static Module module() {
        return new Module(ID, "Blood Room", "Counts down the Watcher's intro to the first real spawn.",
                "Dungeons", () -> enabled, value -> { enabled = value; if (!value) reset(); }, List.of(
                new Setting.Toggle("Timer", "Show the countdown on screen while the Watcher talks.",
                        () -> countdown, value -> countdown = value),
                new Setting.Action("Position", "Move the timer.", "Move", HudPlacementScreen::open)));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(BloodRoom::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !DungeonContext.active()) return;
            String line = BloodRoomLogic.plain(message.getString());
            if (DragonTimer.isPlayerChat(line)) return;

            if (BloodRoomLogic.door(line)) {
                openedAt = System.currentTimeMillis();
                long expected = BloodRoomLogic.expected();
                mc.player.sendSystemMessage(Component.literal("§4Blood Room §7- first spawn in about §f"
                        + String.format("%.0fs", expected / 1000.0)
                        + (BloodRoomLogic.samples() == 0 ? " §8(estimate)" : "")));
                return;
            }
            String mob = BloodRoomLogic.spawn(line);
            if (mob == null || openedAt == 0) return;
            long took = System.currentTimeMillis() - openedAt;
            BloodRoomLogic.measured(took);
            mc.player.sendSystemMessage(Component.literal("§4Blood Room §7- §c" + mob
                    + " §7is up §8(" + String.format("%.1fs", took / 1000.0) + " after the door)"));
            reset();
        });
        // One line, not a card. The thing worth seeing is a number going down, and
        // every panel this mod grew around a single value has been taken back out.
        HudLayout.register(ID, "Blood Room", .5f, .36f, (g, font, x, y, sample) -> {
            double left = sample ? 4.2 : remaining() / 1000.0;
            if (!sample && left <= 0) return new int[]{0, 0};
            String text = String.format("%.1fs", left);
            // HudLayout measures with null graphics before rendering, including in the editor.
            if (g != null) g.text(font, text, x, y, left <= 3 ? Theme.neg() : Theme.text(), true);
            return new int[]{font.width(text), 9};
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "blood_room"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (enabled && countdown && openedAt != 0 && !mc.options.hideGui && DungeonContext.active()) {
                HudLayout.draw(ID, g, mc.font, false);
            }
        });
    }

    /** Milliseconds until the spawn is expected, or zero when nothing is running. */
    private static long remaining() {
        if (openedAt == 0) return 0;
        return Math.max(0, BloodRoomLogic.expected() - (System.currentTimeMillis() - openedAt));
    }

    /** The timer clears itself if the spawn line never lands - a left room, a reconnect. */
    private static void tick(Minecraft mc) {
        if (openedAt == 0) return;
        if (mc.level == null || !DungeonContext.active()) { reset(); return; }
        if (System.currentTimeMillis() - openedAt > BloodRoomLogic.expected() + 30_000) reset();
    }

    private static void reset() {
        openedAt = 0;
    }
}
