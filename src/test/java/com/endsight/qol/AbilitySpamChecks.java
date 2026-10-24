package com.endsight.qol;

import com.endsight.zealots.Zealots;
import java.util.List;

/** Boss lines transcribed from the supplied screenshot, plus player/loot boundaries. */
public final class AbilitySpamChecks {
    public static void main(String[] args) {
        List<String> boss = List.of(
                "Rift Corruptor I have been listening to the sky. It is full of the dead.",
                "Rift Corruptor Catch.",
                "It sheds a dreg. (8 crawling)",
                "It sheds a dreg. (6 crawling)",
                "It sheds a dreg. (1 crawling)",
                "The rift slime swells... (phase 4)",
                "The island itself starts to come apart.",
                "Rift Devourer This island was mine before your dragons. It will be mine after.",
                "Rift Devourer Burn with it.",
                "Corruption rains from the sky! 3 revenants fell.",
                "Rift Devourer I can feel the egg. It is watching...",
                "What was left of it dissolves.",
                "It stops moving.",
                "Rift Devourer: A future line of dialogue.",
                "It sheds a dreg. (12 crawling)",
                "The rift slime swells... (phase 12)",
                "Corruption rains from the sky! 1 revenant fell.",
                "Rift Slime So you Disturb Me...?",
                "Rift Slime You dig, and dig, and never ask what lies underneath.",
                "Rift Colony Every piece of me remembers you.",
                "The Disturbed YOU. DISTURBED. ME.",
                "The Disturbed So you disturbed me... and so I sleep.",
                "The ground begins to shake...", "The ground is shaking...",
                "The rift throws you skyward!", "It bursts into pieces! Kill every last one of them.",
                "Your bowstring turns to smoke... (bows are useless for 5s)",
                "12 pieces of it are still crawling around.", "1 piece of it is still crawling around.",
                "It pulls itself back together...", "??? ...under you...", "IT RISES under you!",
                "The slime hurls you into the sky!", "The corruption begins. The sky is not empty any more.",
                "The rift swallows everything.", "The rift falls silent.", "The island pulls itself back together.",
                "It drags you under...", "A dreg crawls back into it. +1.0% (it swells)",
                "A glob reaches the core and it re-forms. +1.0%", "The lance runs you through.",
                "It sheds a whole layer of itself!",
                "The ground softens under ImFear... (JUMP)", "The slime sinks into the island...",
                "The corruption tide rolls in from the EAST. Find the gap.",
                "The ground bulges under ressentiram...", "GOO TIDE · seam E");
        for (String line : boss) {
            if (!AbilitySpam.goopMessage(Zealots.strip("§d" + line))) throw new AssertionError("Boss spam escaped: " + line);
        }
        for (String line : List.of(
                "[+ DRAGON] DelonR", "[VIP+] Player: Rift Corruptor Catch.",
                "Player: It stops moving.", "Guild > Player: Corruption rains from the sky! 3 revenants fell.",
                "RARE DROP! Rift Goo", "You received 1x Goop!", "Rift Goo",
                "This ability is on cooldown for 4.45s", "Salvation found nothing in its path.",
                "The Rift is now open!", "Your Revenant boss has spawned!",
                "The ground trembles with ancient sculk energy...", "You hear something falling from the sky.",
                "+1 Crypt Cache, 13 Rift Goo and 2 Gelatinous Membranes (top three)",
                "THE DISTURBED DROPPED", "  Corrupted Core",
                "You did 2.9% of the damage. The top 3, the Goop placer and anyone with 3%+ are paid.",
                "▬▬▬▬\n                            ✦ THE RIFT SLEEPS\n\n1st Damager Tester - 26,205,884\n▬▬▬▬",
                "▬▬▬▬\nBESTIARY TIER UP Corrupted Revenant 4➜5\nREWARDS\n▬▬▬▬",
                "▬▬▬▬\n✦ THE WARDEN DOWN!\n1st Damager Tester - 500,000\n▬▬▬▬",
                "Player: ✦ THE RIFT SLEEPS")) {
            if (AbilitySpam.goopMessage(line)) throw new AssertionError("Unrelated chat hidden: " + line);
        }
        for (String line : List.of(
                "§5§lImNotJob §dsqueezes the goop into the altar.",
                "§5§lressentiram §dsqueezes the goop into the altar.",
                "✦ ImFear has woken something under the altar...",
                "Rift Slime Small things. Loud things.\nImNotJob squeezes the goop into the altar.")) {
            if (AbilitySpam.goopMessage(Zealots.strip(line))) throw new AssertionError("Placer announcement hidden: " + line);
        }
        var toggle = AbilitySpam.module().settings().stream().filter(s -> s.label().equals("Goop boss")).findFirst().orElseThrow();
        if (!(toggle instanceof com.endsight.ui.Setting.Toggle)) throw new AssertionError("Goop boss must be a saved toggle");
        System.out.println("Goop boss dialogue, phase, dreg and unrelated-chat boundary checks passed");
    }
}
