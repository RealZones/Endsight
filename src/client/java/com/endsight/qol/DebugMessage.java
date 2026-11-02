package com.endsight.qol;

import com.endsight.dragons.DragonTimer;
import com.endsight.hud.Toast;
import com.endsight.ui.Draw;
import com.endsight.ui.Theme;
import com.endsight.zealots.Zealots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your own /debug line, read through the message you set for it on the server.
 *
 * Dragsim lets everyone write their own debug message - up to eight lines of text with
 * placeholders like [roll] and [result], in any order and wording. One player's roll
 * reads "Result Goop miss! Roll 27.86, target 0.16692% (1/599)!", another's
 * "[Debug] The Disturbed | Roll 27.86/100". The fixed patterns only ever read the
 * default, so the close-call filter let every roll through for anyone who had changed
 * theirs - Fear's log from 2026-10-30 has thousands of "Result ... Roll ..." lines and
 * not one of them was read.
 *
 * The message itself is the only thing that says where the roll sits, so it is taken
 * off the server's Custom Message page whenever that page is open, or from
 * /debug message as it is sent, kept in config/endsight/debug-message.txt, and turned
 * into a pattern: the text between placeholders must be there as written, and each
 * placeholder takes whatever the server put in its place.
 */
public final class DebugMessage {

    private DebugMessage() {
    }

    private static final String MENU = "Loot Debug";
    private static final String PAGE = "Custom Message";
    private static final String SETTINGS = "Settings";
    private static final String BOOK = "Custom Chat Message";

