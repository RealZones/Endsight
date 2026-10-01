package com.endsight.visual;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;

public final class InventoryVisualChecks {
    private static int checks;
    private record Glyph(int cp, Style style) {}

    public static void main(String[] args) {
        HashSet<Integer> inventory = new HashSet<>();
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) {
            int index = InventoryPreview.inventoryIndex(r, c);
            check(index >= 9 && index <= 35 && inventory.add(index), "27 distinct inventory slots");
        }
        for (int c = 0; c < 9; c++) check(InventoryPreview.inventoryIndex(3, c) == c, "Hotbar stays in vanilla order");
        check(InventoryPreview.height(true) - InventoryPreview.height(false) == 24, "Optional hotbar does not change main grid");
        for (int time = 0; time < 4000; time += 13) for (int letter = 0; letter < 6; letter++) {
            int colour = NameGradient.colour(0x500740, 0x5100FF, letter, time);
            check(((colour >> 16) & 255) >= 0x50 && ((colour >> 16) & 255) <= 0x51, "Red in range");
            check(((colour >> 8) & 255) <= 7, "Green in range");
            check((colour & 255) >= 0x40, "Blue in range");
            check(colour == NameGradient.colour(0x500740, 0x5100FF, letter, time + 4000), "Smooth loop period");
        }
        check(NameGradient.colour(0x500740, 0x5100FF, 0, 0) == 0x500740, "First endpoint");
        check(NameGradient.colour(0x500740, 0x5100FF, 0, 2000) == 0x5100FF, "Second endpoint");
        Component plain = Component.literal("[DRAGON] ImFear: hi").withStyle(style -> style.withColor(0xFFFF00));
        check(NameGradient.animate(plain) == plain, "Plain mentions stay untouched");
        Component plainTag = Component.literal("[DRAGON] ImFear").withStyle(style -> style.withColor(0xCC00CC));
        // No hardcoded fallback: until the server's gradient has been seen, a plain tag stays as sent.
        check(NameGradient.nametagText(plainTag) == plainTag, "Plain player tag waits for the server's gradient");
        NameGradient.observe(Component.literal("[DRAGON] ").append(live("ImFear")));
        Component animatedTag = NameGradient.nametagText(plainTag);
        check(animatedTag != plainTag && animatedTag.getString().equals(plainTag.getString()), "Plain player tag uses the server's gradient");
        List<Glyph> tagBefore = glyphs(plainTag.getVisualOrderText()), tagAfter = glyphs(animatedTag.getVisualOrderText());
        for (int i = 0; i < 9; i++) check(tagBefore.get(i).style.equals(tagAfter.get(i).style), "Fallback leaves rank alone");
        Component other = gradient("SomeoneElse");
        check(NameGradient.animate(other) == other, "Other players untouched");
        Component longer = gradient("ImFearful");
        check(NameGradient.animate(longer) == longer, "Whole username only");
        Component styled = Component.literal("[DRAGON] ").withStyle(style -> style.withColor(0xCC00CC))
                .append(gradient("ImFear")).append(Component.literal(": hello").withStyle(style -> style.withColor(0xFFFFFF)));
        List<Glyph> before = glyphs(styled.getVisualOrderText());
        List<Glyph> after = glyphs(NameGradient.animate(styled).getVisualOrderText());
        check(before.size() == after.size(), "Text length unchanged");
        for (int i = 0; i < before.size(); i++) {
            check(before.get(i).cp == after.get(i).cp, "Text unchanged");
            if (i < 9 || i >= 15) check(before.get(i).style.equals(after.get(i).style), "Rank and message styles unchanged");
            else check(after.get(i).style.isBold(), "Name bold style retained");
        }
        FormattedCharSequence seq = styled.getVisualOrderText();
        List<Glyph> chat = glyphs(NameGradient.animate(seq));
        for (int i = 0; i < before.size(); i++) {
            check(chat.get(i).cp == before.get(i).cp, "Chat glyphs retained");
            if (i < 9 || i >= 15) check(chat.get(i).style.equals(before.get(i).style), "Chat rank/message retained");
        }
        // A line sent before the gradient changed is drawn in the new one, not the colours it arrived in.
        List<Glyph> old = glyphs(NameGradient.animate(gradient("ImFear")).getVisualOrderText());
        for (int i = 0; i < 6; i++) {
            int c = old.get(i).style.getColor().getValue();
            check(((c >> 16) & 255) >= 0x10 && ((c >> 16) & 255) <= 0x20 && (c & 255) >= 0x80, "Old line follows the live gradient");
        }
        System.out.println(checks + " inventory and animated-name checks passed");
    }

    /** A gradient far from the test's other one: 0x1000FF to 0x2000C0, so either is easy to tell apart. */
    private static Component live(String name) {
        var text = Component.empty();
        for (int i = 0; i < name.length(); i++) text.append(Component.literal(name.substring(i, i + 1))
                .setStyle(Style.EMPTY.withColor(i == 0 ? 0x1000FF : i == name.length() - 1 ? 0x2000C0 : 0x180000)));
        return text;
    }

    private static Component gradient(String name) {
        var text = Component.empty();
        for (int i = 0; i < name.length(); i++) text.append(Component.literal(name.substring(i, i + 1))
                .setStyle(Style.EMPTY.withBold(true).withColor(0x500740 + i * 20)));
        return text;
    }

    private static List<Glyph> glyphs(FormattedCharSequence sequence) {
        List<Glyph> glyphs = new ArrayList<>();
        sequence.accept((index, style, cp) -> { glyphs.add(new Glyph(cp, style)); return true; });
        return glyphs;
    }

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
}
