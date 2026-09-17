package com.endsight.zealots;

import com.endsight.dragons.DragonTimer;
import com.endsight.hud.Area;
import com.endsight.hud.HudLayout;
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
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
 * landed on. A right-click of the scythe is the cast - the Frozen Scythe says nothing in
 * chat either way. An earlier version here treated a click as provisional until the
 * server had a chance to refuse it with "This ability is on cooldown"; a twelve-minute
 * chat log settled that: 1,953 scythe casts, none refused, a median 200ms apart, which
 * is simply the rate a held right-click repeats at. All 168 refusals in that session
 * were the Giant's Sword. So a scythe click is a cast, and the provisional step only
 * still earns its place for the sword - and for the roses: the Flower of Truth and the
 * Bouquet of Lies are casts too, but with a real one-second cooldown, so under a held
 * click most clicks ARE refused, and each refusal withdraws the strike it belongs to.
 *
 * A rose is also the one strike that can only kill so much. A bolt pierces and a swing
 * sweeps, so anything dying in their zone is theirs; a rose homes onto one zealot and
 * then bounces to a couple more, each within a few blocks of the last - three kills
 * a cast, and the Bouquet throws three roses. The first version gave a rose the
 * bolt's open zone, and looking at a cluster while someone else cleared it counted
 * their kills - one cast was explaining six deaths. Now a cast names the zealot it
 * most likely homed onto at the moment of the click, that one is credited first, the
 * next credits only go to deaths within bounce range of the LAST one credited, and
 * once the three are spent the cast explains nothing else.
 *
 * The Giant's Sword is the other way round: it says nothing on the click and "You hear
 * something falling from the sky." when the cast lands, so that line is its trigger.
 *
 * A death is counted when it follows one of those closely enough in time and space
 * that nothing else explains it. The windows are short on purpose; someone else's kill
 * landing in the same spot in the same moment is possible, but rare enough not to move
 * an hourly rate.
 *
 * A death is a death animation or a removal at close range. Range matters: a zealot
 * unloading forty blocks away is you walking off, not it dying.
 */
public final class ZealotTracker {

    private ZealotTracker() {
    }

    // Verified against a full evening's log, section codes stripped.
    private static final String SWORD_CAST = "You hear something falling from the sky";
    private static final String COOLDOWN = "This ability is on cooldown";
    /** How long the server gets to refuse a click before the click is trusted. */
    private static final long REFUSE_MS = 400;
    private static final String EYE = "RARE DROP! (Summoning Eye)";
    private static final String GOLDEN = "EPIC DROP! Golden Eye";

    /** A swing kills what it hit and what stood within this of it, inside this long. */
    private static final double SWEEP = 6.0;
    private static final long SWEEP_MS = 1_500;
    /**
     * The scythe's bolts fly down the line you are looking and hit the first thing they
     * meet, so its kill zone is that line - anything dying this close to any point of
     * it, this soon after. The cooldown is a fifth of a second, so under a held click
     * there is a fresh line every few frames and the zone simply follows your aim.
     */
    private static final double BOLT = 6.0;
    private static final long BOLT_MS = 1_500;
    /**
     * A rose, measured from a recorded session of nine casts: the first kill lands
     * 170-460ms after the click and within three blocks of the aim line, then the rose
     * bounces on, each next kill 110-240ms later and 3.4-5.1 blocks from the one
     * before, three kills in all. Every zealot that died later than that, or further
     * off, was someone else's. So the zone is the line, a little wider than the bolt's;
     * the first kill has just over a second to arrive; and a bounce has to be quick
     * and close to the last kill, not merely near your aim.
     */
    private static final double ROSE = 4.0;
    private static final long ROSE_MS = 2_400;
    private static final long FIRST_MS = 1_200;
    private static final int ROSE_KILLS = 3;
    private static final double BOUNCE = 6.0;
    private static final long BOUNCE_MS = 600;
    /**
     * The sword drops something on the point you were looking at, and it has to fall
     * first: a wider zone at the far end of the line only, and a longer window.
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

    /**
     * One of your actions that could have killed something: a zone in the world, for a
     * while. The zone is a capsule from one point to another - a swing or a sword drop
     * is a sphere (both ends the same), a bolt is the whole line it flew along.
     *
     * {@code left} is how many deaths this strike may still explain - unlimited for a
     * bolt or a swing, three for a rose - and {@code targetId} the entity it was aimed
     * at, which is credited ahead of anything merely inside the zone. A strike that
     * {@code chains} moves its zone after each kill: the next death it can explain has
     * to be within bounce range of {@code last}, the one it just took.
     */
    private static final class Strike {
        final long at;
        final Vec3 from, to;
        final double radius;
        final long window;
        final boolean chains;
        int targetId;
        int left;
        Vec3 last;
        long lastAt;

