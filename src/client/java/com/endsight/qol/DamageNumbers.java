package com.endsight.qol;

import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.HashMap;
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
     * does: a popup is caught the frame it spawns, and anything still standing there two
     * seconds later was never one, so it is dropped and left alone from then on.
     *
     * Both numbers were far looser to begin with, and it showed. Moving around loads and
     * unloads the holograms near you, and every reappearance restarts an entity's tick
     * count - so a hologram that happens to read as a bare number kept being taken for a
     * fresh popup. Catching popups at spawn is what allows the window to be this narrow,
     * and a narrow window is what keeps the scenery out of it.
     */
    private static final int NEW_TICKS = 5;
    private static final int STALE_TICKS = 40;

    private static boolean enabled = false;
    private static String mode = COMPACT;
    /**
     * Whether the plain numbers go regardless of mode. A crit is decorated - the
     * sparkle either side - and a hit that did not crit, or came from an ability, is
     * the bare number. What is worth reading in a fight is the crits, so this drops
     * everything undecorated and leaves the mode to decide what happens to the rest.
     */
    private static boolean critsOnly = false;

    /** Live popups by entity id. Rebuilt every pass, so it cleans up after itself. */
    private static Map<Integer, Tracked> tracked = new HashMap<>();

    private static final class Tracked {
        boolean shortened;
        boolean hidden;
    }

    public static Module module() {
        return new Module("qol.damage", "Damage Numbers",
                "Shorten damage popups, crits only, or hide them.", "Visual",
                () -> enabled, v -> enabled = v,
                List.of(
                        new Setting.Choice("Mode",
                                "Shorten to 8.49M, or hide them.",
                                List.of(COMPACT, HIDE), () -> mode, v -> mode = v),
                        new Setting.Toggle("Crits only",
                                "Hide non-crit and ability damage.",
                                () -> critsOnly, v -> critsOnly = v)));
    }

    /**
     * Run every frame as well as every tick, which is not belt and braces.
     *
     * Minecraft.tick() ticks the entities BEFORE it flushes the packets that create
     * them, so a popup can be spawned, drawn and read several frames before the next
     * tick comes round to it - which is exactly the flip you see when the full number
     * appears and then turns into the short one in front of you. Catching it on the
     * frame it arrives closes that gap.
     *
     * The tick pass is still needed: HUD elements are not drawn at all with the GUI
     * hidden, and a module that quietly stops working on F1 is worse than a late rewrite.
     */
    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> scan());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("endsight", "damage"),
                (g, delta) -> scan());
    }

    // -- the scan --------------------------------------------------------------

    private static void scan() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.level == null || mc.player == null) {
            if (!tracked.isEmpty()) tracked = new HashMap<>();
            return;
        }

        long now = System.currentTimeMillis();
        Map<Integer, Tracked> next = new HashMap<>();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;

            Tracked known = tracked.get(e.getId());
            if (known == null) {
                if (!isCandidate(e) || e.tickCount > NEW_TICKS) continue;

                Component text = text(e);
                String plain = plain(text);
                if (plain == null || plain.isBlank()) continue;
                if (!DAMAGE.matcher(plain).matches()) continue;

                // Decided once, on the frame it spawns, and never revisited. A popup is
                // dead inside a second, so there is no state worth re-deciding - and a
                // decision that cannot change is one that cannot flicker.
                known = new Tracked();
                if (HIDE.equals(mode) || (critsOnly && !isCrit(plain))) hide(e, known);
                else shorten(e, known);

            } else if (e.tickCount > STALE_TICKS) {
                // Still here two seconds on, so it was never a damage popup.
                continue;
            }

            next.put(e.getId(), known);
        }
        tracked = next;
    }

    /** Decorated at all - anything left once the number, its punctuation and spaces are gone. */
    private static boolean isCrit(String plain) {
        return !plain.replaceAll("[\\d,.\\s]", "").isEmpty();
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
    private static void hide(Entity e, Tracked t) {
        if (t.hidden) return;
        t.hidden = true;
        if (e instanceof Display.TextDisplay td) {
            td.getEntityData().set(Display.DATA_VIEW_RANGE_ID, 0f);
        } else {
            e.setCustomNameVisible(false);
        }
    }

    private static void shorten(Entity e, Tracked t) {
        if (t.shortened) return;
        t.shortened = true;
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
}
