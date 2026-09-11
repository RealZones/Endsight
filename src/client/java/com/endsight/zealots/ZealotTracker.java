package com.endsight.zealots;

import com.endsight.dragons.DragonTimer;
import com.endsight.hud.HudLayout;
import com.endsight.hud.HudPlacementScreen;
import com.endsight.hud.Readout;
import com.endsight.ui.Draw;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import com.endsight.ui.Theme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
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
 * Zealot kills and eye drops this session, with rates.
 *
 * Drops are easy: the server tells you, and only you, in chat. Kills are the hard part -
 * it announces nothing when a zealot dies, and a dozen people farm the same nest, so a
 * death near you looks the same whoever caused it.
 *
 * What the client does know is what YOU did. A melee swing reports the exact entity it
 * landed on. The scythe's ability is confirmed by the server with its own line - "You
 * hear something falling from the sky." - which is the cast and not the click: a log of
 * one evening's farming had 152 of those five seconds apart and 3,912 "on cooldown"
 * lines from the clicks in between. A death is counted when it follows one of those
 * closely enough in time and space that nothing else explains it. The windows are short
 * on purpose; someone else's kill landing in the same spot in the same moment is
 * possible, but rare enough not to move an hourly rate.
 *
 * A death is a death animation or a removal at close range. Range matters: a zealot
 * unloading forty blocks away is you walking off, not it dying.
 */
public final class ZealotTracker {

    private ZealotTracker() {
    }

    private static final String MATCH = "Zealot";
    /** Bruisers live in the layer below; nobody farming eyes is counting them. */
    private static final String EXCLUDE = "Bruiser";

    // Verified against a full evening's log, section codes stripped.
    private static final String CAST = "You hear something falling from the sky";
    private static final String EYE = "RARE DROP! (Summoning Eye)";
    private static final String GOLDEN = "EPIC DROP! Golden Eye";

    /** A swing kills what it hit and what stood within this of it, inside this long. */
    private static final double SWEEP = 6.0;
    private static final long SWEEP_MS = 1_500;
    /**
     * The ability drops something on the point you were looking at. Anything dying this
     * close to that point, this soon after, is yours. Longer than the swing window
     * because the thing has to fall first.
     */
    private static final double IMPACT = 10.0;
    private static final long IMPACT_MS = 3_000;
    private static final double PICK_RANGE = 30.0;
    /** A removal closer than this is a death, not you leaving. */
    private static final double DEATH_RANGE = 24.0;

    private static boolean enabled = true;
    private static double hideAfterMin = 3;

    private static int kills;
    private static int eyes;
    private static int golden;
    private static long sessionStart;
    private static long lastActivity;

    /** One of your actions that could have killed something. */
    private record Strike(long at, Vec3 pos, double radius, long window, int targetId) {
    }

    private static final List<Strike> strikes = new ArrayList<>();
    private static final Set<Integer> counted = new LinkedHashSet<>();