    private static final Pattern LINE_ITEM = Pattern.compile("^Line\\s+(\\d{1,2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[([a-z_]+)]");
    /** Colours never reach the plain chat text being matched, so they come out of the pattern too. */
    private static final Pattern CODES = Pattern.compile("(?i)[&§](?:#[0-9a-f]{6}|[0-9a-fk-or])");
    private static final Pattern COMMAND = Pattern.compile("^debug\\s+message\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** What an unused line's item might say where a message would be. */
    private static final Pattern UNSET = Pattern.compile("(?i)^\\(?(?:empty|unused|not set|none|blank)\\)?\\.?$");

    /**
     * One line of the message as a pattern, the placeholder behind each group, whether it
     * opens on one, and the message's own words in order.
     */
    record Compiled(Pattern pattern, List<String> fields, boolean leads, List<String> words) {
    }

    /**
     * Your debug message in chat: which line of it (-1 for the whole message at once),
     * the roll and the result it carried (either may be absent), and whether that is the
     * whole roll or one line of several still to come.
     */
    public record Read(int line, Double roll, String result, boolean complete) {
    }

    /** As set on the server, one entry a line; null until it has been read once. */
    private static List<String> lines;
    private static Compiled whole;
    private static List<Compiled> single = List.of();
    /** The chat prompt goes out once a game session, not on every join. */
    private static boolean told;

    public static void init() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.screen instanceof AbstractContainerScreen<?> screen && page(screen, PAGE)) learn(readPage(screen));
            // Five seconds in, so the line is not lost under the server's join messages.
            else if (!told && lines == null && LootFilter.on() && mc.player != null
                    && mc.getCurrentServer() != null && mc.player.tickCount > 100) tell(mc);
        });
        ClientSendMessageEvents.COMMAND.register(command -> learn(fromCommand(command)));
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> container)
                ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> hint(container, g));
        });
    }

    // ---------------------------------------------------------------- reading chat

    /** Your debug message, or one line of it, read off a chat message; null for anything else. */
    public static Read read(String text) {
        if (whole == null || text == null) return null;
        String plain = text.strip();
        if (plain.isEmpty()) return null;
        Read all = match(whole, -1, plain, true);
        if (all != null || single.size() < 2 || plain.indexOf('\n') >= 0) return all;
        // Several lines can also arrive as several messages, one line each.
        for (int i = 0; i < single.size(); i++) {
            Compiled c = single.get(i);
            Read part = c == null ? null : match(c, i, plain, false);
            if (part != null) return part;
        }
        return null;
    }

    private static Read match(Compiled c, int line, String plain, boolean complete) {
        // Every chat line comes through here, and most are not a roll. A message that opens
        // on several placeholders, "[mob] [result] [mf] [pl] [luck] Roll [roll]", is several
        // (.*?) in a row, and the matcher tries every way of splitting a line between them
        // before it fails: 650 ms for one ordinary 60-character line, timed on 2026-11-01,
        // and seconds for longer ones. A line without the message's own words in order can
        // never match, so it is turned away before the pattern runs.
        for (int at = 0, i = 0; i < c.words().size(); i++) {
            at = plain.indexOf(c.words().get(i), at);
            if (at < 0) return null;
            at += c.words().get(i).length();
        }
        Matcher m = c.pattern().matcher(plain);
        // A message that opens on a placeholder has nothing before it to rule out a
        // prefix, so someone pasting their own roll into chat would read as yours.
        if (!m.matches() || c.leads() && DragonTimer.isPlayerChat(plain)) return null;
        Double roll = null;
        String result = null;
        for (int g = 0; g < c.fields().size(); g++) {
            String value = m.group(g + 1);
            if (value == null) continue;
            if (roll == null && c.fields().get(g).equals("roll")) roll = number(value);
            else if (result == null && c.fields().get(g).equals("result")) result = value.strip();
        }
        return new Read(line, roll, result, complete || single.size() < 2);
    }

    /**
     * A rare roll that hit, or a table roll that gave something. The server writes a
     * miss as "miss", "nothing" or "<item> miss", a hit as "<item> HIT", and a drop as the
     * item alone - "Golden Eye", or the dragon's name on the roll that picks it. Without
     * [result] in the message there is no telling, so no.
     */
    public static boolean happened(String result) {
        if (result == null) return false;
        String r = result.strip().toLowerCase(Locale.ROOT);
        return !(r.isEmpty() || r.equals("miss") || r.equals("nothing") || r.equals("none") || r.endsWith(" miss"));
    }

    /** The highest line a several-line roll can end on; a roll is complete once it is in. */
    public static int lastLine() {
        for (int i = single.size() - 1; i >= 0; i--) if (single.get(i) != null) return i;
        return -1;
    }

    // ---------------------------------------------------------------- the message

    /** Installs a message: one pattern for all of it, and one per line that can stand alone. */
    static void set(List<String> next) {
        lines = List.copyOf(next);
        List<Compiled> parts = new ArrayList<>();
        for (String line : lines) parts.add(compile(line));
        single = parts;
        whole = lines.isEmpty() ? null : compile(String.join("\n", lines));
    }

    /**
     * One line of the message as a pattern, or null when it has too little text of its
     * own to be told apart from other chat: "[debug]" alone would match every line there
     * is. [roll] takes a number only, so the text after it can start straight away.
     */
    static Compiled compile(String template) {
        String text = CODES.matcher(template).replaceAll("").strip();
        StringBuilder regex = new StringBuilder("^");
        List<String> fields = new ArrayList<>(), words = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        int at = 0, own = 0;
        boolean leads = false;
        while (m.find()) {
            if (m.start() == 0) leads = true;
            own += literal(regex, words, text.substring(at, m.start()));
            fields.add(m.group(1));
            regex.append(m.group(1).equals("roll") ? "([\\d.,]+)(?:/100|%)?" : "(.*?)");
            at = m.end();
        }
        own += literal(regex, words, text.substring(at));
        if (own < 3) return null;
        return new Compiled(Pattern.compile(regex.append('$').toString()), List.copyOf(fields), leads, List.copyOf(words));
    }

    /**
     * Text the server prints as written. Spacing is loose because a placeholder with
     * nothing to say leaves two spaces where the message had one, and a line break
     * between two lines of the message counts as spacing too.
     */
    private static int literal(StringBuilder regex, List<String> found, String text) {
        int own = 0;
        String[] words = text.split("\\s+", -1);
        for (int i = 0; i < words.length; i++) {
            if (i > 0) regex.append("\\s*");
            if (words[i].isEmpty()) continue;
            regex.append(Pattern.quote(words[i]));
            found.add(words[i]);
            own += words[i].length();
        }
        return own;
    }

    /** "/debug message <message>" as sent: the message, a line per "\n" in it. Anything else is null. */
    static List<String> fromCommand(String command) {
        Matcher m = COMMAND.matcher(command == null ? "" : command.strip());
        if (!m.matches()) return null;
        List<String> out = Arrays.stream(m.group(1).split("\\\\n|\n")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        return out.isEmpty() ? null : out;
    }

    /**
     * The message on one line's item: its first lore line, exactly as typed, codes and
     * placeholders and all. The click hint under it, or whatever an unused line shows
     * instead, is not a message.
     */
    static String template(List<String> lore) {
        for (String raw : lore) {
            String s = raw.strip();
            if (s.isEmpty()) continue;
            return s.startsWith("Click") || UNSET.matcher(s).matches() ? null : s;
        }
        return null;
    }

    /**
     * In chat, while the loot filter is on and the message has never been read: one click
     * loads it. /debug message with nothing after it opens the Custom Message page, and
     * that page being open is all the reading needs.
     */
    private static void tell(Minecraft mc) {
        told = true;
        mc.player.sendSystemMessage(Component.literal("")
                .append(Component.literal("[Endsight] ").withStyle(ChatFormatting.DARK_PURPLE))
                .append(Component.literal("Your debug message isn't loaded, so loot rolls can't be filtered. ")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal("Load it").withStyle(s -> s
                        .withColor(ChatFormatting.YELLOW).withUnderlined(true)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("/debug message")))
                        .withClickEvent(new ClickEvent.RunCommand("/debug message")))));
    }

    private static void learn(List<String> next) {
        if (next == null || next.equals(lines)) return;
        set(next);
        save();
        Toast.changed("Debug message", next.isEmpty() ? "Default" : "Loaded");
    }

    // ---------------------------------------------------------------- the server's pages

    /** The lines on the Custom Message page, in order; null until its items have arrived. */
    private static List<String> readPage(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        TreeMap<Integer, String> found = new TreeMap<>();
        boolean any = false;
        for (Slot slot : screen.getMenu().slots) {
            if (mc.player != null && slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            Matcher m = LINE_ITEM.matcher(Zealots.strip(stack.getHoverName().getString()).strip());
            if (!m.matches()) continue;
            any = true;
            var lore = stack.get(DataComponents.LORE);
            String text = lore == null ? null
                    : template(lore.lines().stream().map(c -> Zealots.strip(c.getString())).toList());
            if (text != null) found.put(Integer.parseInt(m.group(1)), text);
        }
        return any ? List.copyOf(found.values()) : null;
    }

    private static boolean page(AbstractContainerScreen<?> screen, String which) {
        String title = Zealots.strip(screen.getTitle().getString());
        return title.contains(MENU) && title.contains(which);
    }

    private static boolean book(ItemStack stack) {
        return !stack.isEmpty() && Zealots.strip(stack.getHoverName().getString()).strip().equals(BOOK);
    }

    /**
     * Whether this screen is the settings page, asked once per screen. This runs for every
     * slot of every open menu each frame, and for anyone who never loads a message it never
     * stops: checking the item's name first read every item in every chest, every frame.
     * A menu's title does not change while it is open.
     */
    private static Object pageScreen;
    private static boolean onSettings;

    /** On the settings page, until the message has been read: the book to open, ringed. */
    public static void overSlot(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Slot slot) {
        if (lines != null) return;
        if (screen != pageScreen) {
            pageScreen = screen;
            onSettings = page(screen, SETTINGS);
        }
        if (!onSettings || !book(slot.getItem())) return;
        Draw.rect(g, slot.x, slot.y, 16, 16, Draw.alpha(Theme.accent(), 0.3f));
        Draw.roundedRing(g, slot.x - 2, slot.y - 2, 20, 20, 4, Theme.accent());
    }

    /** And why, above the window - below it when there is no room above. */
    private static void hint(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (lines != null || !page(screen, SETTINGS)) return;
        if (screen.getMenu().slots.stream().noneMatch(s -> book(s.getItem()))) return;
        var font = Minecraft.getInstance().font;
        int y = screen.topPos >= 14 ? screen.topPos - 12 : screen.topPos + screen.imageHeight + 4;
        Draw.textCentered(g, font, "Open Custom Chat Message to load your debug lines",
                screen.leftPos + screen.imageWidth / 2, y, Theme.accent());
    }

    // ---------------------------------------------------------------- disk

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("debug-message.txt");
    }

    private static void load() {
        try {
            if (Files.exists(file())) set(Files.readAllLines(file()).stream().filter(s -> !s.isBlank()).toList());
        } catch (IOException | RuntimeException e) {
            System.err.println("[Endsight] could not read the debug message: " + e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), lines);
        } catch (IOException e) {
            System.err.println("[Endsight] could not write the debug message: " + e);
        }
    }

    private static Double number(String s) {
        try {
            return Double.valueOf(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
