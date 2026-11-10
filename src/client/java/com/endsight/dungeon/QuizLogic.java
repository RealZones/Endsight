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
 * Since Dragsim's new dungeons (seen 2026-11-09) the room is Hypixel's word for word:
 * Oruo the Omniscient asks in chat ("Question #1", the question on the next line, then
 * "ⓐ Wendell", "ⓑ Jamie", "ⓒ Pat") and a label such as "ⓒ Pat." floats over the block
 * you click for each answer. The old room, a "1/3 ..." hologram over lettered signs, is
 * gone, and so is the code that read it.
 *
 * Lore answers ship as defaults. Each player's own answers and exclusions stay in a
 * separate file and override those defaults, so a server answer that differs from
 * Hypixel's is learned without changing the jar: "How many total Fairy Souls are
 * there?" was 289 here on the first night, which is not Hypixel's count.
 *
 * The book is plain text, one "question = answer" per line, so it can be read, edited
 * and shared by hand.
 */
public final class QuizLogic {

    private QuizLogic() {
    }

    /** "1/3 Which Wither Lord ..." - the old room's counter, still stripped from saved books. */
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)\\s*/\\s*(\\d+)\\s*(.*)$");
    private static final Pattern HEADER = Pattern.compile("^Question #(\\d+)$");
    /** "ⓐ Wendell" in chat, "ⓐ Wendell." on the label over its block. */
    private static final Pattern CIRCLED = Pattern.compile("^[ⓐ-ⓩ]\\s*(.+)$");

    /**
     * Strip the colour codes and the decoration, so the same question recognises itself
     * next week. Lowercased and stripped of trailing punctuation: the server is free to
     * re-punctuate a line and we are not free to care.
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

    /**
     * An option without its slot letter: "ⓒ Pat." on the label and "ⓒ Pat" in chat are
     * both "Pat". The letters say where an answer stands this time, not what it is, so
     * nothing is ever stored with one.
     *
     * The old room's "C Storm" form is still read, because saved books hold answers like
     * that. One leading letter and a space only, and an exact match wins in
     * {@link #resolve}, so a real answer such as "A chest" is never cut down.
     */
    public static String option(String raw) {
        if (raw == null) return "";
        String text = raw.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
        Matcher circled = CIRCLED.matcher(text);
        if (circled.matches()) return circled.group(1).replaceAll("\\.+$", "").trim();
        if (text.length() > 2 && Character.isLetter(text.charAt(0)) && text.charAt(1) == ' ') {
            String rest = text.substring(2).trim();
            if (!rest.isEmpty()) return rest;
        }
        return text;
    }

    /** Whether a line or label carries a circled slot letter, i.e. is one of the options. */
    static boolean lettered(String raw) {
        return raw != null && CIRCLED.matcher(raw.replaceAll("§.", "").trim()).matches();
    }

    /** The live option a stored answer names; an old lettered answer finds its bare option. */
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
     * The SkyBlock year, for "What SkyBlock year is it?". Dragsim keeps Hypixel's
     * calendar: year 1 began 2019-06-11 17:55 UTC and a year is 124 hours. On
     * 2026-11-09 this gave 525, Early Spring 25th, which is what the server's sidebar
     * said and the answer it accepted.
     */
    static int skyblockYear(long epochMillis) {
        return (int) Math.floorDiv(epochMillis - 1_560_275_700_000L, 446_400_000L) + 1;
    }

    /** The answer for a question that is worked out rather than remembered, or null. */
    static String worked(String question, long epochMillis) {
        return normalize(question).equals("what skyblock year is it") ? "Year " + skyblockYear(epochMillis) : null;
    }

    /**
     * One question as Oruo asks it, assembled from separate chat lines: the header, the
     * question on the next line, then the three lettered options.
     */
    static final class Asking {
        private int index;
        private String text;
        private final List<String> options = new ArrayList<>();
        private boolean open;

        /** Feed one plain chat line. True on the line that completes a question. */
        boolean feed(String line) {
            String text = line == null ? "" : line.replaceAll("§.", "").trim();
            Matcher header = HEADER.matcher(text);
            if (header.matches()) {
                index = Integer.parseInt(header.group(1));
                this.text = null;
                options.clear();
                open = true;
                return false;
            }
            if (!open || text.isEmpty()) return false;
            if (this.text == null) {
                if (lettered(text)) { open = false; return false; }   // no question line: not ours
                this.text = text;
                return false;
            }
            if (!lettered(text)) return false;
            options.add(option(text));
            if (options.size() < 3) return false;
            open = false;
            return true;
        }

        int index() { return index; }
        String text() { return text; }
        List<String> options() { return List.copyOf(options); }
        void clear() { index = 0; text = null; options.clear(); open = false; }
    }

    enum Verdict { RIGHT, WRONG, RESET, DONE }

    /** A verdict and whose answer it was; the name is null for RESET and DONE. */
    record Ruling(Verdict verdict, String name) { }

    private static final String ORUO = "Oruo the Omniscient: ";
    private static final Pattern ANSWERED = Pattern.compile("^(\\S+) answered (?:Question #\\d+|the final question) correctly!$");
    private static final Pattern MISSED = Pattern.compile("^(\\S+) chose the wrong answer!.*$");

    /** What a chat line says about the quiz, or null when it says nothing. */
    static Ruling ruling(String line) {
        String text = line == null ? "" : line.replaceAll("§.", "").trim();
        if (text.equals("Quiz has reset. You can try again!")) return new Ruling(Verdict.RESET, null);
        int at = text.indexOf(ORUO);
        if (at < 0) return null;
        String said = text.substring(at + ORUO.length()).trim();
        if (said.startsWith("I bestow upon you")) return new Ruling(Verdict.DONE, null);
        Matcher m = ANSWERED.matcher(said);
        if (m.matches()) return new Ruling(Verdict.RIGHT, m.group(1));
        m = MISSED.matcher(said);
        if (m.matches()) return new Ruling(Verdict.WRONG, m.group(1));
        return null;
    }

    /**
     * Which answer a teammate took, from how far they stand from each answer's label:
     * the index of the nearest, or -1 unless it is within reach and clearly nearer than
     * the next. Oruo names who answered but not what, and you only see your own clicks;
     * a player has to be at the block to click it, and the blocks stand well apart.
     */
    static int standingAt(List<Double> distances, double reach, double margin) {
        int best = -1;
        double nearest = Double.MAX_VALUE, next = Double.MAX_VALUE;
        for (int i = 0; i < distances.size(); i++) {
            double d = distances.get(i);
            if (d < nearest) { next = nearest; nearest = d; best = i; }
            else if (d < next) next = d;
        }
        return best >= 0 && nearest <= reach && next - nearest >= margin ? best : -1;
    }

    /** Whether a line is Oruo speaking, which is how the room announces itself. */
    static boolean oruo(String line) {
        return line != null && line.replaceAll("§.", "").contains(ORUO);
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
            // Otherwise the solver would keep pointing to the same failed block.
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
         * The answer among these options, or the only option the exclusions leave
         * standing.
         *
         * A stored answer the room is not showing is no answer. "Which of these enemies
         * does not spawn in the Spider's Den?" has a different outsider each time, and
         * naming last time's would point at nothing. Elimination is only trusted when
         * exactly one survives; two left is still a guess, and a guess costs a reset.
         */
        public String deduce(String question, java.util.List<String> options) {
            String known = resolve(answer(question), options);
            if (options == null || options.isEmpty()) return known;
            if (known != null && options.contains(known)) return known;
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
}
