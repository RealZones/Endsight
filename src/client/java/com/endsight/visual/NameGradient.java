package com.endsight.visual;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Animates the server-supplied gradient, so recipients need no separate colour-sync service.
 *
 * Always on, and deliberately not a module: it is not a feature anyone picks, it is how
 * that name looks to everyone running the mod. 1.2.0 shipped it as a card in the menu with
 * its own switch and colour pickers, and players asked why it was an option. The colours
 * are set in game with /gradient, and this follows them.
 */
public final class NameGradient {
    /** Who gets it, as hashes of the lower-case name, so the list does not spell anyone out. */
    private static final long[] NAMES = {0x6F8122120A9CD2E5L, 0xED5AF48CF0DF0517L};
    private static final long HASH_START = 0xcbf29ce484222325L, HASH_STEP = 0x100000001b3L;
    /**
     * The gradient the server gives each of them right now, or -1 until one has been seen.
     *
     * Every line used to animate between the colours it arrived in, and a nametag with
     * none of its own fell back to a purple written into this file - so after a /gradient
     * the chat already on screen kept the old gradient, and anyone who had not yet seen a
     * coloured line got one the server never gave. The tab list holds the name as it is
     * now and the server re-sends that entry when the gradient changes, so it is the
     * source; a line arriving in chat is the next best, for when they are not in your tab.
     * Every name on screen is drawn in these, the old lines included.
     */
    private static final int[] liveFirst = {-1, -1}, liveSecond = {-1, -1};
    private static final Component[] lastTab = new Component[NAMES.length];
    private static long tabCheckedAt;
    private static final WeakHashMap<FormattedCharSequence, Plan> CACHE = new WeakHashMap<>();
    private static final WeakHashMap<FormattedCharSequence, Plan> COMPONENT_CACHE = new WeakHashMap<>();
    private static final WeakHashMap<FormattedCharSequence, Plan> NAMETAG_CACHE = new WeakHashMap<>();
    private static final Plan NONE = new Plan(List.of(), -1, 0, 0, 0, -1, -1, 0);

    private record Glyph(int index, Style style, int codepoint) {}
    /**
     * A name to animate: where it starts and how long it is, its gradient, and the rank tag
     * just in front of it ("[DRAGON]"), which fades with it as one wave from the bracket on.
     * The tag was left as the server sent it until Fear asked for it to fade too.
     */
    private record Plan(List<Glyph> glyphs, int start, int a, int b, int length, int who, int tagStart, int tagLength) {
        boolean animates(int index) {
            return index >= start && index < start + length || tagLength > 0 && index >= tagStart && index < tagStart + tagLength;
        }
        /** Letters counted from the tag's bracket when there is one, so tag and name are one wave. */
        int letter(int index) { return index - (tagLength > 0 ? tagStart : start); }
    }

    private NameGradient() {}

