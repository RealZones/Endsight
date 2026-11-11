package com.endsight.visual;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The guild list (/g online) with each gradient name smooth again.
 *
 * In chat Dragsim sends a player's gradient name in true colours. In the guild list it sends
 * the same names as old-style chat codes, one per letter, each the nearest of the sixteen
 * classic colours - so a fade comes out as hard steps, and dark gradients land on black and
 * dark blue, which barely show on chat's background ("§0[§4D§4R…§6] §0D§4e…", "YoungRaide§0n";
 * the 2026-11-10 log). Fear: "so ugly its cooked".
 *
 * Each such name is redrawn as a smooth fade: in the player's true colours where they have
 * been seen (their tab entry, or a line of theirs in chat), otherwise between the colours of
 * its first and last letters, lifted where too dark to read. The rank tag in front fades the
 * same way, as the server does it. Only the list's own lines, only names whose letters come
 * in more than one colour; plain names, [MVP+] tags and the rest stay as sent, and a line
 * with anything clickable in it is left whole.
 */
public final class GuildNames {
    private GuildNames() { }

    /** The list arrives as a burst of lines right after its "Guild Name:" header. */
    private static final long LIST_MS = 3000;
    private static long listUntil;
    /** True gradients seen in chat and the tab list, by lower-case name. */
    private static final Map<String, int[]> known = new HashMap<>();
    private static final int KNOWN_MAX = 600;

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) learn(message); });
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : modify(message));
    }

    private static Component modify(Component message) {
        String raw = message.getString();
        long now = System.currentTimeMillis();
        if (strip(raw).startsWith("Guild Name:")) {
            listUntil = now + LIST_MS;
            learnTab();
            return message;
        }
        if (now > listUntil || raw.indexOf('§') < 0 || clickable(message)) return message;
        Component fixed = rebuild(raw, known);
        return fixed == null ? message : fixed;
    }

    private static boolean clickable(Component message) {
        return message.visit((style, text) -> style.getClickEvent() != null || style.getHoverEvent() != null
                ? Optional.of(true) : Optional.empty(), Style.EMPTY).isPresent();
    }

    // ── learning true colours ─────────────────────────────────────────────────────

    private static void learnTab() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        for (var info : connection.getOnlinePlayers()) {
            Component tab = info.getTabListDisplayName();
            if (tab != null) learn(tab);
        }
    }

    /** Any name in a line whose first and last letters differ in true colour is a gradient worth keeping. */
    static void learn(Component line) {
        List<int[]> glyphs = new ArrayList<>();   // {codepoint, rgb or -1}
        line.visit((style, text) -> {
            int rgb = style.getColor() == null ? -1 : style.getColor().getValue();
            text.codePoints().forEach(cp -> glyphs.add(new int[]{cp, rgb}));
            return Optional.empty();
        }, Style.EMPTY);
        for (int i = 0; i < glyphs.size(); i++) {
            if (!nameChar(glyphs.get(i)[0]) || i > 0 && nameChar(glyphs.get(i - 1)[0])) continue;
            int end = i;
            StringBuilder name = new StringBuilder();
            while (end < glyphs.size() && nameChar(glyphs.get(end)[0])) name.appendCodePoint(glyphs.get(end++)[0]);
            int a = glyphs.get(i)[1], b = glyphs.get(end - 1)[1];
            if (name.length() >= 3 && name.length() <= 16 && a >= 0 && b >= 0 && a != b) {
                if (known.size() >= KNOWN_MAX) known.clear();
                known.put(name.toString().toLowerCase(), new int[]{a, b});
            }
            i = end - 1;
        }
    }

    // ── rebuilding a list line ────────────────────────────────────────────────────

    private record Glyph(int cp, int colour, boolean bold, boolean italic, boolean underline, boolean strike) { }

    /** The line again with its gradient names smoothed, or null if it holds none. */
    static Component rebuild(String raw, Map<String, int[]> known) {
        List<Glyph> glyphs = parse(raw);
        int[] colours = new int[glyphs.size()];
        for (int i = 0; i < colours.length; i++) colours[i] = glyphs.get(i).colour();
        boolean changed = false;
        for (int i = 0; i < glyphs.size(); i++) {
            int cp = glyphs.get(i).cp();
            if (!nameChar(cp) || i > 0 && nameChar(glyphs.get(i - 1).cp())) continue;
            int end = i;
            while (end < glyphs.size() && nameChar(glyphs.get(end).cp())) end++;
            int start = i;
            i = end - 1;
            if (end - start < 3 || end - start > 16) continue;
            Set<Integer> letters = new HashSet<>();
            for (int k = start; k < end; k++) letters.add(glyphs.get(k).colour());
            if (letters.size() < 2) continue;                         // one colour: as sent
            int tagStart = -1;
            if (start >= 3 && glyphs.get(start - 1).cp() == ' ' && glyphs.get(start - 2).cp() == ']')
                for (int k = start - 3; k >= 0 && k >= start - 24; k--) {
                    int c = glyphs.get(k).cp();
                    if (c == '[') { tagStart = k; break; }
                    if (c == ']' || c == ' ') break;
                }
            String name = codepoints(glyphs, start, end);
            int[] ends = known.get(name.toLowerCase());
            int a = ends != null ? ends[0] : glyphs.get(start).colour(), b = ends != null ? ends[1] : glyphs.get(end - 1).colour();
            a = readable(a);
            b = readable(b);
            fade(colours, start, end, a, b);
            if (tagStart >= 0) fade(colours, tagStart, start - 1, a, b);
            changed = true;
        }
        if (!changed) return null;
        MutableComponent out = Component.empty();
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph g = glyphs.get(i);
            Style style = Style.EMPTY.withColor(TextColor.fromRgb(colours[i])).withBold(g.bold()).withItalic(g.italic())
                    .withUnderlined(g.underline()).withStrikethrough(g.strike());
            out.append(Component.literal(new String(Character.toChars(g.cp()))).setStyle(style));
        }
        return out;
    }

    private static void fade(int[] colours, int from, int to, int a, int b) {
        int span = Math.max(1, to - from - 1);
        for (int k = from; k < to; k++) colours[k] = mix(a, b, (k - from) / (double) span);
    }

    /** The classic codes in a string, as glyphs with their colour and formatting. White until coloured. */
    static List<Glyph> parse(String raw) {
        List<Glyph> out = new ArrayList<>();
        int colour = 0xFFFFFF;
        boolean bold = false, italic = false, underline = false, strike = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '§' && i < raw.length()) {
                ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(raw.charAt(i++)));
                if (f == null) continue;
                if (f.isColor()) { colour = f.getColor(); bold = italic = underline = strike = false; }
                else if (f == ChatFormatting.RESET) { colour = 0xFFFFFF; bold = italic = underline = strike = false; }
                else if (f == ChatFormatting.BOLD) bold = true;
                else if (f == ChatFormatting.ITALIC) italic = true;
                else if (f == ChatFormatting.UNDERLINE) underline = true;
                else if (f == ChatFormatting.STRIKETHROUGH) strike = true;
                continue;
            }
            out.add(new Glyph(cp, colour, bold, italic, underline, strike));
        }
        return out;
    }

    private static String codepoints(List<Glyph> glyphs, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int k = from; k < to; k++) sb.appendCodePoint(glyphs.get(k).cp());
        return sb.toString();
    }

    /**
     * Lifted toward white until it reads on chat's dark background: black and dark blue
     * are what a dark gradient quantises to, and the point of the fix is to see the name.
     */
    static int readable(int rgb) {
        int c = rgb;
        for (int step = 0; step < 10 && luminance(c) < .22; step++) c = mix(c, 0xFFFFFF, .15);
        return c;
    }

    private static double luminance(int rgb) {
        return (.2126 * (rgb >> 16 & 255) + .7152 * (rgb >> 8 & 255) + .0722 * (rgb & 255)) / 255;
    }

    private static int mix(int a, int b, double t) {
        int out = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int x = a >> shift & 255, y = b >> shift & 255;
            out |= (int) Math.round(x + (y - x) * t) << shift;
        }
        return out;
    }

    private static String strip(String s) {
        return s.replaceAll("§.", "");
    }

    private static boolean nameChar(int cp) {
        return cp < 128 && (Character.isLetterOrDigit(cp) || cp == '_');
    }
}
