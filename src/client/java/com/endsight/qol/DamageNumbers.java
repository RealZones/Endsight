package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The floating damage numbers, shortened or taken away.
 *
 * Hitting a boss spawns one of these per hit, so spam-clicking buries the fight under a
 * wall of eight-digit numbers that overlap each other and the mob behind them. Two
 * separate things fix that: making each number shorter, and showing fewer of them.
 *
 * Done by editing the entity on THIS client rather than by drawing over it. The server
 * sends the text once when the popup spawns and never touches it again - the thing lives
 * about a second and dies - so a local overwrite sticks for as long as the popup exists,
 * and nothing ever has to be put back. It also means no renderer is hooked, which is the
 * part that breaks on every Minecraft version.
 *
 * Nothing here can reach another client or change what the server thinks happened.
 */
public final class DamageNumbers {

    private DamageNumbers() {
    }

    /**
     * What a damage popup says, once the colour codes are stripped.
     *
     * A number, and around it anything that is not a letter - the crit form on this
     * server is a sparkle either side, "*8,486,084*". The two exclusions are what keep
     * this off everything else that floats: a letter rules out every mob nametag, and a
     * slash rules out the health bars, which are the one nametag that is nearly all
     * digits ("[Lv55] Zealot 13000/13000").
     */
    private static final Pattern DAMAGE = Pattern.compile("^[^\\p{L}/]*\\d[\\d,.]*[^\\p{L}/]*$");

    /** The digit run inside that text, which is the only part that gets rewritten. */
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private static final char SECTION = '§';

    private static final String COMPACT = "Compact";
    private static final String HIDE = "Hide";

    /**
     * How new an entity has to be to be called a damage popup, and how long one is
     * followed for afterwards.
     *
     * A popup exists for about a second. A hologram - a shop price, a leaderboard - can
     * be a bare number too, and no amount of reading the text tells the two apart. Age
     * does: anything still standing there five seconds later was never a popup, so it is
     * dropped and can no longer be hidden as an older popup. The two-second entry
     * gate means a hologram is only ever a candidate in the moment it comes into range,
     * and never again after that.
     */
    private static final int NEW_TICKS = 40;
    private static final int STALE_TICKS = 100;

    private static boolean enabled = false;
    private static String mode = COMPACT;
    private static boolean onlyNewest = true;

    /** Live popups by entity id. Rebuilt every tick, so it cleans up after itself. */
    private static Map<Integer, Tracked> tracked = new HashMap<>();

    /** What floating text has actually said, for the dump. Keyed by the text itself. */
    private static final Map<String, String> sightings = new LinkedHashMap<>();
    private static final int SIGHTING_CAP = 150;

    private static final class Tracked {
        final long born;
        boolean shortened;
        boolean hidden;

        Tracked(long born) {
            this.born = born;
        }
    }

