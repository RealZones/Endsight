package com.endsight.profile;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Reproduces the server's integer /pv argument in the merged client chat tree. */
public final class ProfileCommandChecks {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var dispatcher = new CommandDispatcher<String>();
        dispatcher.register(LiteralArgumentBuilder.<String>literal("pv")
                .then(RequiredArgumentBuilder.<String,Integer>argument("player", IntegerArgumentType.integer())
                        .executes(c -> { throw new AssertionError("Server command selected for a username"); })));
        List<String> opened = new ArrayList<>(), forwarded = new ArrayList<>();
        ProfileCommands.register(dispatcher, source -> source,
                source -> List.of("Secrets_", "Secreta", "secrets_", "Imfear", "Steve", "invalid name"), opened::add, forwarded::add);
        var parsed = dispatcher.parse("pv secrets_", "Self");
        require(!parsed.getReader().canRead(), "The username must parse completely despite the server integer argument");
        dispatcher.execute(parsed);
        require(opened.equals(List.of("secrets_")), "A username must open the profile viewer");
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("pv s", "Self")).join().getList();
        require(suggestions.stream().map(s -> s.getText()).toList().equals(List.of("Secreta", "Secrets_", "Steve")),
                "Suggestions start at the first letter, filter case-insensitively, dedupe and sort");
        var alias = dispatcher.getCompletionSuggestions(dispatcher.parse("esprofile IM", "Self")).join().getList();
        require(alias.size() == 1 && alias.getFirst().getText().equals("Imfear"), "Alias shares autocomplete");
        dispatcher.execute("esprofile", "Self");
        require(opened.getLast().equals("Self"), "No argument opens the current player");
        var server = new CommandDispatcher<String>();
        var vault = server.register(LiteralArgumentBuilder.<String>literal("vault")
                .then(RequiredArgumentBuilder.<String,Integer>argument("page", IntegerArgumentType.integer())));
        server.register(LiteralArgumentBuilder.<String>literal("pv").redirect(vault));
        var filtered = ProfileCommands.withoutServerAliases(server.getRoot());
        require(filtered.getChild("vault") == vault, "Other server commands retain their original branches");
        require(filtered.getChild("pv") == null && server.getRoot().getChild("pv") != null,
                "Filter reserved aliases without mutating the received server tree");
        var redirected = new CommandDispatcher<>(filtered);
        ProfileCommands.register(redirected, source -> source, source -> List.of("Secrets_"), opened::add, forwarded::add);
        redirected.execute("pv Secrets_", "Self");
        require(opened.getLast().equals("Secrets_"), "A server redirect cannot capture profile usernames");
        // Dragsim's vaults: /pv 1 to /pv 99 belong to the server (the game drops the server's own
        // /pv tree, as here); a three-digit name is a player.
        redirected.execute("pv 1", "Self"); redirected.execute("pv 10", "Self"); redirected.execute("pv 100", "Self");
        require(forwarded.equals(List.of("pv 1", "pv 10")), "A one- or two-digit /pv must go to the server's vaults");
        require(opened.getLast().equals("100"), "Three digits is a valid username and opens a profile");
        var temp = Files.createTempDirectory("endsight-profile-names");
        var file = temp.resolve("profile-players.txt");
        try {
            var names = new ProfileNames(file);
            names.include("Secrets_");
            names.remember("Secrets_"); names.remember("not a player");
            var restored = new ProfileNames(file);
            require(restored.withOnline(List.of("Imfear")).containsAll(List.of("Secrets_", "Imfear")), "Known profiles survive restarting");
            require(!Files.readString(file).contains("not a player"), "Invalid names never enter persistent suggestions");
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(temp); }
        System.out.println("Profile command collision, first-letter suggestions, alias and name-history checks passed");
    }
}
