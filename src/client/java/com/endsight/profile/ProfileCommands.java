package com.endsight.profile;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared command grammar, also checked against a server's conflicting /pv tree. */
public final class ProfileCommands {
    private static final String PLAYER = "endsightProfilePlayer";
    private ProfileCommands() { }

    public static <S> RootCommandNode<S> withoutServerAliases(RootCommandNode<S> root) {
        RootCommandNode<S> result = new RootCommandNode<>();
        for (var child : root.getChildren()) {
            if (!child.getName().equals("pv") && !child.getName().equals("esprofile")) result.addChild(child);
        }
        return result;
    }

    public static <S> void register(CommandDispatcher<S> dispatcher, Function<S,String> self,
                                   Function<S,Collection<String>> players, Consumer<String> open) {
        for (String name : List.of("pv", "esprofile")) {
            dispatcher.register(LiteralArgumentBuilder.<S>literal(name)
                    .executes(c -> { open.accept(self.apply(c.getSource())); return 1; })
                    // Brigadier merges children by argument NAME, keeping the old type.
                    // "player" collided with DragSim's integer argument in chat parsing.
                    .then(RequiredArgumentBuilder.<S,String>argument(PLAYER, StringArgumentType.word())
                            .suggests((c, builder) -> {
                                String prefix = builder.getRemainingLowerCase();
                                TreeMap<String,String> matches = new TreeMap<>();
                                for (String player : players.apply(c.getSource())) {
                                    if (player != null && player.matches("[A-Za-z0-9_]{1,16}")) {
                                        String lower = player.toLowerCase(Locale.ROOT);
                                        if (lower.startsWith(prefix)) matches.putIfAbsent(lower, player);
                                    }
                                }
                                matches.values().forEach(builder::suggest);
                                return builder.buildFuture();
                            })
                            .executes(c -> { open.accept(StringArgumentType.getString(c, PLAYER)); return 1; })));
        }
    }
}