        Strike(long at, Vec3 from, Vec3 to, double radius, long window, int targetId, int left, boolean chains) {
            this.at = at;
            this.from = from;
            this.to = to;
            this.radius = radius;
            this.window = window;
            this.targetId = targetId;
            this.left = left;
            this.chains = chains;
        }

        /** How well this strike explains a death there and then: a distance, or NaN for not at all. */
        double explains(int id, Vec3 p, long now) {
            if (left <= 0) return Double.NaN;
            if (last != null) {
                if (now - lastAt > BOUNCE_MS) return Double.NaN;
                double d = p.distanceTo(last);
                return d <= BOUNCE ? d : Double.NaN;
            }
            if (chains && now - at > FIRST_MS) return Double.NaN;
            if (targetId == id) return -1;
            double d = distanceTo(p);
            return d <= radius ? d : Double.NaN;
        }

        void spend(Vec3 p, long now) {
            if (left != Integer.MAX_VALUE) left--;
            if (chains) {
                last = p;
                lastAt = now;
            }
        }

        long at() {
            return at;
        }

        long window() {
            return window;
        }

        double distanceTo(Vec3 p) {
            Vec3 d = to.subtract(from);
            double len2 = d.lengthSqr();
            if (len2 < 1e-9) return p.distanceTo(from);
            double t = Math.max(0, Math.min(1, p.subtract(from).dot(d) / len2));
            return p.distanceTo(from.add(d.scale(t)));
        }
    }

    private static final List<Strike> strikes = new ArrayList<>();
    private static final Set<Integer> counted = new LinkedHashSet<>();
    /** The last scythe click, until the server has had its say about it. */
    private static Strike provisional;

