package com.endsight.dungeon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Quiz puzzle's answer book, and the parsing around it.
 *
 * Confirmed answers ship as defaults. Each player's own answers and exclusions stay in
 * a separate file and override those defaults, so a corrected server answer can be
 * learned without changing the jar or erasing the player's book.
 *
 * The book is plain text, one "question = answer" per line, so it can be read, edited
 * and shared by hand - a few players swapping files fills it far faster than any one
 * person running dungeons.
 */
public final class QuizLogic {

    private QuizLogic() {
    }

    /** "1/3 Which Wither Lord is crushed under pillars?" -> index 1, of 3. */
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)\\s*/\\s*(\\d+)\\s*(.*)$");

    /** One question as it was asked: which of the three it is, and the text itself. */
    public record Question(int index, int total, String text) {
        public boolean valid() {
            return index >= 1 && total >= 1 && index <= total && !text.isBlank();
        }
    }

    /**
     * Strip the colour codes, the counter and the decoration, so the same question
     * recognises itself next week.
     *
     * Lowercased and stripped of trailing punctuation for the same reason the other
     * solvers do it: the server is free to re-punctuate a line and we are not free to
     * care. Interior spacing is collapsed because holograms are assembled from parts
     * and occasionally arrive double-spaced.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String text = raw.replaceAll("§.", "")
                .replace('’', '\'')
                .replace('–', '-')
                .replaceAll("\\s+", " ")
                .trim();
        Matcher m = NUMBERED.matcher(text);
        if (m.matches()) text = m.group(3).trim();
        return text.replaceAll("[.?!]+$", "").toLowerCase(Locale.ROOT).trim();
    }

    /** Read a hologram line as a question, or null when it is not one. */
    public static Question question(String raw) {
        if (raw == null) return null;
        String text = raw.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
        Matcher m = NUMBERED.matcher(text);
        if (!m.matches()) return null;
        String body = m.group(3).trim();
        if (body.isBlank()) return null;
        try {
            Question q = new Question(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), body);
            return q.valid() ? q : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * An option label without its slot letter.
     *
     * The room labels its three answers "A Necron", "B Goldor", "C Storm" - and the
     * letters MOVE. Storm was C on one question and B on the next, because the letter
     * says which pillar an answer is standing at this time, not what the answer is.
     * Storing the label whole therefore stored a position: the book filled up with
     * "C Goldor", which matches nothing the next time Goldor stands at B, so the hint
     * named the wrong button and the highlight found nothing to mark.
     *
     * One leading letter and a space only. An option that is genuinely one letter long
     * keeps it, since stripping that would leave nothing.
     */
    public static String option(String raw) {
        if (raw == null) return "";
        String text = raw.replaceAll("§.", "").replaceAll("\s+", " ").trim();
        if (text.length() > 2 && Character.isLetter(text.charAt(0)) && text.charAt(1) == ' ') {
            String rest = text.substring(2).trim();
            if (!rest.isEmpty()) return rest;
        }
        return text;
    }

    /**
     * The live option a stored answer names.
     *
     * Books written before {@link #option} existed hold the slot letter too ("C Storm"),
     * which named a pillar rather than an answer: it matched nothing once Storm stood
     * somewhere else, so the hint repeated a stale letter and nothing was highlighted.
     * An exact match wins, so an answer that really starts with a letter and a space
     * ("A chest") is never cut down further.
     */
    public static String resolve(String stored, List<String> options) {
        if (stored == null || options == null || options.contains(stored)) return stored;
        String bare = option(stored);
        return options.contains(bare) ? bare : stored;
    }

    /** Whether a recorded wrong answer rules out this option, lettered or not. */
    static boolean rulesOut(String excluded, String option) {
        if (excluded.equals(option)) return true;
        String bare = option(excluded);
        return !bare.equals(excluded) && bare.equals(option);
    }

    /**
     * Which way the answer pillars face, from where their three labels stand: {dx, dz}
     * with one of them ±1, or null when the labels are not three or not that shape.
     *
     * The room is two pillars side by side with the third set back between them (a
     * recording: A at +4,+5, B at +7,0, C at +4,-5, entered from the west). The pillar
     * set back is the corner opposite the longest side, and the front is the way from
     * it to the middle of that side. Read off the room rather than assumed, so a room
     * built facing another way still gets its own front.
     */
    public static int[] front(List<double[]> labels) {
        if (labels == null || labels.size() != 3) return null;
        int back = -1;
        double longest = -1;
        for (int i = 0; i < 3; i++) {
            double[] a = labels.get((i + 1) % 3), b = labels.get((i + 2) % 3);
            double side = Math.hypot(a[0] - b[0], a[1] - b[1]);
            if (side > longest) { longest = side; back = i; }
        }
        double[] a = labels.get((back + 1) % 3), b = labels.get((back + 2) % 3), m = labels.get(back);
        double dx = (a[0] + b[0]) / 2 - m[0], dz = (a[1] + b[1]) / 2 - m[1];
        if (Math.hypot(dx, dz) < 1) return null;              // three in a row has no front
        return Math.abs(dx) >= Math.abs(dz) ? new int[]{(int) Math.signum(dx), 0} : new int[]{0, (int) Math.signum(dz)};
    }

    /** Bundled answers with per-player corrections and discoveries backed by a file. */
    public static final class Book {
        private static final String DEFAULT_RESOURCE = "/endsight-quiz-answers.txt";
        private final Map<String, String> defaults = new LinkedHashMap<>();
        private final Map<String, String> answers = new LinkedHashMap<>();
        // Wrong answers are worth as much as right ones here. Three options and two
        // failures name the third by elimination, so a lost room still teaches
        // something instead of nothing.
        private final Map<String, java.util.Set<String>> wrong = new LinkedHashMap<>();
        private Path path;
        private boolean dirty;

        /** The stored answer for a question, or null when it has not been seen. */
        public String answer(String question) {
            String key = normalize(question);
            String value = answers.getOrDefault(key, defaults.get(key));
            if (value != null && wrong.getOrDefault(key, java.util.Set.of()).stream()
                    .anyMatch(option -> rulesOut(option, value))) return null;
            return value;
        }

        public boolean knows(String question) {
            return answer(question) != null;
        }

        /** Note that an option was wrong. Returns true when this was news. */
        public boolean exclude(String question, String option) {
            String key = normalize(question);
            if (key.isEmpty() || option == null || option.isBlank()) return false;
            // A confirmed wrong press must silence a stale saved/default answer.
            // Otherwise the solver would keep pointing to the same failed button.
            boolean removed = answer(key) != null && rulesOut(option, answer(key))
                    && answers.remove(key) != null;
            boolean added = wrong.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(option.trim());
            dirty |= added || removed;
            return added || removed;
        }

        public java.util.Set<String> excluded(String question) {
            return java.util.Set.copyOf(wrong.getOrDefault(normalize(question), java.util.Set.of()));
        }

        /**
         * The answer, or the only option the exclusions leave standing.
         *
         * Elimination is only trusted when exactly one survives; two left is still a
         * guess, and a guess here fails the room.
         */
        public String deduce(String question, java.util.List<String> options) {
            String known = answer(question);
            if (known != null) return known;
            if (options == null || options.isEmpty()) return null;
            java.util.Set<String> out = excluded(question);
            java.util.List<String> left = options.stream()
                    .filter(o -> out.stream().noneMatch(e -> rulesOut(e, o))).toList();
            return left.size() == 1 ? left.get(0) : null;
        }

        public int size() {
            java.util.Set<String> names = new java.util.HashSet<>(defaults.keySet());
            names.addAll(answers.keySet());
            return names.size();
        }

        /**
         * Record an answer. Returns true when this taught us something new.
         *
         * A later answer overwrites an earlier one on purpose: if the server changes an
         * answer, the first wrong attempt after the change should be the last one.
         */
        public boolean learn(String question, String answer) {
            String key = normalize(question);
            if (key.isEmpty() || answer == null || answer.isBlank()) return false;
            String value = answer.trim();
            String old = answer(key);
            boolean hadExclusions = wrong.remove(key) != null;
            // A confirmation of a bundled answer needs no local override; a
            // different answer must survive a future bundled-data update.
            String previousLocal = value.equals(defaults.get(key)) ? answers.remove(key) : answers.put(key, value);
            boolean localChanged = previousLocal != null && !previousLocal.equals(answers.get(key))
                    || previousLocal == null && answers.containsKey(key);
            boolean changed = !value.equals(old) || hadExclusions;
            dirty |= changed || localChanged;
            return changed;
        }

        public void load(Path from) {
            path = from;
            defaults.clear();
            answers.clear();
            wrong.clear();
            dirty = false;
            try (InputStream bundled = QuizLogic.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (bundled != null) readLines(new String(bundled.readAllBytes(), StandardCharsets.UTF_8).lines().toList(),
                        defaults, new LinkedHashMap<>());
            } catch (IOException unreadable) {
                defaults.clear();
            }
            if (from == null || !Files.isReadable(from)) return;
            try {
                readLines(Files.readAllLines(from, StandardCharsets.UTF_8), answers, wrong);
            } catch (IOException unreadable) {
                // A broken local book must not take the bundled answers down.
                answers.clear();
                wrong.clear();
            }
        }

        private static void readLines(List<String> lines, Map<String, String> into,
                                      Map<String, java.util.Set<String>> exclusions) {
            for (String line : lines) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) continue;
                int no = text.indexOf("!=");
                if (no > 0 && no < text.length() - 2) {
                    java.util.Set<String> set = new java.util.LinkedHashSet<>();
                    for (String option : text.substring(no + 2).split(java.util.regex.Pattern.quote("|"))) {
                        if (!option.isBlank()) set.add(option.strip());
                    }
                    if (!set.isEmpty()) exclusions.put(normalize(text.substring(0, no)), set);
                    continue;
                }
                int split = text.indexOf('=');
                if (split <= 0 || split == text.length() - 1) continue;
                into.put(normalize(text.substring(0, split)), text.substring(split + 1).strip());
            }
        }

        /** Write the book back, but only when it changed. */
        public void save() {
            if (!dirty || path == null) return;
            List<String> lines = new ArrayList<>();
            lines.add("# Endsight dungeon quiz answers, learned in game.");
            lines.add("# One 'question = answer' per line. Safe to edit or share.");
            // Keep the player's file small and let future corrected defaults apply.
            answers.forEach((question, answer) -> lines.add(question + " = " + answer));
            wrong.forEach((question, options) -> {
                if (!answers.containsKey(question) && !options.isEmpty()) {
                    lines.add(question + " != " + String.join("|", options));
                }
            });
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.write(path, lines, StandardCharsets.UTF_8);
                dirty = false;
            } catch (IOException unwritable) {
                // Keep the answers in memory for this session rather than losing the run.
            }
        }

        /** Exposed for the checks; the live book is private to the feature. */
        public Map<String, String> entries() {
            Map<String, String> all = new LinkedHashMap<>(defaults);
            all.putAll(answers);
            return Map.copyOf(all);
        }
    }

    /**
     * Which option the player chose, held until the server says whether it was right.
     *
     * The confirmation is the counter moving on: a question that advances from 1/3 to
     * 2/3 was answered correctly, because a wrong press fails the room instead. So the
     * pending answer is bound to the question it was given for, and committed only when
     * a LATER question appears - never on the press itself.
     */
    public static final class Pending {
        private String question;
        private String answer;

        public void offer(String forQuestion, String option) {
            if (forQuestion == null || option == null) return;
            question = forQuestion;
            answer = option;
        }

        public void clear() {
            question = null;
            answer = null;
        }

        public boolean has() {
            return question != null && answer != null;
        }

        public String question() {
            return question;
        }

        public String answer() {
            return answer;
        }

        /**
         * Commit the pending answer if the puzzle moved past the question it was for.
         * Returns true when something was learned.
         */
        public boolean confirm(Book book, Question now) {
            if (!has() || now == null) return false;
            if (normalize(now.text()).equals(normalize(question))) return false;  // same question, no verdict yet
            boolean learned = book.learn(question, answer);
            clear();
            return learned;
        }
    }
}
