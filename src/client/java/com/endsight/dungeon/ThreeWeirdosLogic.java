package com.endsight.dungeon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two rooms. Dragsim's old room said "one of the three always lies" in single claims, so
 * its answer is worked out from the claims ({@link #solution}'s fallback).
 *
 * Its new dungeons (2026-11-09) use Hypixel's room instead: the weirdos say compound lines
 * ("The reward is not in my chest. They are both lying.") that the old parser could not
 * read, so the room never opened. Hypixel's room is not solved by logic - in the run of
 * that night no truth-teller count fits all three lines - but by script: the weirdo who
 * says one of six lines owns the reward chest. That night Virginia said "The reward isn't
 * in any of our chests." beside "The reward is not in my chest. They are both lying." and
 * "The reward is in my chest!".
 */
final class ThreeWeirdosLogic {
    /** Hypixel's six lines that mark the speaker's chest as the one, normalised. */
    private static final List<Pattern> ANSWER_LINES = List.of(
            Pattern.compile("^the reward is not in my chest$"),
            // Hypixel says "them's lying", Dragsim "them is lying" (2026-11-10: Eveleth said it,
            // Hope and Carver lied, and with only Hypixel's wording no chest was picked).
            Pattern.compile("^at least one of them(?:'s| is) lying, and the reward is not in .+'s chest$"),
            Pattern.compile("^my chest doesn't have the reward\\. we are all telling the truth$"),
            Pattern.compile("^my chest has the reward and i'm telling the truth$"),
            Pattern.compile("^the reward isn't in any of our chests$"),
            Pattern.compile("^both of them are telling the truth\\. also, .+ has the reward in their chest$"));
    /** The rest of that room's lines, so hearing one of them first still opens the room. */
    private static final List<Pattern> OTHER_LINES = List.of(
            Pattern.compile("^one of us is telling the truth$"),
            Pattern.compile("^they are both telling the truth\\. the reward isn't in .+'s chest$"),
            Pattern.compile("^we are all telling the truth$"),
            Pattern.compile("^.+ is telling the truth and the reward is in (?:his|her|their) chest$"),
            Pattern.compile("^my chest doesn't have the reward\\. at least one of the others is telling the truth$"),
            Pattern.compile("^one of the others is lying$"),
            Pattern.compile("^they are both telling the truth, the reward is in .+'s chest$"),
            Pattern.compile("^they are both lying, the reward is in my chest$"),
            Pattern.compile("^the reward is in my chest$"),
            Pattern.compile("^the reward is not in my chest\\. they are both lying$"),
            Pattern.compile("^.+ is telling the truth$"));

    static boolean answerLine(String text) { return ANSWER_LINES.stream().anyMatch(p -> p.matcher(text).matches()); }
    static boolean roomLine(String text) { return answerLine(text) || OTHER_LINES.stream().anyMatch(p -> p.matcher(text).matches()); }
    private static final Pattern NPC = Pattern.compile("^\\[NPC] ([A-Za-z][A-Za-z0-9_ -]{0,31}): (.+)$");
    private static final String NAME = "([a-z][a-z0-9_ -]{0,31}?)";
    private static final Pattern NAMED_CHEST = Pattern.compile("^(?:whatever you do, )?" + NAME + "'s chest is (not )?the one$");
    private static final Pattern NAMED_REWARD = Pattern.compile("^(?:the reward|the treasure) (is|is not|isn't) in " + NAME + "'s chest$");
    private static final Pattern DONT_TRUST = Pattern.compile("^don't trust " + NAME + ", (?:they're|they are|he's|he is|she's|she is) lying$");
    private static final Pattern TRUTH = Pattern.compile("^" + NAME + " (is lying|is telling the truth|tells the truth)$");

    enum Kind { CHEST, NOT_CHEST, LYING, TRUTHFUL }
    record Clue(Kind kind, String subject) { }
    record Statement(String name, String text, Clue clue) { }

    private final LinkedHashMap<String, Statement> statements = new LinkedHashMap<>();
    private boolean active, conflict;

    void begin() { statements.clear(); active = true; conflict = false; }
    void clear() { statements.clear(); active = false; conflict = false; }
    boolean active() { return active; }
    int answered() { return Math.min(3, statements.size()); }
    List<String> names() { return statements.values().stream().map(Statement::name).toList(); }

    /** Anchoring matters: a player's pasted [NPC] dialogue is not a room clue. */
    boolean accept(String raw) {
        String line = plain(raw);
        if (entry(line)) { begin(); return true; }
        if (line.startsWith("PUZZLE! ") || finished(line)) { clear(); return true; }
        Matcher npc = NPC.matcher(line);
        if (!npc.matches()) return false;
        String name = npc.group(1).trim();
        String key = name.toLowerCase(Locale.ROOT);
        String text = normalized(npc.group(2));
        // A clue opens the room by itself. Dragsim's new dungeon generation (seen
        // 2026-11-09) stopped saying "PUZZLE! Three Weirdos" on the way in, so waiting
        // for it ignored every weirdo. Only a line that reads as a clue counts, so Mort's
        // greeting at the start of a run does not open a room with a speaker in it.
        if (!active) {
            if (parse(name, text) == null && !roomLine(text)) return false;
            begin();
        }
        Statement old = statements.get(key);
        if (old != null) {
            // Repeated dialogue is common. A changed statement from the same NPC
            // cannot silently replace an earlier constraint and invent a solution.
            if (!old.text().equals(text)) conflict = true;
            return true;
        }
        if (statements.size() >= 3) { conflict = true; return true; }
        statements.put(key, new Statement(name, text, parse(name, text)));
        return true;
    }

    boolean unsupported() {
        if (conflict) return true;
        for (Statement statement : statements.values()) {
            Clue clue = statement.clue();
            if (clue == null) return true;
            if (statements.size() == 3 && !statements.containsKey(clue.subject())) return true;
        }
        return false;
    }

    String solution() {
        if (!active || conflict) return null;
        // Hypixel's room: the one weirdo saying an answer line. Two saying one is not that
        // room's script, so it falls through to the claims and, failing them, no answer.
        String scripted = null;
        int saying = 0;
        for (Statement statement : statements.values()) {
            if (answerLine(statement.text())) { scripted = statement.name(); saying++; }
        }
        if (saying == 1) return scripted;
        if (statements.size() != 3 || unsupported()) return null;
        List<String> names = new ArrayList<>(statements.keySet());
        String answer = null;
        for (String owner : names) {
            boolean possible = false;
            for (int truthMask = 0; truthMask < 8; truthMask++) {
                if (Integer.bitCount(truthMask) != 2) continue;
                boolean consistent = true;
                for (int i = 0; i < 3; i++) {
                    Clue clue = statements.get(names.get(i)).clue();
                    boolean truth = switch (clue.kind()) {
                        case CHEST -> owner.equals(clue.subject());
                        case NOT_CHEST -> !owner.equals(clue.subject());
                        case LYING -> (truthMask & (1 << names.indexOf(clue.subject()))) == 0;
                        case TRUTHFUL -> (truthMask & (1 << names.indexOf(clue.subject()))) != 0;
                    };
                    if (truth != ((truthMask & (1 << i)) != 0)) { consistent = false; break; }
                }
                if (consistent) { possible = true; break; }
            }
            if (possible) {
                if (answer != null) return null; // More than one chest satisfies every clue.
                answer = owner;
            }
        }
        return answer == null ? null : statements.get(answer).name();
    }

    static boolean entry(String raw) {
        String line = plain(raw).replace('\u2013', '-').replace('\u2014', '-');
        return line.equals("PUZZLE! Three Weirdos") || line.startsWith("PUZZLE! Three Weirdos - ");
    }

    static boolean finished(String raw) {
        String line = plain(raw);
        return line.equals("PUZZLE SOLVED! Three Weirdos") || line.equals("PUZZLE FAIL! Three Weirdos")
                || line.matches("^You picked [A-Za-z][A-Za-z0-9_ -]{0,31}'s chest\\. Correct!$");
    }

    static Clue parse(String speaker, String raw) {
        String text = normalized(raw), self = speaker.toLowerCase(Locale.ROOT);
        if (text.matches("^(?:the reward|the treasure) is in my chest$")) return new Clue(Kind.CHEST, self);
        if (text.matches("^(?:the reward|the treasure) (?:is not|isn't) in my chest$")) return new Clue(Kind.NOT_CHEST, self);
        if (text.matches("^my chest (?:has|contains) the (?:reward|treasure)$")) return new Clue(Kind.CHEST, self);
        // "My chest is empty, I promise." - this server's wording for the same claim.
        // The patterns above came from the other game's script, where a weirdo always
        // names the reward; here two of the three say only that their chest is empty,
        // so the puzzle parsed one clue out of three and gave up with "no guess".
        if (text.matches("^my chest is empty(?:, i promise)?$")) return new Clue(Kind.NOT_CHEST, self);
        Matcher m = NAMED_CHEST.matcher(text);
        if (m.matches()) return new Clue(m.group(2) == null ? Kind.CHEST : Kind.NOT_CHEST, m.group(1));
        m = NAMED_REWARD.matcher(text);
        if (m.matches()) return new Clue(m.group(1).equals("is") ? Kind.CHEST : Kind.NOT_CHEST, m.group(2));
        m = DONT_TRUST.matcher(text);
        if (m.matches()) return new Clue(Kind.LYING, m.group(1));
        m = TRUTH.matcher(text);
        if (m.matches()) return new Clue(m.group(2).equals("is lying") ? Kind.LYING : Kind.TRUTHFUL, m.group(1));
        return null;
    }

    private static String normalized(String raw) {
        return plain(raw).replace('\u2019', '\'').replaceAll("\\s+", " ")
                .replaceAll("[.!]+$", "").toLowerCase(Locale.ROOT);
    }

    static String plain(String raw) { return raw == null ? "" : com.endsight.zealots.Zealots.strip(raw).trim(); }
}
