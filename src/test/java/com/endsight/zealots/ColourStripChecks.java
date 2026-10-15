package com.endsight.zealots;

import java.util.Random;
import java.util.regex.Pattern;

/** Matches the former regex while avoiding regex compilation in entity/render loops. */
public final class ColourStripChecks {
    public static void main(String[] args) {
        Pattern old = Pattern.compile("§[0-9A-Fa-fK-Ok-orRxX]");
        int checks = 0;
        for (int c = 0; c < 256; c++) {
            String raw = "A§" + (char) c + "B§";
            if (!Zealots.strip(raw).equals(old.matcher(raw).replaceAll(""))) throw new AssertionError("format code " + c);
            checks++;
        }
        Random random = new Random(42);
        String alphabet = "ABCDEF0123456789abcdefklmnorxKLMNORX§ 日本😀";
        for (int n = 0; n < 2000; n++) {
            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < 80; i++) raw.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String text = raw.toString();
            if (!Zealots.strip(text).equals(old.matcher(text).replaceAll(""))) throw new AssertionError("mixed format sequence");
            checks++;
        }
        String plain = new String("Golden Dragon");
        if (Zealots.strip(plain) != plain || !Zealots.strip(null).isEmpty()) throw new AssertionError("plain/null fast path");
        System.out.println((checks + 2) + " colour stripping checks passed");
    }
}
