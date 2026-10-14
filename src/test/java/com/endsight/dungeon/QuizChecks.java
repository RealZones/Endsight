package com.endsight.dungeon;

import java.nio.file.Files;
import java.nio.file.Path;

public final class QuizChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        // -- reading the hologram ------------------------------------------------
        QuizLogic.Question q = QuizLogic.question("1/3 Which Wither Lord is crushed under pillars?");
        check(q != null && q.index() == 1 && q.total() == 3, "counter parsed");
        check(q != null && q.text().equals("Which Wither Lord is crushed under pillars?"), "question text kept intact");
        check(QuizLogic.question("§e2/3 §fWhich one is the youngest?") != null, "colour codes do not block parsing");
        check(QuizLogic.question("Quiz") == null, "the title line is not a question");
        check(QuizLogic.question("3/3") == null, "a bare counter is not a question");
        check(QuizLogic.question(null) == null, "null is not a question");

        // -- the same question recognising itself --------------------------------
        String a = "1/3 Which Wither Lord is crushed under pillars?";
        String b = "§a2/3  Which Wither Lord is crushed under pillars!";
        check(QuizLogic.normalize(a).equals(QuizLogic.normalize(b)),
                "counter, colour, spacing and trailing punctuation all ignored");
        check(QuizLogic.normalize("Which Wither Lord is crushed under pillars?")
                .equals("which wither lord is crushed under pillars"), "normalised form is lowercase and bare");

        // -- the book ------------------------------------------------------------
        QuizLogic.Book book = new QuizLogic.Book();
        check(!book.knows(a), "an unseen question is unknown");
        check(book.learn(a, "Goldor"), "learning a new answer reports a change");
        check(!book.learn(a, "Goldor"), "learning the same answer again reports no change");
        check("Goldor".equals(book.answer(b)), "the answer is found from a differently worded counter");
        check(book.learn(a, "Maxor"), "a corrected answer overwrites the old one");
        check("Maxor".equals(book.answer(a)), "the correction is what gets returned");
        check(!book.learn(a, "  "), "a blank answer is not learned");
        check(book.size() == 1, "one question, one entry");

        // -- it survives a round trip through the file ---------------------------
        Path file = Files.createTempDirectory("endsight-quiz").resolve("quiz-answers.txt");
        book.save();                       // no path yet: must not throw
        book.load(file);                   // missing file: must not throw
        check(book.size() == 0, "loading a missing book leaves it empty");
        book.learn("1/3 Which Wither Lord is crushed under pillars?", "Goldor");
        book.learn("2/3 Who is the youngest?", "Maxor");
        book.save();
        QuizLogic.Book reopened = new QuizLogic.Book();
        reopened.load(file);
        check(reopened.size() == 2, "both answers came back");
        check("Goldor".equals(reopened.answer("3/3 Which Wither Lord is crushed under pillars?")),
                "an answer survives the round trip and ignores the counter");

        // a hand-edited book with comments and junk must still load
        Files.write(file, java.util.List.of(
                "# a comment",
                "",
                "who is the oldest = Necron",
                "malformed line with no separator",
                "= dangling",
                "trailing = Storm "));
        reopened.load(file);
        check(reopened.size() == 2, "comments and malformed lines are skipped");
        check("Necron".equals(reopened.answer("1/3 Who is the oldest?")), "hand-written entry is readable");
        check("Storm".equals(reopened.answer("trailing")), "surrounding whitespace is trimmed");

        // -- only commit an answer once the puzzle has moved on ------------------
        QuizLogic.Book fresh = new QuizLogic.Book();
        QuizLogic.Pending pending = new QuizLogic.Pending();
        pending.offer("1/3 Which Wither Lord is crushed under pillars?", "Goldor");
        check(!pending.confirm(fresh, QuizLogic.question("1/3 Which Wither Lord is crushed under pillars?")),
                "still on the same question: nothing is learned yet");
        check(pending.has(), "the pending answer is kept while the question stands");
        check(pending.confirm(fresh, QuizLogic.question("2/3 Who is the youngest?")),
                "the counter moving on confirms the previous answer");
        check("Goldor".equals(fresh.answer("Which Wither Lord is crushed under pillars?")), "and it is written down");
        check(!pending.has(), "the pending answer is cleared once committed");
        check(!pending.confirm(fresh, QuizLogic.question("3/3 Anything?")), "nothing pending, nothing learned");

        // -- learning from a failed room ----------------------------------------
        QuizLogic.Book e = new QuizLogic.Book();
        java.util.List<String> opts = java.util.List.of("Maxor", "Storm", "Goldor");
        String q2 = "2/3 Who guards the Blood Room?";
        check(e.deduce(q2, opts) == null, "no answer and no exclusions means no guess");
        check(e.exclude(q2, "Maxor"), "a wrong answer is recorded");
        check(!e.exclude(q2, "Maxor"), "the same wrong answer twice is not news");
        check(e.deduce(q2, opts) == null, "two options left is still a guess, so stay quiet");
        e.exclude(q2, "Goldor");
        check("Storm".equals(e.deduce(q2, opts)), "one option left is the answer by elimination");
        check(e.answer(q2) == null, "elimination does not pretend the answer was taught");
        e.learn(q2, "Storm");
        check("Storm".equals(e.answer(q2)), "and a real answer still overrides");

        // exclusions survive the file, and a solved question drops them
        java.nio.file.Path f2 = Files.createTempDirectory("endsight-quiz2").resolve("book.txt");
        QuizLogic.Book k = new QuizLogic.Book();
        k.load(f2);
        k.exclude("1/3 Which is oldest?", "Maxor");
        k.exclude("1/3 Which is oldest?", "Storm");
        k.learn("2/3 Settled?", "Goldor");
        k.save();
        QuizLogic.Book k2 = new QuizLogic.Book();
        k2.load(f2);
        check(k2.excluded("Which is oldest?").size() == 2, "exclusions came back from the file");
        check("Goldor".equals(k2.answer("Settled?")), "answers still came back too");
        check(!Files.readString(f2).contains("settled? !="), "a solved question writes no exclusion line");

        // -- books written while answers kept their slot letter -------------------
        java.util.List<String> lords = java.util.List.of("Storm", "Necron", "Goldor");
        check("Storm".equals(QuizLogic.resolve("C Storm", lords)), "a lettered answer finds its bare option");
        check("A chest".equals(QuizLogic.resolve("A chest", java.util.List.of("A chest", "A door"))),
                "an answer that really starts with a letter is not cut down");
        check("Storm".equals(QuizLogic.resolve("B A Storm", lords)) == false, "only one letter is ever stripped");
        check("Fairy".equals(QuizLogic.resolve("Fairy", lords)), "an answer missing from the room is left alone");
        QuizLogic.Book old = new QuizLogic.Book();
        old.exclude("1/3 Who revives a fallen teammate?", "A The Watcher");
        old.exclude("1/3 Who revives a fallen teammate?", "B Mort");
        check("The Fairy".equals(old.deduce("Who revives a fallen teammate?",
                java.util.List.of("The Watcher", "Mort", "The Fairy"))), "lettered wrong answers still eliminate");

        // -- which way the pillars face (room-20261014-005531: A +4,+5  B +7,0  C +4,-5)
        int[] front = QuizLogic.front(java.util.List.of(new double[]{4, 5}, new double[]{7, 0}, new double[]{4, -5}));
        check(front != null && front[0] == -1 && front[1] == 0, "the front faces back toward the entrance");
        int[] turned = QuizLogic.front(java.util.List.of(new double[]{5, 4}, new double[]{0, 7}, new double[]{-5, 4}));
        check(turned != null && turned[0] == 0 && turned[1] == -1, "a room built the other way gets its own front");
        check(QuizLogic.front(java.util.List.of(new double[]{0, 0}, new double[]{0, 5}, new double[]{0, 10})) == null,
                "three signs in a row have no front");
        check(QuizLogic.front(java.util.List.of(new double[]{0, 0}, new double[]{0, 5})) == null, "two signs say nothing");

        System.out.println(checks + " quiz checks passed");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        checks++;
    }
}
