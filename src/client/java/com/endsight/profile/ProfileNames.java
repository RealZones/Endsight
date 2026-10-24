package com.endsight.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;

/** A bounded list of known players; typing never makes a network request. */
public final class ProfileNames {
    private static final int LIMIT = 512;
    private final Path file;
    private final LinkedHashMap<String,String> names = new LinkedHashMap<>();

    public ProfileNames(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) { lines.limit(LIMIT).forEach(this::include); }
            catch (IOException ignored) { }
        }
    }

    public synchronized void include(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return;
        String key = name.toLowerCase(Locale.ROOT);
        names.remove(key); names.put(key, name);
        while (names.size() > LIMIT) names.remove(names.keySet().iterator().next());
    }

    public synchronized void remember(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return;
        include(name);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, names.values(), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    public synchronized Collection<String> withOnline(Collection<String> online) {
        ArrayList<String> result = new ArrayList<>(online);
        result.addAll(names.values());
        return result;
    }
}
