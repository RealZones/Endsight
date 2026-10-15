package com.endsight.dungeon;

import com.endsight.dragons.DragonTimer;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Quiz puzzle: three questions, a button under each answer.
 *
 * The public jar includes answers confirmed in the current server, and a player's
 * later discoveries or corrections override them in a local file. It reads the
 * question and options from the room instead of trusting old A/B/C positions. A
 * press is remembered but not believed until the server confirms it was right.
 */
public final class Quiz {
    private static final String ID = "dungeon.quiz";
    private static final String ENTRY = "PUZZLE! Quiz";

    private static final QuizLogic.Book BOOK = new QuizLogic.Book();
    private static final QuizLogic.Pending PENDING = new QuizLogic.Pending();

    private static boolean enabled = true, announce = true;
    private static boolean highlight = true, tracer = true, throughWalls = true;
    private static Vec3 mark;            // where the answer's label stands
    private static final List<BlockPos> marked = new ArrayList<>();
    private static boolean active, loaded;
    private static ClientLevel world;
    private static QuizLogic.Question current;
    private static List<String> options = List.of();
    private static String answer;              // the stored answer for `current`, or null
    private static String status = "Waiting for a question";
    private static String announced;   // the question whose answer we have already printed
    private static int learned;
    private static final Set<BlockPos> pressed = new HashSet<>();
    private static List<BlockPos> clickers = List.of();
    private static BlockPos clickerCenter, markCenter;
    private static int clickerTicks, markTicks;

    /**
     * The question sign changes the instant an answer lands; the three answer signs are
     * rewritten a moment later, and the letters move between questions. Reading them on
     * the first tick of a new question printed "2/3 - B Goldor" while the room was still
     * showing the last question's letters, and Goldor ended up at C (log, 2026-10-14
     * 15:30:57: the hint landed in the same second as "answered correctly!"). So nothing
     * is said or marked until the signs have held still for this long.
     */
    private static final int SETTLE_TICKS = 15;
    private static List<String> lastLabels = List.of();
    private static int settled;

    private Quiz() { }

