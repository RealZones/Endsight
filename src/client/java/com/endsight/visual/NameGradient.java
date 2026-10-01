package com.endsight.visual;

import com.endsight.hud.Toast;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
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

/** Animates the server-supplied gradient, so recipients need no separate colour-sync service. */
public final class NameGradient {
    private static final String NAME = "ImFear";
    private static boolean enabled = true;
    /** What the pickers hold for Apply. Overwritten by the server's gradient whenever it changes. */
    private static int first = 0xFF500740, second = 0xFF5100FF;
    /**
     * The gradient the server gives ImFear right now, or -1 until one has been seen.
     *
     * Every line used to animate between the colours it arrived in, and a nametag with
     * none of its own fell back to a purple written into this file - so after a /gradient
     * the chat already on screen kept the old gradient, and anyone who had not yet seen a
     * coloured line got one the server never gave. The tab list holds the name as it is
     * now and the server re-sends that entry when the gradient changes, so it is the
     * source; a line arriving in chat is the next best, for when ImFear is not in your tab.
     * Every name on screen is drawn in these, the old lines included.
     */
    private static int liveFirst = -1, liveSecond = -1;
    private static Component lastTab;
    private static long tabCheckedAt;
    private static final WeakHashMap<FormattedCharSequence, Plan> CACHE = new WeakHashMap<>();
    private static final WeakHashMap<FormattedCharSequence, Plan> COMPONENT_CACHE = new WeakHashMap<>();
    private static final WeakHashMap<FormattedCharSequence, Plan> NAMETAG_CACHE = new WeakHashMap<>();
    private static final Plan NONE = new Plan(List.of(), -1, 0, 0);

    private record Glyph(int index, Style style, int codepoint) {}
    private record Plan(List<Glyph> glyphs, int start, int a, int b) {}

    private NameGradient() {}

    public static Module module() {
        return new Module("visual.nameGradient", "Animated Name", "Animate ImFear in chat, tab and nametags.", "Visual",
                () -> enabled, value -> enabled = value, List.of(
                new Setting.Section("Colours"),
                new Setting.Color("First colour", "One end of the moving gradient.", () -> first, value -> { if (value != 0) first = value; }),
                new Setting.Color("Second colour", "The other end of the gradient.", () -> second, value -> { if (value != 0) second = value; }),
                new Setting.Action("Server gradient", "Set ImFear's in-game gradient to these colours.", "Apply", NameGradient::apply)));
    }

    /** On this server players' chat arrives as system lines, so GAME is where a new one shows up. */
    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) observe(message);
        });
    }

    private static void apply() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || !mc.player.getGameProfile().name().equalsIgnoreCase(NAME)) {
            Toast.warn("Animated Name", "Only ImFear can apply these colours.");
            return;
        }
        mc.getConnection().sendCommand(String.format(java.util.Locale.ROOT, "gradient %06X %06X", first & 0xFFFFFF, second & 0xFFFFFF));
        CACHE.clear();
        COMPONENT_CACHE.clear();
        NAMETAG_CACHE.clear();
        // Shown at once rather than when the tab entry comes back; the tab then confirms it.
        liveFirst = first & 0xFFFFFF;
        liveSecond = second & 0xFFFFFF;
    }

    /** Read ImFear's tab entry again if it has been replaced - at most twice a second. */
    private static void refreshLive() {
        long now = System.currentTimeMillis();
        if (now - tabCheckedAt < 500) return;
        tabCheckedAt = now;
        Minecraft mc = Minecraft.getInstance();
        var connection = mc == null ? null : mc.getConnection();
        var info = connection == null ? null : connection.getPlayerInfoIgnoreCase(NAME);
        Component tab = info == null ? null : info.getTabListDisplayName();
        if (tab == null || tab == lastTab) return;
        lastTab = tab;
        observe(tab);
    }

    /** Take the gradient off a server line that colours ImFear as one; anything else is ignored. */
    static void observe(Component line) {
        if (line == null) return;
        Plan plan = plan(glyphs(line));
        if (plan.start < 0 || plan.a < 0 || (plan.a == liveFirst && plan.b == liveSecond)) return;
        liveFirst = plan.a;
        liveSecond = plan.b;
        // The pickers follow the server, so Apply starts from the gradient you really have.
        first = 0xFF000000 | plan.a;
        second = 0xFF000000 | plan.b;
    }

    public static FormattedCharSequence animate(FormattedCharSequence original) {
        if (!enabled || original == null) return original;
        refreshLive();
        Plan plan = CACHE.get(original);
        if (plan == null) {
            List<Glyph> glyphs = new ArrayList<>();
            boolean complete = original.accept((index, style, cp) -> {
                if (glyphs.size() >= 2048) return false;
                glyphs.add(new Glyph(index, style, cp));
                return true;
            });
            plan = complete ? plan(glyphs) : NONE;
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
        if (!enabled || original == null) return original;
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
        if (!enabled || original == null || !player.getGameProfile().name().equalsIgnoreCase(NAME)) return original;
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
        if (plan.start < 0 || (plan.a < 0 && liveFirst < 0)) return original;
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
        if (index < plan.start || index >= plan.start + NAME.length()) return original;
        int a = liveFirst >= 0 ? liveFirst : plan.a, b = liveFirst >= 0 ? liveSecond : plan.b;
        if (a < 0) return original;
        return original.withColor(colour(a, b, index - plan.start, time));
    }

    private static Plan plan(List<Glyph> glyphs) {
        return plan(glyphs, false);
    }

    private static Plan plan(List<Glyph> glyphs, boolean nametag) {
        for (int start = 0; start + NAME.length() <= glyphs.size(); start++) {
            if (start > 0 && usernameChar(glyphs.get(start - 1).codepoint)) continue;
            int end = start + NAME.length();
            if (end < glyphs.size() && usernameChar(glyphs.get(end).codepoint)) continue;
            boolean matches = true;
            for (int i = 0; i < NAME.length(); i++)
                if (Character.toLowerCase(glyphs.get(start + i).codepoint) != Character.toLowerCase(NAME.charAt(i))) matches = false;
            if (!matches) continue;
            var a = glyphs.get(start).style.getColor();
            var b = glyphs.get(end - 1).style.getColor();
            // Plain mentions and rank text are untouched. The server is the source of both endpoints.
            if (a != null && b != null && a.getValue() != b.getValue())
                return new Plan(List.copyOf(glyphs), start, a.getValue(), b.getValue());
            // The entity's actual profile has already been checked by nametag(). Chat mentions
            // still require a server gradient; a plain player nametag can use the server's colours.
            if (nametag) return new Plan(List.copyOf(glyphs), start, -1, -1);
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
