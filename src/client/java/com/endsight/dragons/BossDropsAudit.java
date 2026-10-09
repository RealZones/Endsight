package com.endsight.dragons;

import com.endsight.qol.Drops;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Read-only evidence from Minecraft's own chat logs. This deliberately does not replay
 * BossDrops' live handler: its wall-clock dedupe, inventory baselines and save writes
 * would give different answers when months of messages are read in seconds.
 */
public final class BossDropsAudit {
    private BossDropsAudit() { }

    public record Report(String text, int events, List<Fix> fixes) { }

    /** One count /drops audit apply would set: from what is saved to what the logs show. */
    public record Fix(String boss, String item, int from, int to) { }

    /** The item a kill-count fix names. */
    public static final String KILLS = "#kills";
    /** Other players' dragons an earlier apply already took off ("lobby" lines in the save). */
    private static final String LOBBY = "#lobby";

    private static final LocalDate FEATURE_DATE = LocalDate.of(2026, 9, 16);
    private static final Pattern VERSION = Pattern.compile("(?:^\\s*|:\\s+)- endsight (\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:\\s|$)");
    private static final Pattern LOG_DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})-\\d+\\.log\\.gz$");
    private static final Pattern TIME = Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})]");
    private static final Pattern COLOUR = Pattern.compile("§[0-9A-FK-ORa-fk-or]");
    private static final Pattern DRAGON_DEAD = Pattern.compile("^☠ The (?:(.+?) )?Dragon has de-spawned");
    private static final Pattern POSITION = Pattern.compile("Your Damage:.*?\\(Position #(\\d+)\\)", Pattern.DOTALL);
    private static final Pattern OBTAINED = Pattern.compile("^(?:\\[[^\\]]+\\] )?(\\S+?)(?: \\S)? has obtained (.+?)!$");
    private static final Pattern TARGET = Pattern.compile("Slay [\\d,]+ Combat XP worth of (\\w+)");
    private static final Pattern SUMMARY_LINE = Pattern.compile("^-\\s*(\\d+)x\\s+(.+)$");
    private static final Pattern DROP_LINE = Pattern.compile("^[A-Z][A-Z ]*?\\s*DROP!\\s*\\(?([^()]+?)\\)?(?:\\s*\\(.*)?$");
    private static final Pattern PET = Pattern.compile("^\\[Lvl \\d+] ");
    private static final Pattern RATED = Pattern.compile(" \\((?:Common/Uncommon|Rare|Epic|Legendary)\\)$");
    private static final Pattern ANY_RARITY = Pattern.compile(" \\((?:Common/Uncommon|Rare|Epic|Legendary|rarity unknown)\\)$");
    private static final List<String> BOSSES = List.of("Dragon", "Warden", "Golem", "Revenant", "Voidgloom");

    private record FileInfo(Path path, LocalDate date, String version, boolean hasModList) { }
    private record Observation(long at, String raw, String item, int quantity) { }
    private record Box(long at, int place) { }

    private static final class Event {
        final String boss, when;
        final long at;
        boolean mine;
        /** Counted by 0.6.1-1.5.0 from the lobby-wide awoken line alone, with no eye of yours. */
        boolean lobbyOnly;
        final Map<String, Integer> drops = new LinkedHashMap<>();
        Event(String boss, String when, long at, boolean mine) {
            this.boss = boss;
            this.when = when;
            this.at = at;
            this.mine = mine;
        }
        void add(String raw, String item, int quantity) {
            if (quantity <= 0 || item.endsWith(" Fragment") || !BossDrops.auditCounted(boss, item)) return;
            String key = petKey(raw, item);
            // Debug, announcement and summary can each describe one reward. The summary
            // carries stack quantity, so take the largest observation for this kill.
            drops.merge(key, quantity, Math::max);
            mine = true;
        }
    }

    private static final class Session {
        final String player;
        final LocalDate date;
        final List<String> listed;
        final boolean awokenRule;
        boolean awoken;
        final List<Event> events = new ArrayList<>();
        final List<Observation> pending = new ArrayList<>();
        final Map<String, Box> boxes = new HashMap<>();
        long lastAt = -1, dayOffset, summaryAt = -1;
        Event summary;
        int eyes, unmatchedSummaries, unknownSlayers;
        String slayer;

        Session(LocalDate date, String player, List<String> listed, boolean awokenRule) {
            this.date = date;
            this.player = player;
            this.listed = listed;
            this.awokenRule = awokenRule;
        }

        void line(String full) {
            Matcher clock = TIME.matcher(full);
            if (!clock.find()) return;
            long second = Integer.parseInt(clock.group(1)) * 3_600L
                    + Integer.parseInt(clock.group(2)) * 60L + Integer.parseInt(clock.group(3));
            long at = dayOffset + second;
            if (lastAt >= 0 && at + 43_200 < lastAt) {
                dayOffset += 86_400;
                at += 86_400;
            }
            lastAt = at;
            int chat = full.indexOf("[CHAT] ");
            if (chat < 0) return;
            String raw = full.substring(chat + 7).trim();
            String text = COLOUR.matcher(raw).replaceAll("").trim();
            if (DragonTimer.isPlayerChat(text)) return;
            String when = date.plusDays(dayOffset / 86_400) + " " + clock.group(1) + ":" + clock.group(2) + ":" + clock.group(3);

            if (text.startsWith("Here is your loot from the last dragon:")) {
                summary = recent("Dragon", at, 20);
                summaryAt = at;
                if (summary == null) unmatchedSummaries++;
                else summary.mine = true; // This summary is only sent to a paid player.
                return;
            }
            if (summary != null && at - summaryAt <= 2) {
                Matcher row = SUMMARY_LINE.matcher(text);
                if (row.find()) {
                    summary.add(raw, row.group(2).trim(), Integer.parseInt(row.group(1)));
                    return;
                }
            }
            if (at - summaryAt > 2) summary = null;

            Matcher target = TARGET.matcher(text);
            if (target.find()) slayer = target.group(1).toLowerCase(Locale.ROOT).startsWith("ender") ? "Voidgloom" : "Revenant";
            if (text.contains("You placed a Summoning Eye") || text.contains("You placed a Golden Eye")) eyes++;
            // Sent to the whole lobby, so it proves nothing; only the old tracker took it as yours.
            if (text.contains("Your Sleeping Eyes have been awoken")) awoken = true;
            if (text.contains("Egg has Spawned")) {
                eyes = 0;
                awoken = false;
            }

            Matcher dragon = DRAGON_DEAD.matcher(text);
            if (dragon.find()) {
                String boss = dragon.group(1) == null ? "Dragon" : dragon.group(1) + " Dragon";
                create(boss, when, at, eyes > 0);
                events.get(events.size() - 1).lobbyOnly = awokenRule && awoken && eyes == 0;
                eyes = 0;
                awoken = false;
                return;
            }
            if (text.contains("The Warden has been defeated")) {
                create("Warden", when, at, placed("Warden", at));
                return;
            }
            if (text.contains("The Endstone Protector has been defeated")) {
                create("Golem", when, at, placed("Golem", at));
                return;
            }
            if (text.contains("THE WARDEN DOWN!") || text.contains("ENDSTONE PROTECTOR DOWN!")) {
                String boss = text.contains("THE WARDEN DOWN!") ? "Warden" : "Golem";
                Matcher place = POSITION.matcher(text);
                if (place.find()) {
                    int n = Integer.parseInt(place.group(1));
                    boxes.put(boss, new Box(at, n));
                    Event e = recent(boss, at, 3);
                    if (e != null && n <= 3) e.mine = true;
                }
                return;
            }
            if (text.contains("SLAYER BOSS SLAIN")) {
                if (slayer == null) unknownSlayers++;
                else create(slayer, when, at, true);
                return;
            }
            Matcher obtained = OBTAINED.matcher(text);
            if (obtained.find() && obtained.group(1).equalsIgnoreCase(player)) {
                Event e = recent("Dragon", at, 20);
                if (e != null) e.add(raw, obtained.group(2).trim(), 1);
                return;
            }
            String item = item(raw, text, listed);
            if (item == null) return;
            Event e = recentAny(at, 3);
            if (e == null) pending.add(new Observation(at, raw, item, 1));
            else e.add(raw, item, 1);
            long eventAt = at;
            pending.removeIf(x -> eventAt - x.at > 3);
        }

        private boolean placed(String boss, long at) {
            Box box = boxes.get(boss);
            return box != null && Math.abs(at - box.at) <= 3 && box.place <= 3;
        }

        private void create(String boss, String when, long at, boolean mine) {
            Event e = new Event(boss, when, at, mine);
            events.add(e);
            for (Observation x : pending) if (at - x.at >= 0 && at - x.at <= 2) e.add(x.raw, x.item, x.quantity);
            pending.removeIf(x -> at - x.at >= 0 && at - x.at <= 2);
        }

        private Event recent(String boss, long at, long within) {
            for (int i = events.size() - 1; i >= 0; i--) {
                Event e = events.get(i);
                if (at - e.at > within) break;
                if (at >= e.at && (boss.equals("Dragon") ? e.boss.equals("Dragon") || e.boss.endsWith(" Dragon") : e.boss.equals(boss))) return e;
            }
            return null;
        }

        private Event recentAny(long at, long within) {
            for (int i = events.size() - 1; i >= 0; i--) {
                Event e = events.get(i);
                if (at - e.at > within) break;
                if (at >= e.at) return e;
            }
            return null;
        }
    }

    public static Report scan(Path logs, Path save, String player) throws IOException {
        List<Path> paths;
        try (Stream<Path> files = Files.list(logs)) {
            paths = files.filter(p -> p.getFileName().toString().endsWith(".log.gz")
                    || p.getFileName().toString().equals("latest.log"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().equals("latest.log")
                            ? "9999" : p.getFileName().toString())).toList();
        }
        Map<String, Integer> saved = readSaved(save);
        List<String> listed = listed(save.getParent() == null ? null : save.getParent().resolve("drops.txt"));
        List<Event> all = new ArrayList<>();
        int preFeature = 0, absent = 0, inferred = 0, eligible = 0, unmatched = 0, unknownSlayers = 0;
        boolean supported = false, firstSupported = false;
        String version = null;
        LocalDate first = null, last = null;
        for (Path path : paths) {
            FileInfo file = probe(path);
            if (file.date == null || file.date.isBefore(FEATURE_DATE)) { preFeature++; continue; }
            if (file.version != null) {
                version = file.version;
                supported = supports(file.version);
                if (supported) firstSupported = true;
            } else if (file.hasModList) {
                supported = false; // A startup that omitted Endsight is not a rollover.
            } else if (supported && firstSupported) {
                inferred++;
            }
            if (!supported || !firstSupported) { absent++; continue; }
            Session session = new Session(file.date, player, listed, awokenRule(version));
            try (BufferedReader in = open(path)) {
                for (String line; (line = in.readLine()) != null;) session.line(line);
            }
            all.addAll(session.events);
            eligible++;
            unmatched += session.unmatchedSummaries;
            unknownSlayers += session.unknownSlayers;
            if (first == null) first = file.date;
            last = file.date;
        }
        return report(all, saved, eligible, preFeature, absent, inferred, unmatched, unknownSlayers, first, last);
    }

    private static FileInfo probe(Path path) throws IOException {
        String name = path.getFileName().toString();
        Matcher date = LOG_DATE.matcher(name);
        LocalDate day = date.find() ? LocalDate.parse(date.group(1))
                : name.equals("latest.log") ? Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis())
                        .atZone(ZoneId.systemDefault()).toLocalDate() : null;
        String version = null;
        boolean hasModList = false;
        try (BufferedReader in = open(path)) {
            for (String line; (line = in.readLine()) != null;) {
                if (line.contains("Loading ") && line.contains(" mods:")) hasModList = true;
                Matcher found = VERSION.matcher(line);
                if (found.find()) { version = found.group(1) + "." + found.group(2) + "." + (found.group(3) == null ? "0" : found.group(3)); break; }
                if (line.contains("[CHAT] ")) break;
            }
        }
        return new FileInfo(path, day, version, hasModList);
    }

    /** 0.6.1 started counting a dragon from the awoken line alone; 1.6.0 stopped. */
    static boolean awokenRule(String version) {
        if (version == null) return false;
        String[] p = version.split("\\.");
        int[] v = {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        boolean from = v[0] > 0 || v[1] > 6 || (v[1] == 6 && v[2] >= 1);
        boolean before = v[0] < 1 || (v[0] == 1 && v[1] < 6);
        return from && before;
    }

    static boolean supports(String version) {
        String[] p = version.split("\\.");
        int major = Integer.parseInt(p[0]), minor = Integer.parseInt(p[1]);
        return major > 0 || minor >= 6;
    }

    private static BufferedReader open(Path path) throws IOException {
        return new BufferedReader(new InputStreamReader(path.getFileName().toString().endsWith(".gz")
                ? new GZIPInputStream(Files.newInputStream(path)) : Files.newInputStream(path), StandardCharsets.UTF_8));
    }

    private static String item(String raw, String text, List<String> listed) {
        if (text.contains("loot number:") && raw.contains("→")) {
            String name = COLOUR.matcher(raw.substring(raw.indexOf('→') + 1)).replaceAll("").trim();
            // A pet without a coloured name has no trustworthy rarity in this line.
            if (PET.matcher(name).find()) return Drops.petTier(raw) >= 0 ? name : null;
            String lower = name.toLowerCase(Locale.ROOT);
            return listed.stream().anyMatch(lower::contains) ? name : null;
        }
        Matcher drop = DROP_LINE.matcher(text);
        return drop.find() ? drop.group(1).trim() : null;
    }

    private static String petKey(String raw, String item) {
        if (!PET.matcher(item).find()) return item;
        int tier = Drops.petTier(raw);
        String rarity = switch (tier) {
            case 3 -> "Legendary";
            case 2 -> "Epic";
            case 1 -> "Rare";
            case 0 -> "Common/Uncommon";
            default -> "rarity unknown";
        };
        return item + " (" + rarity + ")";
    }

    private static List<String> listed(Path config) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = BossDropsAudit.class.getResourceAsStream("/endsight-drops.txt")) {
            if (in != null) readNames(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList(), names);
        }
        if (config != null && Files.exists(config)) readNames(Files.readAllLines(config, StandardCharsets.UTF_8), names);
        return names;
    }

    private static void readNames(List<String> lines, List<String> names) {
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length < 2 || !List.of("common", "rare", "epic", "legendary").contains(parts[0].toLowerCase(Locale.ROOT))) continue;
            names.add(parts[1].replaceFirst("\\s*\\([^)]*\\)\\s*$", "").toLowerCase(Locale.ROOT));
        }
    }

    /**
     * What the logs prove, and nothing past it.
     *
     * Pets: a row saved before rarities were kept ("[Lvl 1] Ender Dragon") got one tier for
     * every pet of that name, so an Epic Ender Dragon showed as Legendary. The logs still have
     * each pet's name in its rarity colour. Where they account for every pet of that name
     * already saved for that boss, the old row is relabelled and missed pets added; where they
     * do not (pets from before the oldest log), it is left alone. Dragon drops: one the logs
     * show that was never counted is added. DragSim stopped naming dragon loot in its debug
     * line on 2026-10-07, and Dragon Drops missed most of it until it read the loot summary.
     *
     * Kills: the dragons 0.6.1-1.5.0 counted from the lobby-wide awoken line with no eye of
     * yours and no loot come off, one each - the tracker counted every one of those, so this
     * takes back exactly what it added. Nothing else goes down: saved kills run well above
     * logged ones (sessions whose logs are gone), so a higher saved number is no evidence of
     * a double count. Slayer items are left out: a Zombie Talisman from a ghoul in the same
     * seconds reads as the boss's.
     */
    static List<Fix> fixes(Map<String, Integer> logged, Map<String, Integer> saved, Map<String, Integer> notYours) {
        List<Fix> out = new ArrayList<>();
        notYours.forEach((boss, n) -> {
            int have = saved.getOrDefault(boss + "\t" + KILLS, 0);
            int left = n - saved.getOrDefault(boss + "\t" + LOBBY, 0);     // an earlier apply took the rest
            if (have > 0 && left > 0) out.add(new Fix(boss, KILLS, have, Math.max(0, have - left)));
        });
        Map<String, Map<String, Integer>> loggedPets = new LinkedHashMap<>(), savedRated = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> savedOld = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : saved.entrySet()) {
            String[] k = e.getKey().split("\t", 2);
            if (k.length < 2 || !PET.matcher(k[1]).find()) continue;
            String group = k[0] + "\t" + ANY_RARITY.matcher(k[1]).replaceFirst("");
            (RATED.matcher(k[1]).find() ? savedRated : savedOld)
                    .computeIfAbsent(group, x -> new LinkedHashMap<>()).put(k[1], e.getValue());
        }
        for (Map.Entry<String, Integer> e : logged.entrySet()) {
            String[] k = e.getKey().split("\t", 2);
            if (!PET.matcher(k[1]).find()) {
                int have = saved.getOrDefault(e.getKey(), 0);
                if (dragon(k[0]) && e.getValue() > have) out.add(new Fix(k[0], k[1], have, e.getValue()));
                continue;
            }
            if (!RATED.matcher(k[1]).find()) continue;                    // no colour, no proof
            loggedPets.computeIfAbsent(k[0] + "\t" + ANY_RARITY.matcher(k[1]).replaceFirst(""),
                    x -> new LinkedHashMap<>()).put(k[1], e.getValue());
        }
        for (Map.Entry<String, Map<String, Integer>> g : loggedPets.entrySet()) {
            String boss = g.getKey().split("\t", 2)[0];
            Map<String, Integer> old = savedOld.getOrDefault(g.getKey(), Map.of());
            Map<String, Integer> rated = savedRated.getOrDefault(g.getKey(), Map.of());
            int seen = g.getValue().values().stream().mapToInt(Integer::intValue).sum();
            int kept = Stream.of(old, rated).flatMap(m -> m.values().stream()).mapToInt(Integer::intValue).sum();
            if (seen < kept) continue;                                    // pets from before the logs
            for (Map.Entry<String, Integer> o : old.entrySet()) out.add(new Fix(boss, o.getKey(), o.getValue(), 0));
            for (Map.Entry<String, Integer> r : g.getValue().entrySet()) {
                int have = rated.getOrDefault(r.getKey(), 0);
                if (r.getValue() > have) out.add(new Fix(boss, r.getKey(), have, r.getValue()));
            }
        }
        return out;
    }

    private static boolean dragon(String boss) {
        return boss.equals("Dragon") || boss.endsWith(" Dragon");
    }

    private static Map<String, Integer> readSaved(Path save) throws IOException {
        Map<String, Integer> saved = new HashMap<>();
        if (!Files.exists(save)) return saved;
        for (String line : Files.readAllLines(save, StandardCharsets.UTF_8)) {
            String[] p = line.split("\\t");
            if (p.length == 3 && p[0].equals("kill")) saved.put(p[1] + "\t" + KILLS, Integer.parseInt(p[2]));
            if (p.length == 3 && p[0].equals("lobby")) saved.put(p[1] + "\t" + LOBBY, Integer.parseInt(p[2]));
            if (p.length == 4 && p[0].equals("drop")) saved.put(p[1] + "\t" + p[2], Integer.parseInt(p[3]));
        }
        return saved;
    }

    private static Report report(List<Event> all, Map<String, Integer> saved, int eligible, int preFeature,
                                 int absent, int inferred, int unmatched, int unknownSlayers,
                                 LocalDate first, LocalDate last) {
        Map<String, Integer> kills = new LinkedHashMap<>(), drops = new LinkedHashMap<>(), notYours = new LinkedHashMap<>();
        Map<String, List<String>> evidence = new HashMap<>();
        int owned = 0;
        for (Event e : all) {
            if (e.lobbyOnly && !e.mine) notYours.merge(e.boss, 1, Integer::sum);
            if (!e.mine) continue;
            owned++;
            kills.merge(e.boss, 1, Integer::sum);
            for (Map.Entry<String, Integer> d : e.drops.entrySet()) {
                String key = e.boss + "\t" + d.getKey();
                drops.merge(key, d.getValue(), Integer::sum);
                List<String> lines = evidence.computeIfAbsent(key, x -> new ArrayList<>());
                if (lines.size() < 5) lines.add(e.when);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Endsight drop audit (read-only)\n")
                .append("Player: ").append(first == null ? "unknown" : "local account").append("\n")
                .append("Supported log range: ").append(first).append(" through ").append(last).append("\n")
                .append("Files read: ").append(eligible).append("; pre-feature skipped: ").append(preFeature)
                .append("; absent/unsupported skipped: ").append(absent)
                .append("; rollover version inferred: ").append(inferred).append("\n")
                .append("This is evidence seen in retained logs, NOT a replacement all-time total.\n")
                .append("The save has no event timestamps. Missing/rotated logs, disabled modules, inventory-only rewards,\n")
                .append("Warden catalyst credit and Slayer costs cannot be recovered exactly. No counts were changed.\n")
                .append("Unmatched dragon summaries: ").append(unmatched)
                .append("; unknown Slayer targets: ").append(unknownSlayers).append("\n\n");
        List<String> bosses = new ArrayList<>(BOSSES);
        for (String boss : kills.keySet()) if (!bosses.contains(boss)) bosses.add(boss);
        for (String boss : bosses) {
            int observed = boss.equals("Dragon") ? kills.entrySet().stream()
                    .filter(e -> e.getKey().equals("Dragon") || e.getKey().endsWith(" Dragon"))
                    .mapToInt(Map.Entry::getValue).sum() : kills.getOrDefault(boss, 0);
            int stored = boss.equals("Dragon") ? saved.entrySet().stream()
                    .filter(e -> e.getKey().endsWith("\t#kills") && (e.getKey().startsWith("Dragon\t") || e.getKey().contains(" Dragon\t")))
                    .mapToInt(Map.Entry::getValue).sum() : saved.getOrDefault(boss + "\t#kills", 0);
            out.append(boss).append(": logged owned kills ").append(observed)
                    .append(", saved ").append(stored).append("\n");
        }
        out.append("\nDragons counted that were not yours (died in your lobby, no eye of yours): ")
                .append(notYours.values().stream().mapToInt(Integer::intValue).sum()).append("\n");
        notYours.forEach((boss, n) -> out.append("  ").append(boss).append(": ").append(n).append("\n"));
        out.append("\nNamed drop evidence (logged vs saved):\n");
        drops.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            int current = saved.getOrDefault(e.getKey(), 0);
            out.append(e.getKey().replace('\t', ' ')).append(": ").append(e.getValue())
                    .append(" logged; ").append(current).append(" saved");
            if (e.getValue() > current) out.append("  <-- inspect");
            out.append("\n");
            if (e.getValue() > current) for (String when : evidence.get(e.getKey())) out.append("    ").append(when).append("\n");
        });
        List<Fix> fixes = fixes(drops, saved, notYours);
        out.append("\nCorrections /drops audit apply would make (").append(fixes.size()).append("):\n");
        if (fixes.isEmpty()) out.append("  none\n");
        for (Fix f : fixes) out.append("  ").append(f.boss()).append(" ").append(f.item())
                .append(": ").append(f.from()).append(" -> ").append(f.to()).append("\n");
        out.append("Apply only lowers kills the old tracker counted from other players' dragons, and never touches\n")
                .append("Slayer items. It backs the save up first.\n");
        return new Report(out.toString(), owned, fixes);
    }
}
