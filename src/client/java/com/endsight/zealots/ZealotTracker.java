package com.endsight.zealots;

import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.hud.Readout;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Zealot kills this session, and the rate.
 *
 * The hard part is not seeing a zealot die - it is knowing it was YOURS. A dozen people
 * farm the same nest, and every zealot that dies near you looks the same whoever swung.
 * The server does not announce common kills, so there is no chat line to read the way
 * the slayer tracker does.
 *
 * What the client does know is what you did. It sees every swing you make and which
 * entity it landed on, and every right-click of the scythe's ability. So a death is
 * counted when it follows one of your own actions closely enough in time and space
 * that nothing else explains it: the thing you hit, or something standing within a
 * scythe's sweep of it, dying inside a second and a half of the swing. The window is
 * short on purpose - a false kill from someone else's swing landing in the same second
 * on the same pack is possible, but rare enough not to move an hourly rate.
 *
 * A death is a death animation or a removal at close range. Range matters: a zealot
 * unloading forty blocks away is you walking off, not it dying, and only a removal
 * well inside tracking range is trusted.
 */
public final class ZealotTracker {

    private ZealotTracker() {
    }

    private static final String MATCH = "Zealot";
    /** Bruisers live in the layer below; nobody farming eyes is counting them. */
    private static final String EXCLUDE = "Bruiser";

    /** How long after one of your actions a death can still be blamed on it. */
    private static final long ATTRIBUTE_MS = 1_500;
    /** A swing kills what it hit and what stood within this of it. */
    private static final double SWEEP = 6.0;
    /** The ability reaches this far down the line you were looking... */
    private static final double BOLT_RANGE = 24.0;
    /** ...within this many degrees of it. */
    private static final double BOLT_CONE = 35.0;
    /** A removal closer than this is a death, not you leaving. */
    private static final double DEATH_RANGE = 24.0;

    private static boolean enabled = true;
    private static double hideAfterMin = 3;

    private static int kills;
    private static long sessionStart;
    private static long lastActivity;

    /** One of your actions that could have killed something. */
    private record Strike(long at, Vec3 pos, Vec3 look, int targetId) {
        boolean melee() {
            return look == null;
        }
    }

    private static final List<Strike> strikes = new ArrayList<>();
    private static final Set<Integer> counted = new LinkedHashSet<>();

