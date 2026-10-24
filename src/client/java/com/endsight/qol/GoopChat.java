package com.endsight.qol;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The encounter's own messages, matched separately from player chat and loot. */
final class GoopChat {
    private GoopChat() { }

    private static final Pattern PLACER = Pattern.compile(
            "^(?:✦ )?(?:\\[[^\\r\\n\\]]+\\] )?[A-Za-z0-9_]{1,16} (?:squeezes the goop into the altar\\.|has woken something under the altar\\.{3})$",
            Pattern.MULTILINE);
    private static final List<String> SPEAKERS = List.of("Rift Slime", "Rift Colony", "Rift Corruptor", "Rift Devourer", "The Disturbed");
    private static final Set<String> NARRATION = Set.of(
            "The ground begins to shake...", "The ground is shaking...", "The rift throws you skyward!",
            "It bursts into pieces! Kill every last one of them.", "It pulls itself back together...",
            "??? ...under you...", "IT RISES under you!", "The slime hurls you into the sky!",
            "The corruption begins. The sky is not empty any more.", "The island itself starts to come apart.",
            "What was left of it dissolves.", "It stops moving.", "The rift swallows everything.",
            "The rift falls silent.", "The island pulls itself back together.", "It drags you under...",
            "The lance runs you through.", "It sheds a whole layer of itself!",
            "The slime sinks into the island...");
    private static final List<Pattern> COUNTERS = List.of(
            Pattern.compile("It sheds a dreg\\. \\(\\d+ crawling\\)"),
            Pattern.compile("The rift slime swells\\.{3} \\(phase \\d+\\)"),
            Pattern.compile("Corruption rains from the sky! \\d+ revenants? fell\\."),
            Pattern.compile("\\d+ pieces? of it (?:is|are) still crawling around\\."),
            Pattern.compile("Your bowstring turns to smoke\\.{3} \\(bows are useless for \\d+(?:\\.\\d+)?s\\)"),
            Pattern.compile("A dreg crawls back into it\\. \\+\\d+(?:\\.\\d+)?% \\(it swells\\)"),
            Pattern.compile("A glob reaches the core and it re-forms\\. \\+\\d+(?:\\.\\d+)?%"),
            Pattern.compile("The ground softens under [A-Za-z0-9_]{1,16}\\.{3} \\(JUMP\\)"),
            Pattern.compile("The ground bulges under [A-Za-z0-9_]{1,16}\\.{3}"),
            Pattern.compile("The corruption tide rolls in from the [A-Z]+\\. Find the gap\\."),
            Pattern.compile("GOO TIDE · seam [A-Z]+"));

    static boolean hide(String line) {
        // Preserve the whole original announcement, including the placer's name
        // and click/hover formatting, even if narration shares the same packet.
        if (PLACER.matcher(line).find()) return false;
        for (String speaker : SPEAKERS) {
            if (line.startsWith(speaker + " ") || line.startsWith(speaker + ": ")) return true;
        }
        if (NARRATION.contains(line)) return true;
        return COUNTERS.stream().anyMatch(pattern -> pattern.matcher(line).matches());
    }
}