    /** On this server players' chat arrives as system lines, so GAME is where a new one shows up. */
    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) observe(message);
        });
    }

    /** Read their tab entries again if one has been replaced - at most twice a second. */
    private static void refreshLive() {
        long now = System.currentTimeMillis();
        if (now - tabCheckedAt < 500) return;
        tabCheckedAt = now;
        Minecraft mc = Minecraft.getInstance();
        var connection = mc == null ? null : mc.getConnection();
        if (connection == null) return;
        for (var info : connection.getOnlinePlayers()) {
            int who = who(info.getProfile().name());
            Component tab = info.getTabListDisplayName();
            if (who < 0 || tab == null || tab == lastTab[who]) continue;
            lastTab[who] = tab;
            observe(tab);
        }
    }

    private static long hash(long hash, int cp) {
        return (hash ^ Character.toLowerCase(cp)) * HASH_STEP;
    }

    private static int who(long hash) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i] == hash) return i;
        return -1;
    }

    private static int who(String name) {
        long hash = HASH_START;
        for (int i = 0; i < name.length(); i++) hash = hash(hash, name.charAt(i));
        return who(hash);
    }

    /** Take the gradient off a server line that colours one of them as one; anything else is ignored. */
    static void observe(Component line) {
        if (line == null) return;
        Plan plan = plan(glyphs(line));
        if (plan.start < 0 || plan.a < 0) return;
        liveFirst[plan.who] = plan.a;
        liveSecond[plan.who] = plan.b;
    }

    public static FormattedCharSequence animate(FormattedCharSequence original) {
        if (original == null) return original;
        refreshLive();
        Plan plan = CACHE.get(original);
        if (plan == null) {
            List<Glyph> glyphs = new ArrayList<>();
            // Whether the walk got to the end is told by the count, not by what accept()
            // returns: sequences disagree on it. The client's chat lines answer true, but a
            // plain Language's visual order answers false when it finishes, and the chat
            // path then skipped every line - the checks found it once they looked at names.
            original.accept((index, style, cp) -> {
                if (glyphs.size() >= 2048) return false;
                glyphs.add(new Glyph(index, style, cp));
                return true;
            });
            plan = glyphs.size() < 2048 ? plan(glyphs) : NONE;
            if (CACHE.size() >= 512) CACHE.clear();
            CACHE.put(original, plan);
        }
        if (plan.start < 0) return original;
        Plan result = plan;
        long now = System.currentTimeMillis();
        return sink -> {
            for (int i = 0; i < result.glyphs.size(); i++) {
                Glyph glyph = result.glyphs.get(i);
                Style style = recolour(result, glyph.style, i, now);
                if (!sink.accept(glyph.index, style, glyph.codepoint)) return false;
            }
            return true;
        };
    }

    public static Component animate(Component original) {
        if (original == null) return original;
        refreshLive();
        // Vanilla invalidates this key when a mutable component changes. Negative matches
        // are cached too, so the tab list does not rebuild every other player's text each frame.
        FormattedCharSequence key = original.getVisualOrderText();
        Plan plan = COMPONENT_CACHE.get(key);
        if (plan == null) {
            plan = plan(glyphs(original));
            if (COMPONENT_CACHE.size() >= 512) COMPONENT_CACHE.clear();
            COMPONENT_CACHE.put(key, plan);
        }
        if (plan.start < 0) return original;
        return render(plan);
    }

    public static Component nametag(Component original, net.minecraft.world.entity.player.Player player) {
        if (original == null || who(player.getGameProfile().name()) < 0) return original;
        refreshLive();
        return nametagText(original);
    }

    static Component nametagText(Component original) {
        FormattedCharSequence key = original.getVisualOrderText();
        Plan plan = NAMETAG_CACHE.get(key);
        if (plan == null) {
            plan = plan(glyphs(original), true);
            if (NAMETAG_CACHE.size() >= 128) NAMETAG_CACHE.clear();
            NAMETAG_CACHE.put(key, plan);
        }
        // A plain tag has no colours of its own; until the server's are known it stays as sent.
        if (plan.start < 0 || (plan.a < 0 && liveFirst[plan.who] < 0)) return original;
        return render(plan);
    }

    private static List<Glyph> glyphs(Component component) {
        List<Glyph> glyphs = new ArrayList<>();
        component.visit((style, text) -> {
            text.codePoints().forEach(cp -> glyphs.add(new Glyph(glyphs.size(), style, cp)));
            return Optional.empty();
        }, Style.EMPTY);
        return glyphs;
    }

    private static Component render(Plan plan) {
        MutableComponent result = Component.empty();
        long now = System.currentTimeMillis();
        for (int i = 0; i < plan.glyphs.size(); i++) {
            Glyph glyph = plan.glyphs.get(i);
            result.append(Component.literal(new String(Character.toChars(glyph.codepoint)))
                    .setStyle(recolour(plan, glyph.style, i, now)));
        }
        return result;
    }

    /** The server's current gradient when it is known; the line's own until then. */
    private static Style recolour(Plan plan, Style original, int index, long time) {
        if (!plan.animates(index)) return original;
        int first = liveFirst[plan.who], a = first >= 0 ? first : plan.a, b = first >= 0 ? liveSecond[plan.who] : plan.b;
        if (a < 0) return original;
        return original.withColor(colour(a, b, plan.letter(index), time));
    }

    /** "[TAG] " right before the name: where its bracket is and how long it is, or {-1, 0}. */
    private static int[] tag(List<Glyph> glyphs, int start) {
        if (start < 3 || glyphs.get(start - 1).codepoint != ' ' || glyphs.get(start - 2).codepoint != ']') return new int[]{-1, 0};
        for (int k = start - 3; k >= 0 && k >= start - 24; k--) {
            int cp = glyphs.get(k).codepoint;
            if (cp == '[') return new int[]{k, start - 1 - k};
            if (cp == ']' || cp == ' ') break;
        }
        return new int[]{-1, 0};
    }

    private static Plan plan(List<Glyph> glyphs) {
        return plan(glyphs, false);
    }

    private static Plan plan(List<Glyph> glyphs, boolean nametag) {
        for (int start = 0; start < glyphs.size(); start++) {
            if (!usernameChar(glyphs.get(start).codepoint)) continue;
            if (start > 0 && usernameChar(glyphs.get(start - 1).codepoint)) continue;
            int end = start;
            long hash = HASH_START;
            while (end < glyphs.size() && usernameChar(glyphs.get(end).codepoint)) hash = hash(hash, glyphs.get(end++).codepoint);
            int who = who(hash);
            if (who < 0) continue;
            var a = glyphs.get(start).style.getColor();
            var b = glyphs.get(end - 1).style.getColor();
            // Plain mentions and rank text are untouched. The server is the source of both endpoints.
            int[] tag = tag(glyphs, start);
            if (a != null && b != null && a.getValue() != b.getValue())
                return new Plan(List.copyOf(glyphs), start, a.getValue(), b.getValue(), end - start, who, tag[0], tag[1]);
            // The entity's actual profile has already been checked by nametag(). Chat mentions
            // still require a server gradient; a plain player nametag can use the server's colours.
            if (nametag) return new Plan(List.copyOf(glyphs), start, -1, -1, end - start, who, tag[0], tag[1]);
        }
        return NONE;
    }

    private static boolean usernameChar(int cp) { return Character.isLetterOrDigit(cp) || cp == '_'; }

    static int colour(int a, int b, int letter, long time) {
        double amount = .5 - .5 * Math.cos(2 * Math.PI * ((Math.floorMod(time, 4000) / 4000.0) - letter / 12.0));
        int colour = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int x = (a >>> shift) & 255, y = (b >>> shift) & 255;
            colour |= ((int) Math.round(x + (y - x) * amount)) << shift;
        }
        return colour;
    }
}