    public static Module module() {
        return new Module("zealot.tracker", "Zealot Tracker",
                "Your zealot kills and rate for the session.", "Zealots",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Action("Move readout",
                                "Drag it, and every other readout, where you want.",
                                "Move", HudPlacementScreen::open),
                        new Setting.Slider("Hide when idle",
                                "Fade out after this long without a kill. 0 keeps it up.",
                                0, 15, 1, () -> hideAfterMin, v -> hideAfterMin = v, "m"),
                        new Setting.Action("Reset session",
                                "Zero the kill count and the clock.",
                                "Reset", ZealotTracker::resetSession)));
    }

    private static void resetSession() {
        kills = 0;
        sessionStart = 0;
        lastActivity = 0;
    }

    public static void init() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (enabled && isZealot(entity)) {
                strikes.add(new Strike(System.currentTimeMillis(), entity.position(), null, entity.getId()));
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (enabled && holdingScythe(player)) {
                strikes.add(new Strike(System.currentTimeMillis(), player.getEyePosition(),
                        player.getLookAngle(), -1));
            }
            return InteractionResult.PASS;
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!enabled || mc.player == null || !isZealot(entity)) return;
            if (entity.position().distanceTo(mc.player.position()) <= DEATH_RANGE) died(entity);
        });
        ClientTickEvents.END_CLIENT_TICK.register(ZealotTracker::tick);

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "zealots"),
                (g, delta) -> draw(g));
        HudLayout.register("zealot.tracker", "Zealot Tracker", 0f, 0.5f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    // ── counting ──────────────────────────────────────────────────────────────

    private static void tick(Minecraft mc) {
        if (!enabled || mc.level == null || mc.player == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof LivingEntity le && le.isDeadOrDying() && isZealot(e)) died(e);
        }
        long now = System.currentTimeMillis();
        strikes.removeIf(s -> now - s.at() > ATTRIBUTE_MS * 2);
    }

    /**
     * A zealot has died. Counted once, and only if one of your actions explains it.
     *
     * The id set is what stops the death animation and the removal that follows it
     * twenty ticks later from being two kills.
     */
    private static void died(Entity e) {
        if (!counted.add(e.getId())) return;
        Iterator<Integer> old = counted.iterator();
        while (counted.size() > 512) {
            old.next();
            old.remove();
        }
        if (ours(e.getId(), e.position(), System.currentTimeMillis())) {
            kills++;
            touch();
        }
    }

    private static boolean ours(int id, Vec3 at, long now) {
        for (Strike s : strikes) {
            if (now - s.at() > ATTRIBUTE_MS) continue;
            if (s.melee()) {
                if (s.targetId() == id) return true;
                if (at.distanceTo(s.pos()) <= SWEEP) return true;
            } else {
                Vec3 d = at.subtract(s.pos());
                double dist = d.length();
                if (dist > BOLT_RANGE || dist < 1e-6) continue;
                if (d.scale(1 / dist).dot(s.look()) >= Math.cos(Math.toRadians(BOLT_CONE))) return true;
            }
        }
        return false;
    }

    private static boolean isZealot(Entity e) {
        if (!(e instanceof EnderMan)) return false;
        Component name = e.getCustomName();
        if (name == null) return false;
        String plain = name.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "");
        return plain.contains(MATCH) && !plain.contains(EXCLUDE);
    }

    private static boolean holdingScythe(net.minecraft.world.entity.player.Player player) {
        return player.getMainHandItem().getHoverName().getString().contains("Scythe");
    }

    // ── session clock, same rules as the slayer tracker ───────────────────────

    private static void touch() {
        long now = System.currentTimeMillis();
        if (sessionStart == 0) {
            sessionStart = now;
        } else if (lastActivity != 0) {
            long gap = now - lastActivity;
            if (gap > idleMs()) sessionStart += gap;    // a break is not farming time
        }
        lastActivity = now;
    }

    private static long idleMs() {
        return (long) (Math.max(1, hideAfterMin) * 60_000);
    }

    private static long elapsedMs() {
        return sessionStart == 0 ? 0 : System.currentTimeMillis() - sessionStart;
    }

    /** Kills per hour, or -1 while the sample is too short to mean anything. */
    private static int perHour() {
        long elapsed = elapsedMs();
        if (elapsed < 60_000) return -1;
        return (int) Math.round(kills / (elapsed / 3_600_000.0));
    }

    // ── drawing ───────────────────────────────────────────────────────────────

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (hideAfterMin > 0 && (lastActivity == 0
                || System.currentTimeMillis() - lastActivity > idleMs())) return;

        Font font = mc.font;
        int[] size = drawAt(null, font, 0, 0, false);
        drawAt(g, font,
                HudLayout.x("zealot.tracker", size[0], mc.getWindow().getGuiScaledWidth()),
                HudLayout.y("zealot.tracker", size[1], mc.getWindow().getGuiScaledHeight()),
                false);
    }

    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        int rate = perHour();
        String[][] rows = sample
                ? new String[][]{{"Kills", "148"}, {"Elapsed", "12m04s"}, {"Rate", "736/h"}}
                : new String[][]{{"Kills", String.valueOf(kills)},
                                 {"Elapsed", sessionStart == 0 ? "-" : secs(elapsedMs())},
                                 {"Rate", rate < 0 ? "-" : rate + "/h"}};

        String title = "ZEALOT TRACKER";
        int w = font.width(title) + 20;
        for (String[] r : rows) w = Math.max(w, Readout.width(font, r[0], r[1]));
        int h = Readout.ROW_H + 3 + rows.length * (Readout.ROW_H + 2);

        if (g != null) {
            Draw.rect(g, x, y, 2, Readout.ROW_H - 1, Theme.accent());
            Draw.text(g, font, title, x + 8, y, Theme.muted());
            int ry = y + Readout.ROW_H + 3;
            for (String[] r : rows) {
                Draw.text(g, font, r[0], x + 8, ry, Theme.dim());
                Draw.textRight(g, font, r[1], x + w, ry, Theme.text());
                ry += Readout.ROW_H + 2;
            }
        }
        return new int[]{w, h};
    }

    private static String secs(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 60 ? s + "s" : (s / 60) + "m" + String.format("%02d", s % 60) + "s";
    }
}
