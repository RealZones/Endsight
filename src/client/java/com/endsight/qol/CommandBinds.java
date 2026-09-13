package com.endsight.qol;

import com.endsight.ui.Keybinds;
import com.endsight.ui.Module;
import com.endsight.ui.Setting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Keys that type a command for you.
 *
 * A list you grow yourself: New bind adds a row, you type the command in it and give
 * the row a key, and the key sends the command. "/warp end" on F6, say. A row without
 * a slash is sent as chat, which is occasionally what you want.
 *
 * The list is the settings page: the module holds a live list, New bind appends to it
 * and the page redraws from it, so there is no separate screen for managing binds.
 */
public final class CommandBinds {

    private CommandBinds() {
    }

    private static boolean enabled = true;
    /** Live: the settings page reads this list every frame, so a new row shows at once. */
    private static final List<Setting> settings = new ArrayList<>();
    private static final List<Setting.Command> binds = new ArrayList<>();
    private static int nextId;

    public static Module module() {
        settings.add(new Setting.Action("New bind",
                "Add a row: type the command, then click its key chip and press a key.",
                "New", CommandBinds::add));
        return new Module("qol.commands", "Command Binds",
                "Keys that send a command for you.", "Quality of Life",
                () -> enabled, v -> enabled = v, settings);
    }

    private static Setting.Command add() {
        return add("");
    }

    private static Setting.Command add(String command) {
        String id = "cmd." + nextId++;
        Setting.Command row = new Setting.Command(id, command, () -> remove(id));
        binds.add(row);
        settings.add(row);
        return row;
    }

    private static void remove(String id) {
        binds.removeIf(b -> b.id().equals(id));
        settings.removeIf(s -> s instanceof Setting.Command c && c.id().equals(id));
        Keybinds.clear(id);
    }

    /** Every bind's id, for the key poller. */
    public static List<Setting.Command> all() {
        return List.copyOf(binds);
    }

    /** A bound key was pressed: send that row's command. */
    public static void fire(Setting.Command bind) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        String text = bind.command().trim();
        if (text.isEmpty() || mc.player == null || mc.getConnection() == null) return;
        if (text.startsWith("/")) mc.getConnection().sendCommand(text.substring(1));
        else mc.getConnection().sendChat(text);
    }

    // ── config ────────────────────────────────────────────────────────────────

    public static void save(Properties p) {
        for (int i = 0; i < binds.size(); i++) {
            p.setProperty("command." + binds.get(i).id(), binds.get(i).command());
        }
    }

    public static void load(Properties p) {
        List<String> ids = new ArrayList<>();
        for (String name : p.stringPropertyNames()) if (name.startsWith("command.cmd.")) ids.add(name);
        ids.sort(null);
        for (String name : ids) {
            // Rows keep the ids they were saved under, so their keys (bind.cmd.N) still match.
            String id = name.substring("command.".length());
            int n = Integer.parseInt(id.substring(4));
            nextId = Math.max(nextId, n + 1);
            Setting.Command row = new Setting.Command(id, p.getProperty(name), () -> remove(id));
            binds.add(row);
            settings.add(row);
        }
    }
}