    public static Module module() {
        return new Module("qol.damage", "Damage Numbers",
                "Shortens the damage popups, or takes them away.", "Quality of Life",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Mode",
                                "Shorten them to 8.49M, or remove them completely.",
                                List.of(COMPACT, HIDE), () -> mode, v -> mode = v),
                        new Setting.Toggle("Only newest",
                                "Show one at a time, so they cannot stack up.",
                                () -> onlyNewest, v -> onlyNewest = v),
                        new Setting.Action("Dump popups",
                                "Writes what the floating text actually said to a file.",
                                "Dump", DamageNumbers::dump)));
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(DamageNumbers::onTick);
    }

    // -- the scan --------------------------------------------------------------

    private static void onTick(Minecraft mc) {
        if (!enabled || mc.level == null || mc.player == null) {
            if (!tracked.isEmpty()) tracked = new HashMap<>();
            return;
        }

        long now = System.currentTimeMillis();
        Map<Integer, Tracked> next = new HashMap<>();
        List<Entity> live = new ArrayList<>();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;

            Tracked known = tracked.get(e.getId());
            if (known == null) {
                if (!isCandidate(e) || e.tickCount > NEW_TICKS) continue;

                Component text = text(e);
                String plain = plain(text);
                if (plain == null || plain.isBlank()) continue;
                see(e, text, plain);
                if (!DAMAGE.matcher(plain).matches()) continue;
                known = new Tracked(now);

            } else if (e.tickCount > STALE_TICKS) {
                // Still here five seconds on, so it was never a damage popup. Dropped
                // rather than counted among the ones a newer popup is allowed to hide.
                continue;
            }

            next.put(e.getId(), known);
            live.add(e);
        }
        tracked = next;

        // Newest first, so the number still worth reading is the one that survives.
        live.sort(Comparator.comparingLong((Entity e) -> tracked.get(e.getId()).born).reversed());

        boolean hideAll = HIDE.equals(mode);
        int keep = onlyNewest ? 1 : Integer.MAX_VALUE;

        for (int i = 0; i < live.size(); i++) {
            Entity e = live.get(i);
            Tracked t = tracked.get(e.getId());

            if (hideAll || i >= keep) {
                if (!t.hidden) {
                    hide(e);
                    t.hidden = true;
                }
            } else if (!t.shortened) {
                shorten(e);
                t.shortened = true;
            }
        }
    }

    /**
     * The two ways a server can float text over a mob.
     *
     * A text_display is the modern one and an armour stand wearing a name is the old
     * one, and which of them this server uses has never been caught in a dump - a popup
     * is gone long before the button can be pressed. So both are handled. The
     * name-visible test on the armour stand is what keeps the pets out; they carry a
     * name they never show.
     */
    private static boolean isCandidate(Entity e) {
        if (e instanceof Display.TextDisplay) return true;
        return e instanceof ArmorStand && e.isCustomNameVisible();
    }

    private static Component text(Entity e) {
        if (e instanceof Display.TextDisplay td) {
            return td.getEntityData().get(Display.TextDisplay.DATA_TEXT_ID);
        }
        return e.getCustomName();
    }

    /**
     * Stops it drawing at all.
     *
     * A text_display is switched off by its view range rather than by blanking its text:
     * empty text still draws its background, so a blanked popup leaves a small dark pill
     * hanging in the air, which is a worse kind of visible than the number was. View
     * range zero fails the renderer's own distance test at every distance, so the whole
     * entity - text, background and shadow - is skipped.
     */
    private static void hide(Entity e) {
        if (e instanceof Display.TextDisplay td) {
            td.getEntityData().set(Display.DATA_VIEW_RANGE_ID, 0f);
        } else {
            e.setCustomNameVisible(false);
        }
    }

    private static void shorten(Entity e) {
        Component original = text(e);
        if (original == null) return;
        String raw = original.getString();
        String rewritten = compact(raw);
        if (rewritten == null || rewritten.equals(raw)) return;

        Component out = Component.literal(rewritten).setStyle(original.getStyle());
        if (e instanceof Display.TextDisplay td) {
            td.getEntityData().set(Display.TextDisplay.DATA_TEXT_ID, out);
        } else {
            e.setCustomName(out);
        }
    }

    // -- the rewrite -----------------------------------------------------------

    /**
     * The same line with its number shortened and its colours kept.
     *
     * The colours are why this is not a one-line replace. The server writes the number a
     * character at a time in a gradient - white through yellow and orange to red and
     * back - so dropping the codes and printing "8.49M" would come out flat and look
     * nothing like the rest of the game. Instead the codes are replayed in order across
     * the shorter number, sampled evenly, which keeps the shape of the gradient and
     * leaves the sparkles either side of it untouched.
     */
    private static String compact(String raw) {
        List<String> codes = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == SECTION && i + 1 < raw.length()) {
                pending.append(c).append(raw.charAt(++i));
                continue;
            }
            codes.add(pending.toString());
            pending.setLength(0);
            plain.append(c);
        }
        String trailing = pending.toString();

        Matcher m = NUMBER.matcher(plain);
        if (!m.find()) return null;
        int start = m.start(), end = m.end();

        String shortened = format(m.group());
        if (shortened == null || shortened.isEmpty()) return null;

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < start; i++) out.append(codes.get(i)).append(plain.charAt(i));

        int oldLen = end - start, newLen = shortened.length();
        int prev = start - 1;
        for (int i = 0; i < newLen; i++) {
            int src = start + Math.min(oldLen - 1, i * oldLen / newLen);
            // Every code between the last character taken and this one, so a colour
            // change that landed on a digit we dropped is not dropped with it.
            for (int k = prev + 1; k <= src; k++) out.append(codes.get(k));
            out.append(shortened.charAt(i));
            prev = src;
        }
        for (int k = prev + 1; k < end; k++) out.append(codes.get(k));

        for (int i = end; i < plain.length(); i++) out.append(codes.get(i)).append(plain.charAt(i));
        return out.append(trailing).toString();
    }

    private static final String[] UNITS = {"", "K", "M", "B", "T"};

    /**
     * "8,486,084" as "8.49M".
     *
     * Three significant figures, so 8.49M, 17.1M and 847K all come out the same width -
     * which is the point of shortening them at all, since a number that changes width
     * every hit is still something you have to re-read. Anything under a thousand is
     * left alone: "847" is already as short as it gets, and "0.85K" is worse in every
     * way.
     */
    private static String format(String digits) {
        String bare = digits.replace(",", "");
        if (bare.length() > 18) return null;
        double v;
        try {
            v = Double.parseDouble(bare);
        } catch (NumberFormatException e) {
            return null;
        }

        int unit = 0;
        while (Math.abs(v) >= 1000 && unit < UNITS.length - 1) {
            v /= 1000;
            unit++;
        }
        if (unit == 0) return String.format("%.0f", v);

        double a = Math.abs(v);
        int places = a < 10 ? 2 : a < 100 ? 1 : 0;
        return trimZeros(String.format("%." + places + "f", v)) + UNITS[unit];
    }

    private static String trimZeros(String s) {
        if (s.indexOf('.') < 0) return s;
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String plain(Component c) {
        return c == null ? null : c.getString().replaceAll(SECTION + "[0-9A-Fa-fK-Ok-orRxX]", "");
    }

    // -- the dump --------------------------------------------------------------

    /**
     * Remembers a piece of floating text the first time it is seen.
     *
     * Everything is recorded, not only what matched, because the question this answers
     * is why something did NOT match. A snapshot dump cannot answer it - a popup is gone
     * in under a second, so by the time the button is pressed there is nothing left to
     * look at. Keyed on the text so a room of identical health bars is one line and the
     * one interesting entry is not buried under forty copies of it.
     */
    private static void see(Entity e, Component text, String plain) {
        String raw = text == null ? "" : text.getString();
        if (raw.isBlank() || sightings.containsKey(raw)) return;

        Minecraft mc = Minecraft.getInstance();
        double dist = mc.player == null ? 0 : e.position().distanceTo(mc.player.position());
        sightings.put(raw, String.format("%-26s d=%5.1f  tick=%-4d %s",
                e.getType().toString(), dist, e.tickCount,
                DAMAGE.matcher(plain).matches() ? "MATCH" : "-    "));

        while (sightings.size() > SIGHTING_CAP) {
            sightings.remove(sightings.keySet().iterator().next());
        }
    }

    private static void dump() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        List<String> out = new ArrayList<>();
        out.add("# Endsight damage popup dump");
        out.add("# every piece of floating text seen since the module was switched on");
        out.add("# section signs are written as & so they survive a text editor");
        out.add("");
        for (Map.Entry<String, String> s : sightings.entrySet()) {
            out.add(s.getValue() + "  \"" + s.getKey().replace(SECTION, '&') + "\"");
        }

        Path file = mc.gameDirectory.toPath().resolve("endsight-damage-dump.txt");
        try {
            Files.write(file, out);
            mc.player.sendSystemMessage(Component.literal(
                    "[Endsight] wrote " + sightings.size() + " to " + file.getFileName()));
        } catch (IOException ex) {
            mc.player.sendSystemMessage(Component.literal(
                    "[Endsight] dump failed: " + ex.getMessage()));
        }
    }
}