    public static Module module() {
        return new Module("zealot.tracker", "Zealot Tracker",
                "Your zealot kills, eye drops and rates for the session.", "Zealots",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Action("Move readout",
                                "Drag it, and every other readout, where you want.",
                                "Move", HudPlacementScreen::open),
                        new Setting.Slider("Hide when idle",
                                "Fade out after this long without a kill or drop. 0 keeps it up.",
                                0, 15, 1, () -> hideAfterMin, v -> hideAfterMin = v, "m"),
                        new Setting.Action("Reset session",
                                "Zero the counts and the clock.",
                                "Reset", ZealotTracker::resetSession)));
    }

    private static void resetSession() {
        kills = 0;
        eyes = 0;
        golden = 0;
        sessionStart = 0;
        lastActivity = 0;
    }

    public static void init() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (enabled && isZealot(entity)) {
                strikes.add(new Strike(System.currentTimeMillis(), entity.position(), SWEEP, SWEEP_MS, entity.getId()));
            }
            return InteractionResult.PASS;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && enabled) onLine(plain(message));
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

    // ── reading ───────────────────────────────────────────────────────────────

    private static void onLine(String line) {
        if (DragonTimer.isPlayerChat(line)) return;
        if (line.contains(CAST)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            // Where it lands is where you were looking; the line arrives within a few
            // frames of the click, so the look direction now is the one that aimed it.
            Vec3 impact = mc.player.pick(PICK_RANGE, 1f, false).getLocation();
            strikes.add(new Strike(System.currentTimeMillis(), impact, IMPACT, IMPACT_MS, -1));
        } else if (line.contains(EYE)) {
            eyes++;
            touch();
        } else if (line.contains(GOLDEN)) {
            golden++;
            touch();
        }
    }

    // ── counting ──────────────────────────────────────────────────────────────

    private static void tick(Minecraft mc) {
        if (!enabled || mc.level == null || mc.player == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof LivingEntity le && le.isDeadOrDying() && isZealot(e)) died(e);
        }
        long now = System.currentTimeMillis();
        strikes.removeIf(s -> now - s.at() > s.window());
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
            if (now - s.at() > s.window()) continue;
            if (s.targetId() == id) return true;
            if (at.distanceTo(s.pos()) <= s.radius()) return true;
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

    /** Per hour, or "-" while the sample is too short to mean anything. */
    private static String perHour(int count) {
        long elapsed = elapsedMs();
        if (elapsed < 60_000) return "-";
        double rate = count / (elapsed / 3_600_000.0);
        return (rate < 10 ? String.format("%.1f", rate) : String.valueOf(Math.round(rate))) + "/h";
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

    /**
     * Three counters, each with its rate beside it, and the clock.
     *
     * Rate on the same row as its count rather than in rows of its own, as the slayer
     * tracker does: that one has one thing to count, this has three, and six rows of
     * numbers stops being a glance.
     */
    private static int[] drawAt(GuiGraphicsExtractor g, Font font, int x, int y, boolean sample) {
        String[][] rows = sample
                ? new String[][]{{"Kills", "736/h", "148"}, {"Eyes", "458/h", "92"},
                                 {"Golden", "14.9/h", "3"}, {"Elapsed", "", "12m04s"}}
                : new String[][]{{"Kills", perHour(kills), String.valueOf(kills)},
                                 {"Eyes", perHour(eyes), String.valueOf(eyes)},
                                 {"Golden", perHour(golden), String.valueOf(golden)},
                                 {"Elapsed", "", sessionStart == 0 ? "-" : secs(elapsedMs())}};

        String title = "ZEALOT TRACKER";
        int labelW = 0, rateW = 0, valueW = 0;
        for (String[] r : rows) {
            labelW = Math.max(labelW, font.width(r[0]));
            rateW = Math.max(rateW, font.width(r[1]));
            valueW = Math.max(valueW, font.width(r[2]));
        }
        int w = Math.max(font.width(title) + 20, 8 + labelW + 12 + rateW + 10 + valueW);
        int h = Readout.ROW_H + 3 + rows.length * (Readout.ROW_H + 2);

        if (g != null) {
            Draw.rect(g, x, y, 2, Readout.ROW_H - 1, Theme.accent());
            Draw.text(g, font, title, x + 8, y, Theme.muted());
            int ry = y + Readout.ROW_H + 3;
            for (String[] r : rows) {
                Draw.text(g, font, r[0], x + 8, ry, Theme.dim());
                Draw.textRight(g, font, r[1], x + w - valueW - 10, ry, Theme.muted());
                Draw.textRight(g, font, r[2], x + w, ry, Theme.text());
                ry += Readout.ROW_H + 2;
            }
        }
        return new int[]{w, h};
    }

    private static String secs(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 60 ? s + "s" : (s / 60) + "m" + String.format("%02d", s % 60) + "s";
    }

    private static String plain(Component c) {
        return c == null ? "" : c.getString().replaceAll("§[0-9A-Fa-fK-Ok-orRxX]", "").trim();
    }
}
