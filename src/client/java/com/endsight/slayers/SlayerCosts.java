package com.endsight.slayers;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Menu prices matched to confirmed quest starts, never to menu clicks or kills. */
public final class SlayerCosts {
    private static final Pattern TARGET = Pattern.compile("Slay ([\\d,]+) Combat XP worth of (\\w+)");
    private static final Pattern PRICE = Pattern.compile("^Start Cost: ([\\d,]+) coins$");
    private static final Pattern REQUIREMENT = Pattern.compile("^XP Required: ([\\d,]+)$");
    /** Spare Change Talisman, Small Loan and Big Loan all say it this way: 10, 20 and 30%. */
    private static final Pattern LOAN = Pattern.compile("cost of starting a\\s+quest by (\\d+)%");
    private static final long START_WINDOW_MS = 5_000;
    private final Map<Key, Long> prices = new HashMap<>();
    private boolean awaitingTarget;
    private long startedAt;
    private long lastChargedAt = Long.MIN_VALUE;
    private Key lastCharged;

    private record Key(String boss, long xp) { }
    public record Quote(String boss, long xp, long coins) { }
    /** A negative cost means the quest was confirmed but its price is unknown. */
    public record Start(String boss, long coins) { }

    public SlayerCosts() {
        // Observed in this server's menus. A newly observed menu always wins; these
        // are not Hypixel prices or estimates derived from a player's purse changes.
        learn(new Quote("Revenant", 4_000, 500_000));
        learn(new Quote("Revenant", 11_000, 2_500_000));
        learn(new Quote("Revenant", 13_750, 5_000_000));
        learn(new Quote("Voidgloom", 4_000, 500_000));
        learn(new Quote("Voidgloom", 11_000, 5_000_000));
        learn(new Quote("Voidgloom", 13_750, 15_000_000));
    }

    public void learn(Quote quote) {
        if (quote != null && quote.xp > 0 && quote.coins >= 0) {
            prices.put(new Key(quote.boss, quote.xp), quote.coins);
        }
    }

    public static Quote quote(String item, List<String> lore) {
        String boss = item.startsWith("Revenant Horror") || item.equals("Atoned Horror") ? "Revenant"
                : item.startsWith("Voidgloom Seraph") || item.equals("Riftborn Seraph") ? "Voidgloom" : null;
        if (boss == null) return null;
        long price = -1, xp = -1;
        try {
            for (String line : lore) {
                Matcher match = PRICE.matcher(line.trim());
                if (match.matches()) price = number(match.group(1));
                match = REQUIREMENT.matcher(line.trim());
                if (match.matches()) xp = number(match.group(1));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return price >= 0 && xp > 0 ? new Quote(boss, xp, price) : null;
    }

    public Start onLine(String line, long now) {
        if (line.contains("SLAYER QUEST STARTED")) {
            if (!awaitingTarget) startedAt = now;
            awaitingTarget = true;
        }
        if (line.contains("SLAYER QUEST FAILED") || line.contains("SLAYER QUEST COMPLETE")
                || line.contains("SLAYER BOSS SLAIN")) {
            resetPending();
            return null;
        }
        Matcher match = TARGET.matcher(line);
        if (!match.find() || !awaitingTarget) return null;
        awaitingTarget = false;
        if (now < startedAt || now - startedAt > START_WINDOW_MS) return null;
        String target = match.group(2).toLowerCase(Locale.ROOT);
        String boss = target.startsWith("zombie") ? "Revenant" : target.startsWith("ender") ? "Voidgloom" : null;
        if (boss == null) return null;
        long xp;
        try {
            xp = number(match.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
        Key key = new Key(boss, xp);
        // Repeated copies of the same announcement are not a second paid quest.
        if (key.equals(lastCharged) && now >= lastChargedAt && now - lastChargedAt < 250) return null;
        lastCharged = key;
        lastChargedAt = now;
        return new Start(boss, prices.getOrDefault(key, -1L));
    }

    public void resetPending() {
        awaitingTarget = false;
    }

    /**
     * The quest discount an accessory's lore promises, or 0.
     *
     * Read from the lore rather than listed by item name, so the loan you upgrade to next
     * is picked up without a code change. The lines are joined first because the tooltip
     * wraps "Reduces the cost of starting a" and "quest by 20%." onto two lines.
     */
    public static int loan(List<String> lore) {
        Matcher match = LOAN.matcher(String.join(" ", lore));
        if (!match.find()) return 0;
        try {
            return Math.min(100, Integer.parseInt(match.group(1)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * What a start really costs with a loan in the bag.
     *
     * The menu's Start Cost is taken as the full price, with the loan coming off here.
     * Every price learned so far was seen with no loan in the bag, so those are full
     * prices whichever way the server does it; what has not been seen is the menu with a
     * loan in. If the menu drops its own price then, this takes the loan off twice.
     */
    public static long afterLoan(long coins, int percent) {
        if (coins < 0 || percent <= 0) return coins;
        return coins * (100 - Math.min(100, percent)) / 100;
    }

    /** Requested migration assumption: old kills are T5; old failures are unknowable. */
    public static long historicalCost(String boss, int kills) {
        long price = switch (boss) {
            case "Revenant" -> 5_000_000L;
            case "Voidgloom" -> 15_000_000L;
            default -> 0L;
        };
        return price * Math.max(0, kills);
    }

    private static long number(String text) {
        return Long.parseLong(text.replace(",", ""));
    }
}
