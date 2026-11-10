package com.endsight.dungeon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class QuizChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        // -- Oruo's chat, as latest.log has it on 2026-11-09 (colour codes kept) -------
        QuizLogic.Asking asking = new QuizLogic.Asking();
        check(!asking.feed("§4[STATUE] Oruo the Omniscient§r§f: Answer incorrectly, and your moment of ineptitude will live on for generations."),
                "an intro line is not a question");
        check(!asking.feed("§f                                §r§6§lQuestion #1"), "the header alone is not a question");
        check(!asking.feed("§f                    §r§6What is the name of Rick's brother?"), "nor the header and the question");
        check(!asking.feed("    §6 ⓐ §aWendell") && !asking.feed("    §6 ⓑ §aJamie"), "nor two options");
        check(asking.feed("    §6 ⓒ §aPat"), "the third option completes it");
        check(asking.index() == 1 && "What is the name of Rick's brother?".equals(asking.text()), "number and question read");
        check(asking.options().equals(List.of("Wendell", "Jamie", "Pat")), "options read without their letters");
        check(!asking.feed("    §6 ⓐ §aWendell"), "a stray option after the question is ignored");
        asking.feed("§f                                §r§6§lQuestion #2");
        asking.feed("§f                   §r§6How many total Fairy Souls are there?");
        asking.feed("    §6 ⓐ §a289 Fairy Souls");
        asking.feed("    §6 ⓑ §a247 Fairy Souls");
        check(asking.feed("    §6 ⓒ §a253 Fairy Souls") && asking.index() == 2
                && asking.options().get(0).equals("289 Fairy Souls"), "the next question replaces the last");

        // -- the labels over the answer blocks ("ⓑ Year 525." in the clip) -------------
        check(QuizLogic.lettered("ⓑ Year 525.") && "Year 525".equals(QuizLogic.option("ⓑ Year 525.")),
                "a label loses its letter and full stop");
        check("289 Fairy Souls".equals(QuizLogic.option("§6ⓐ §a289 Fairy Souls.")), "colour codes on a label are ignored");
        check(!QuizLogic.lettered("Question #3") && !QuizLogic.lettered("Oruo the Omniscient"), "other names are not options");
        check("Storm".equals(QuizLogic.option("C Storm")), "an old book's lettered answer still reads bare");

        // -- verdicts, and whose they are ----------------------------------------------
        check(QuizLogic.ruling("§4[STATUE] Oruo the Omniscient§r§f: §r§dImFear §r§aanswered §r§6Question #1 §r§acorrectly!")
                .equals(new QuizLogic.Ruling(QuizLogic.Verdict.RIGHT, "ImFear")), "a right answer, and who gave it");
        check(QuizLogic.ruling("[STATUE] Oruo the Omniscient: ImFear answered the final question correctly!")
                .equals(new QuizLogic.Ruling(QuizLogic.Verdict.RIGHT, "ImFear")), "the final answer is a right answer too");
        check(QuizLogic.ruling("[STATUE] Oruo the Omniscient: ImFear chose the wrong answer! I shall never forget this moment of misrememberance.")
                .equals(new QuizLogic.Ruling(QuizLogic.Verdict.WRONG, "ImFear")), "a wrong answer");
        check(QuizLogic.ruling("[STATUE] Oruo the Omniscient: Teammate_1 answered Question #2 correctly!")
                .equals(new QuizLogic.Ruling(QuizLogic.Verdict.RIGHT, "Teammate_1")), "a teammate's answer names them");
        check(QuizLogic.ruling("§aQuiz has reset. You can try again!").verdict() == QuizLogic.Verdict.RESET, "the room resets");
        check(QuizLogic.ruling("[STATUE] Oruo the Omniscient: I bestow upon you all the power of a hundred years!").verdict()
                == QuizLogic.Verdict.DONE, "the room is done");
        check(QuizLogic.ruling("[STATUE] Oruo the Omniscient: Yikes") == null
                && QuizLogic.ruling("[STATUE] Oruo the Omniscient: One more question!") == null
                && QuizLogic.ruling("[STATUE] Oruo the Omniscient: 2 questions left... Then you will have proven your worth to me!") == null,
                "chatter says nothing");
        check(QuizLogic.ruling("[VIP+] Someone: ImFear answered Question #1 correctly!") == null, "a player typing it is not Oruo");
        check(QuizLogic.oruo("§4[STATUE] Oruo the Omniscient§r§f: I am Oruo"), "Oruo speaking is the room");

        // -- which block a teammate answered at ---------------------------------------
        check(QuizLogic.standingAt(List.of(7.5, 1.8, 9.0), 5, 2) == 1, "beside one block, far from the others");
        check(QuizLogic.standingAt(List.of(6.0, 7.0, 9.0), 5, 2) == -1, "out of reach of every block");
        check(QuizLogic.standingAt(List.of(3.5, 4.5, 9.0), 5, 2) == -1, "between two blocks says nothing");
        check(QuizLogic.standingAt(List.of(), 5, 2) == -1, "no labels, no answer");

        // -- the year is worked out, and matched what the server took ------------------
        check(QuizLogic.skyblockYear(Instant.parse("2026-11-09T10:06:40Z").toEpochMilli()) == 525, "year 525 on the night");
        check(QuizLogic.skyblockYear(Instant.parse("2019-06-11T17:56:00Z").toEpochMilli()) == 1, "year 1 at the start");
        check("Year 525".equals(QuizLogic.worked("What SkyBlock year is it?", Instant.parse("2026-11-09T10:06:40Z").toEpochMilli())),
                "the year question is answered from the clock");
        check(QuizLogic.worked("What is the name of Rick's brother?", 0) == null, "other questions are not worked out");

        // -- the bundled book ---------------------------------------------------------
        QuizLogic.Book bundled = new QuizLogic.Book();
        Path file = Files.createTempDirectory("endsight-quiz").resolve("quiz-answers.txt");
        bundled.load(file);
        check("289 Fairy Souls".equals(bundled.deduce("How many total Fairy Souls are there?",
                List.of("289 Fairy Souls", "247 Fairy Souls", "253 Fairy Souls"))), "the server's own count");
        check("Necromancer Lord".equals(bundled.deduce("What is the status of Sadan?",
                List.of("Professor", "The Wither Lords", "Necromancer Lord"))), "a lore answer");
        check("Kat".equals(bundled.deduce("What is the name of the person that upgrades pets?", List.of("Jamie", "Kat", "Elle"))),
                "another accepted answer");
        check(bundled.deduce("Which of these enemies does not spawn in the Spider's Den?",
                List.of("Zombie Spider", "Voracious Spider", "Splitter Spider")) == null,
                "a stored answer the room is not showing names nothing");

        // -- learning ------------------------------------------------------------------
        QuizLogic.Book book = new QuizLogic.Book();
        String a = "Which Wither Lord is crushed under pillars?";
        check(!book.knows(a), "an unseen question is unknown");
        check(book.learn(a, "Goldor"), "learning a new answer reports a change");
        check(!book.learn(a, "Goldor"), "learning the same answer again reports no change");
        check("Goldor".equals(book.answer("which wither lord is crushed under pillars!")), "found again despite punctuation");
        check(book.learn(a, "Maxor") && "Maxor".equals(book.answer(a)), "a corrected answer overwrites the old one");
        check(!book.learn(a, "  "), "a blank answer is not learned");

        book.load(file);
        book.learn("How many total Fairy Souls are there?", "289 Fairy Souls");
        book.learn("What is the colour of the sky?", "Blue");
        book.save();
        check(Files.readAllLines(file).size() == 3, "only the new answer is written; a confirmed bundled one is not");
        QuizLogic.Book reopened = new QuizLogic.Book();
        reopened.load(file);
        check("Blue".equals(reopened.answer("What is the colour of the sky")), "an answer survives the round trip");

        Files.write(file, List.of("# a comment", "", "malformed line with no separator", "= dangling",
                "trailing = Storm ", "what is the status of sadan = Shaman Necromancer"));
        reopened.load(file);
        check("Storm".equals(reopened.answer("trailing")), "surrounding whitespace is trimmed, junk skipped");
        check("Shaman Necromancer".equals(reopened.answer("What is the status of Sadan?")), "a local answer beats the bundled one");

        QuizLogic.Book stale = new QuizLogic.Book();
        Path staleFile = Files.createTempDirectory("endsight-quiz-stale").resolve("quiz-answers.txt");
        stale.load(staleFile);
        check(stale.exclude("What is the name of Rick's brother?", "Pat"), "a wrong bundled answer is rejected");
        check(stale.answer("What is the name of Rick's brother?") == null, "and stops being given");
        stale.save();
        QuizLogic.Book rememberedWrong = new QuizLogic.Book();
        rememberedWrong.load(staleFile);
        check(rememberedWrong.answer("What is the name of Rick's brother?") == null, "even after a reload");

        // -- elimination ----------------------------------------------------------------
        QuizLogic.Book e = new QuizLogic.Book();
        List<String> opts = List.of("Wendell", "Jamie", "Pat");
        String q = "Who is the youngest?";
        check(e.deduce(q, opts) == null, "no answer and no exclusions means no guess");
        check(e.exclude(q, "Wendell") && !e.exclude(q, "Wendell"), "a wrong answer is news once");
        check(e.deduce(q, opts) == null, "two options left is still a guess, so stay quiet");
        e.exclude(q, "Jamie");
        check("Pat".equals(e.deduce(q, opts)), "one option left is the answer by elimination");
        check(e.answer(q) == null, "elimination does not pretend the answer was taught");
        java.nio.file.Path f2 = Files.createTempDirectory("endsight-quiz2").resolve("book.txt");
        e.load(f2);
        e.exclude(q, "Wendell");
        e.exclude(q, "Jamie");
        e.learn("Settled?", "Kat");
        e.save();
        QuizLogic.Book k2 = new QuizLogic.Book();
        k2.load(f2);
        check(k2.excluded(q).size() == 2 && "Kat".equals(k2.answer("Settled?")), "exclusions and answers come back from the file");
        check(!Files.readString(f2).contains("settled? !="), "a solved question writes no exclusion line");

        System.out.println(checks + " quiz checks passed");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        checks++;
    }
}