    public static Module module() {
        return new Module(ID, "Quiz", "Remembers quiz answers as you learn them, and says the answer in chat.",
                "Dungeons", () -> enabled, value -> { enabled = value; if (!value) reset(); }, List.of(
                new Setting.Toggle("Say the answer", "Print the stored answer in chat when a question appears.",
                        () -> announce, value -> announce = value),
                new Setting.Toggle("Highlight the buttons", "Outline the buttons under the known answer.",
                        () -> highlight, value -> highlight = value),
                new Setting.Toggle("Tracer", "Draw a line to the answer you want.",
                        () -> tracer, value -> tracer = value),
                new Setting.Toggle("Through walls", "Keep the outline and tracer visible through blocks.",
                        () -> throughWalls, value -> throughWalls = value)));
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { save(); reset(); });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !enabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || !DungeonContext.active()) return;
            String line = plain(message.getString());
            if (DragonTimer.isPlayerChat(line)) return;
            if (entry(line)) {
                reset();
                active = true;
                world = mc.level;
                status = "Waiting for a question";
            } else if (correct(mc, line)) {
                // The server's own per-question verdict, which beats inferring one from
                // the counter moving: it is immediate, and it is unambiguous about which
                // answer it refers to. Restricted to this player's name because in a
                // party somebody else answering would otherwise teach us their answer
                // as if it were ours.
                if (PENDING.has() && BOOK.learn(PENDING.question(), PENDING.answer())) {
                    learned++;
                    save();
                }
                PENDING.clear();
            } else if (wrong(mc, line)) {
                // A failure is still information: cross that option off. Three options
                // and two crossings name the third.
                if (PENDING.has()) {
                    BOOK.exclude(PENDING.question(), PENDING.answer());
                    save();
                }
                PENDING.clear();
            } else if (ends(line)) {
                // The room is over. A pending answer on the LAST question is only
                // trustworthy if the puzzle was solved - a fail tells us the opposite.
                if (line.startsWith("PUZZLE SOLVED!") && PENDING.has()) {
                    if (BOOK.learn(PENDING.question(), PENDING.answer())) learned++;
                }
                save();
                reset();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(Quiz::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw());
    }

    static String plain(String raw) {
        return raw == null ? "" : raw.replaceAll("§.", "").trim().replace('’', '\'').replace('–', '-');
    }

    /** Match the puzzle name only - the sentence after it is flavour text. */
    static boolean entry(String line) {
        String text = plain(line);
        return text.equals(ENTRY) || text.startsWith(ENTRY + " -") || text.startsWith(ENTRY + ":");
    }

    /** "<you> answered correctly!" - and only you. */
    static boolean correct(Minecraft mc, String line) {
        if (mc.player == null) return false;
        String text = plain(line);
        return text.equals(mc.player.getName().getString() + " answered correctly!");
    }

    /** "PUZZLE FAIL! <you> answered a quiz question wrong" - and only you. */
    static boolean wrong(Minecraft mc, String line) {
        if (mc.player == null) return false;
        String text = plain(line);
        return text.startsWith("PUZZLE FAIL!") && text.contains(mc.player.getName().getString())
                && text.contains("answered a quiz question wrong");
    }

    static boolean ends(String line) {
        String text = plain(line);
        return text.startsWith("PUZZLE SOLVED! Quiz") || text.startsWith("PUZZLE FAIL! Quiz")
                || text.startsWith("PUZZLE! ") && !entry(text);
    }

    private static boolean visible(Minecraft mc) {
        return enabled && active && mc.player != null && mc.level == world && DungeonContext.active();
    }

    private static void reset() {
        active = false; world = null; current = null; answer = null;
        options = List.of(); status = "Waiting for a question";
        PENDING.clear(); pressed.clear(); announced = null; mark = null; marked.clear();
        clickers = List.of(); clickerCenter = null; markCenter = null; clickerTicks = 0; markTicks = 0;
        lastLabels = List.of(); settled = 0;
    }

    private static void tick(Minecraft mc) {
        if (!enabled) return;
        if (!loaded) { BOOK.load(file()); loaded = true; }
        if (!visible(mc)) { if (active) reset(); return; }

        QuizLogic.Question seen = questionNear(mc);
        if (seen == null) { mark = null; marked.clear(); return; }

        // A new question means the previous one was answered correctly - the room
        // would have ended otherwise - so the held answer can be written down now.
        if (PENDING.confirm(BOOK, seen)) { learned++; save(); }

        if (!seen.equals(current)) { clickerTicks = 0; markTicks = 0; settled = 0; }
        current = seen;
        List<String> labels = labelsNear(mc);
        if (labels.equals(lastLabels)) settled++;
        else { lastLabels = labels; settled = 0; }
        options = labels.stream().map(QuizLogic::option).distinct().toList();
        boolean ready = settled >= SETTLE_TICKS;

        // Presses are tracked every tick so a button going up and down is never missed,
        // but one only counts as an answer once the signs say what it was under.
        notePresses(mc, ready);
        if (!ready) { mark = null; marked.clear(); markCenter = null; return; }

        String stored = BOOK.deduce(seen.text(), options);
        answer = QuizLogic.resolve(stored, options);
        // An answer saved with its old slot letter is rewritten bare the first time the
        // room shows it, so the book heals itself instead of needing a hand edit.
        if (answer != null && !answer.equals(stored) && BOOK.answer(seen.text()) != null
                && BOOK.learn(seen.text(), answer)) save();
        status = answer != null ? "Known" : "Learning";
        aim(mc);
        say(mc, seen);
    }

    /** The question hologram: the nearest text that reads as "n/m <question>". */
    private static QuizLogic.Question questionNear(Minecraft mc) {
        QuizLogic.Question best = null;
        double nearest = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player || entity.distanceToSqr(mc.player) > 30 * 30) continue;
            QuizLogic.Question q = QuizLogic.question(label(entity));
            if (q == null) continue;
            double distance = entity.distanceToSqr(mc.player);
            if (distance < nearest) { nearest = distance; best = q; }
        }
        return best;
    }

    /**
     * The answer labels standing around the room.
     *
     * Anything short, nearby and not a question: the options are names rather than
     * sentences, so a length cap separates them from the hologram and from mob tags
     * without needing to know what the answers can be.
     */
    private static List<String> labelsNear(Minecraft mc) {
        List<String> found = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player || entity.distanceToSqr(mc.player) > 20 * 20) continue;
            String text = plain(label(entity));
            if (text.isEmpty() || text.length() > 24 || QuizLogic.question(text) != null) continue;
            if (text.equalsIgnoreCase("Quiz") || text.contains("/")) continue;
            if (!QuizLogic.option(text).isEmpty()) found.add(text);   // whole, letter and all
        }
        found.sort(null);                       // entity order is not stable between ticks
        return List.copyOf(found);
    }

    /** The nearby sign showing this answer now, e.g. "C Goldor", or null. */
    private static Entity answerLabel(Minecraft mc, String bare) {
        Entity best = null;
        double nearest = 20 * 20;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player || !bare.equals(QuizLogic.option(plain(label(entity))))) continue;
            double distance = entity.distanceToSqr(mc.player);
            if (distance < nearest) { nearest = distance; best = entity; }
        }
        return best;
    }

    private static String label(Entity entity) {
        var text = entity instanceof Display.TextDisplay display ? display.getText() : entity.getCustomName();
        return text == null ? "" : text.getString();
    }

    /**
     * Watch for a button being pressed and hold the option above it as the answer.
     *
     * Only the transition into powered counts; a button stays powered for a moment and
     * would otherwise be recorded every tick it is held down. The option taken is the
     * nearest label to the button, which is what "press the button under the right
     * answer" means geometrically.
     */
    private static void notePresses(Minecraft mc, boolean ready) {
        if (current == null || options.isEmpty()) return;
        BlockPos centre = mc.player.blockPosition();
        // Only the clickers can change power. Discover their positions periodically,
        // then read those few blocks every tick so a short press is still observed.
        // The previous full 4,851-block sweep ran twenty times a second per question.
        if (--clickerTicks <= 0 || clickerCenter == null
                || Math.abs(centre.getX() - clickerCenter.getX()) > 4
                || Math.abs(centre.getY() - clickerCenter.getY()) > 2
                || Math.abs(centre.getZ() - clickerCenter.getZ()) > 4) {
            clickerTicks = 10;
            clickerCenter = centre;
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-10, -5, -10), centre.offset(10, 5, 10))) {
                if (!mc.level.hasChunkAt(pos)) continue;
                BlockState state = mc.level.getBlockState(pos);
                if ((state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof LeverBlock)
                        && state.hasProperty(BlockStateProperties.POWERED)) found.add(pos.immutable());
            }
            clickers = List.copyOf(found);
            pressed.retainAll(clickers);
        }
        for (BlockPos pos : clickers) {
            if (Math.abs(pos.getX() - centre.getX()) > 10 || Math.abs(pos.getY() - centre.getY()) > 5
                    || Math.abs(pos.getZ() - centre.getZ()) > 10) continue;
            if (!mc.level.hasChunkAt(pos)) continue;
            BlockState state = mc.level.getBlockState(pos);
            boolean clicker = state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof LeverBlock;
            if (!clicker || !state.hasProperty(BlockStateProperties.POWERED)) { pressed.remove(pos); continue; }
            BlockPos at = pos;
            if (!state.getValue(BlockStateProperties.POWERED)) { pressed.remove(at); continue; }
            if (!pressed.add(at)) continue;                  // already counted this press
            if (!ready) continue;
            String option = nearestOption(mc, Vec3.atCenterOf(at));
            if (option != null) PENDING.offer(current.text(), option);
        }
    }

    /**
     * The label the answer is wearing this question, e.g. "C Goldor", for the chat line.
     * Only a nearby sign counts: this used to take the first match anywhere in the level.
     */
    private static String withSlot(Minecraft mc, String bare) {
        Entity sign = answerLabel(mc, bare);
        return sign == null ? bare : plain(label(sign));
    }

    private static String nearestOption(Minecraft mc, Vec3 button) {
        String best = null;
        double nearest = 6 * 6;                              // a label further than this is not "above" it
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player) continue;
            String text = plain(label(entity));
            if (text.isEmpty() || !options.contains(QuizLogic.option(text))) continue;
            double distance = entity.position().distanceToSqr(button);
            if (distance < nearest) { nearest = distance; best = QuizLogic.option(text); }
        }
        return best;
    }

    /**
     * One chat line per question, when the question changes.
     *
     * Guarded on the question rather than a timer: the hologram is read every tick,
     * and without this the same line would scroll the chat away for the whole room.
     */
    private static void say(Minecraft mc, QuizLogic.Question q) {
        if (!announce || mc.player == null) return;
        String shown = answer == null ? "" : withSlot(mc, answer);
        // Keyed on the letter too: if the signs ever move mid-question, the corrected
        // letter is worth a second line more than a wrong one is worth silence.
        String key = QuizLogic.normalize(q.text()) + "|" + shown;
        if (key.equals(announced)) return;
        announced = key;
        String head = "§5Quiz §8" + q.index() + "/" + q.total() + " §7- ";
        if (answer == null) {
            // Bare names: an old book stored "A The Watcher", and that letter is long gone.
            var out = BOOK.excluded(q.text()).stream().map(QuizLogic::option).distinct().toList();
            mc.player.sendSystemMessage(Component.literal(head + (out.isEmpty()
                    ? "§enot seen before, I will remember your answer"
                    : "§enot " + String.join("§7, §e", out) + "§7, I will remember your answer")));
        } else {
            boolean deduced = BOOK.answer(q.text()) == null;
            mc.player.sendSystemMessage(Component.literal(head + "§a" + shown
                    + (deduced ? " §8(by elimination)" : "")));
        }
    }

    /**
     * Where to point: the one button to press, on the front of the answer's pillar.
     *
     * A recording of the room shows three option labels, each one block above the
     * centre of a pillar with a button on all four sides - press any of them. Marking
     * all four lit the side faces too and read as clutter, so only the front one is
     * marked and the tracer ends on it rather than on the sign above. The front comes
     * from where the three signs stand ({@link QuizLogic#front}); if they cannot say, the
     * button facing the player is the one you would reach for anyway.
     */
    private static void aim(Minecraft mc) {
        mark = null;
        if (answer == null) { marked.clear(); markCenter = null; return; }
        Entity label = answerLabel(mc, answer);
        if (label == null) { marked.clear(); markCenter = null; return; }
        BlockPos centre = label.blockPosition();
        if (!centre.equals(markCenter) || --markTicks <= 0) {
            markCenter = centre;
            markTicks = 10;
            marked.clear();
            BlockPos button = frontButton(mc, centre);
            if (button != null) marked.add(button);
        }
        mark = marked.isEmpty() ? label.position() : buttonCentre(mc, marked.getFirst());
    }

    private static BlockPos frontButton(Minecraft mc, BlockPos sign) {
        int[] front = roomFront(mc);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BlockPos pos : BlockPos.betweenClosed(sign.offset(-2, -3, -2), sign.offset(2, 1, 2))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            BlockState state = mc.level.getBlockState(pos);
            if (!(state.getBlock() instanceof ButtonBlock)) continue;
            double score = 0;
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                double tx = mc.player.getX() - (pos.getX() + .5), tz = mc.player.getZ() - (pos.getZ() + .5);
                double length = Math.max(1e-6, Math.hypot(tx, tz));
                // The room's front decides; facing the player only breaks a tie.
                if (front != null) score += 10 * (facing.getStepX() * front[0] + facing.getStepZ() * front[1]);
                score += (facing.getStepX() * tx + facing.getStepZ() * tz) / length;
            }
            if (score > bestScore) { bestScore = score; best = pos.immutable(); }
        }
        return best;
    }

    /** The front of the answer pillars, read from the three signs standing on them. */
    private static int[] roomFront(Minecraft mc) {
        List<double[]> signs = new ArrayList<>();
        for (String option : options) {
            Entity sign = answerLabel(mc, option);
            if (sign != null) signs.add(new double[]{sign.getX(), sign.getZ()});
        }
        return QuizLogic.front(signs);
    }

    private static Vec3 buttonCentre(Minecraft mc, BlockPos pos) {
        var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        return shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().move(pos).getCenter();
    }

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc) || mark == null) return;
        if (highlight) {
            for (BlockPos pos : marked) {
                var state = mc.level.getBlockState(pos);
                if (!(state.getBlock() instanceof ButtonBlock)) continue;
                var shape = state.getShape(mc.level, pos);
                if (shape.isEmpty()) continue;
                var outline = Gizmos.cuboid(shape.bounds().move(pos).inflate(.015),
                        GizmoStyle.strokeAndFill(0xFF55FF55, 2.5f, 0x6055FF55));
                if (throughWalls) outline.setAlwaysOnTop();
            }
        }
        if (tracer) {
            // Same shape as the Revenant tile tracer: start a little ahead of and below
            // the camera so the line reads as coming from the player, not the eye.
            var camera = mc.gameRenderer.getMainCamera();
            Vec3 eye = camera.position();
            var forward = camera.forwardVector();
            Vec3 from = eye.add(forward.x() * .8, forward.y() * .8 - .45, forward.z() * .8);
            var wide = Gizmos.line(from, mark, 0x3055FF55, 6f);
            var thin = Gizmos.line(from, mark, 0xCC55FF55, 1.8f);
            if (throughWalls) { wide.setAlwaysOnTop(); thin.setAlwaysOnTop(); }
        }
    }

    private static void save() {
        BOOK.save();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("endsight").resolve("quiz-answers.txt");
    }

    /** How many answers the book holds, for the module description and checks. */
    public static int known() {
        return BOOK.size();
    }

    public static int learnedThisSession() {
        return learned;
    }

    static String normalizedFor(String question) {
        return QuizLogic.normalize(question == null ? "" : question.toLowerCase(Locale.ROOT));
    }
}