    public static Module module() {
        return new Module("zealot.tracker", "Zealot Tracker",
                "Zealot kills, eyes and golden eyes, with rates.", "Trackers",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Slider("Hide when idle",
                                "Fade out after this long idle. 0 never.",
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
            if (enabled && Zealots.isTracked(entity)) {
                Vec3 at = entity.position();
                strikes.add(new Strike(System.currentTimeMillis(), at, at, SWEEP, SWEEP_MS, entity.getId(), Integer.MAX_VALUE, false));
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            // Main hand only: vanilla tries the off hand too when the main hand passes,
            // which would make every click two strikes.
            if (enabled && hand == InteractionHand.MAIN_HAND) {
                int roses = Zealots.roses(player);
                if (Zealots.holdingScythe(player)) provisional = bolt(player, BOLT, BOLT_MS, Integer.MAX_VALUE, false);
                else if (roses > 0) provisional = roses(player, roses);
            }
            return InteractionResult.PASS;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && enabled) onLine(plain(message));
        });
        // A line another module hid - Ability Spam folds the cooldown refusals - still
        // has to reach the refusal check, or a hidden refusal leaves a dead click credited.
        ClientReceiveMessageEvents.GAME_CANCELED.register((message, overlay) -> {
            if (!overlay && enabled) onLine(plain(message));
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!enabled || mc.player == null || !Zealots.isTracked(entity)) return;
            if (entity.position().distanceTo(mc.player.position()) <= DEATH_RANGE) died(entity);
        });
        ClientTickEvents.END_CLIENT_TICK.register(ZealotTracker::tick);

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "zealots"),
                (g, delta) -> draw(g));
        HudLayout.register("zealot.tracker", "Zealot Tracker", 0.006f, 0.489f,
                (g, font, x, y, sample) -> drawAt(g, font, x, y, sample));
    }

    // ── reading ───────────────────────────────────────────────────────────────

    private static void onLine(String line) {
        if (DragonTimer.isPlayerChat(line)) return;
        if (line.startsWith(COOLDOWN)) {
            // The click the server just refused did not fire. Withdrawn, so nothing that
            // dies in front of you in the next three seconds gets pinned on it.
            if (provisional != null && System.currentTimeMillis() - provisional.at() <= REFUSE_MS) {
                strikes.remove(provisional);
                strikes.removeAll(provisionalRoses);
            }
            provisional = null;
            provisionalRoses = List.of();
        } else if (line.contains(SWORD_CAST)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) drop(mc.player);
        } else if (line.contains(EYE)) {
            eyes++;
            touch();
        } else if (line.contains(GOLDEN)) {
            golden++;
            touch();
        }
    }

    /** A cast down your aim: the line from your eyes to whatever your aim lands on. */
    private static Strike bolt(Player player, double radius, long window, int credits, boolean chains) {
        Vec3 impact = player.pick(PICK_RANGE, 1f, false).getLocation();
        Strike s = new Strike(System.currentTimeMillis(), player.getEyePosition(), impact, radius, window, -1, credits, chains);
        strikes.add(s);
        return s;
    }

    /**
     * Roses: one chained strike per rose thrown, each aimed at its own zealot.
     *
     * The zealots named are the ones closest to your aim line, nearer ones first when
     * two are close - which is what "homes onto the first enemy it reaches" comes to
     * from where you stand; a second rose takes the second-closest. Named at the click,
     * because by the time the death arrives you have looked somewhere else. Returns
     * the first, which is the one a cooldown refusal withdraws; the rest are withdrawn
     * with it.
     */
    private static Strike roses(Player player, int count) {
        List<Strike> thrown = new ArrayList<>();
        for (int i = 0; i < count; i++) thrown.add(bolt(player, ROSE, ROSE_MS, ROSE_KILLS, true));
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            List<Entity> near = new ArrayList<>();
            Strike line = thrown.get(0);
            for (Entity e : mc.level.entitiesForRendering()) {
                if (Zealots.isTracked(e) && line.distanceTo(e.position()) <= ROSE) near.add(e);
            }
            near.sort((a, b) -> Double.compare(score(line, a), score(line, b)));
            for (int i = 0; i < count && i < near.size(); i++) thrown.get(i).targetId = near.get(i).getId();
        }
        provisionalRoses = thrown;
        return thrown.get(0);
    }

    private static double score(Strike line, Entity e) {
        return line.distanceTo(e.position()) + 0.05 * e.position().distanceTo(line.from);
    }

    /** The whole last throw, so a refusal takes every rose of a Bouquet back, not just the first. */
    private static List<Strike> provisionalRoses = List.of();

    /**
     * A sword drop: a sphere at the far end of your aim, and nothing along the way.
     *
     * Centred there rather than on you, so a zealot beside you is not in the blast and
     * one at the far end of your aim is. The confirming line arrives within a few frames
     * of the click, so the look direction at that moment is still the one that aimed it.
     */
    private static void drop(Player player) {
        Vec3 impact = player.pick(PICK_RANGE, 1f, false).getLocation();
        strikes.add(new Strike(System.currentTimeMillis(), impact, impact, IMPACT, IMPACT_MS, -1, Integer.MAX_VALUE, false));
    }

    // ── counting ──────────────────────────────────────────────────────────────

    private static void tick(Minecraft mc) {
        if (!enabled || mc.level == null || mc.player == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof LivingEntity le && le.isDeadOrDying() && Zealots.isTracked(e)) died(e);
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
        boolean mine = ours(e.getId(), e.position(), System.currentTimeMillis());
        if (mine) {
            kills++;
            touch();
        }
    }

    /**
     * The strike that best explains this death, if any, with a credit spent on it: the
     * one that named this entity, else the nearest zone it fell inside.
     */
    private static boolean ours(int id, Vec3 at, long now) {
        Strike best = null;
        double bestD = Double.MAX_VALUE;
        for (Strike s : strikes) {
            if (now - s.at() > s.window()) continue;
            double d = s.explains(id, at, now);
            if (!Double.isNaN(d) && d < bestD) {
                bestD = d;
                best = s;
            }
        }
        if (best == null) return false;
        best.spend(at, now);
        return true;
    }

    // ── session clock, same rules as the slayer tracker ───────────────────────

    /**
     * A gap longer than this is not farming, and comes off the clock.
     *
     * Thirty seconds, and deliberately not the "Hide when idle" slider it used to share:
     * that is in minutes, so a stop for a dragon or a trip to the bank counted as farming
     * time and quietly dragged the hourly rate down. Thirty is short enough to catch
     * those and still long enough never to fire mid-nest - the same log ran at about
     * 0.8 zealot deaths a second, and the longest gap between two of your own kills in
     * twelve minutes was nowhere near it.
     */
    private static final long AFK_MS = 30_000;

    private static void touch() {
        long now = System.currentTimeMillis();
        if (sessionStart == 0) {
            sessionStart = now;
        } else if (lastActivity != 0) {
            long gap = now - lastActivity;
            if (gap > AFK_MS) sessionStart += gap;      // a break is not farming time
        }
        lastActivity = now;
    }

    private static long idleMs() {
        return (long) (Math.max(1, hideAfterMin) * 60_000);
    }

    /**
     * Time farming, with the break you are in right now already taken off.
     *
     * The correction in touch() only lands when the next kill arrives, so on its own the
     * readout would keep climbing all the way through a break and then jump back. This
     * makes the clock stop as the break passes thirty seconds, which is what someone
     * watching the rate expects to see.
     */
    private static long elapsedMs() {
        if (sessionStart == 0) return 0;
        long now = System.currentTimeMillis();
        long gap = lastActivity == 0 ? 0 : now - lastActivity;
        return (gap > AFK_MS ? lastActivity : now) - sessionStart;
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
        if (!Area.end()) return;
        if (hideAfterMin > 0 && (lastActivity == 0
                || System.currentTimeMillis() - lastActivity > idleMs())) return;

        HudLayout.draw("zealot.tracker", g, mc.font, false);
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
            Readout.tick(g, x, y, w, Theme.accent());
            Draw.text(g, font, title, Readout.left(x), y, Theme.muted());
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
