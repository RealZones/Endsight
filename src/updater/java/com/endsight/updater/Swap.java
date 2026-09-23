package com.endsight.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * The second process that installs a downloaded update: started as the game closes,
 * it waits for the game to be gone, then moves the old jar out and the new one in.
 * Plain Java and nothing else - it runs with only this jar on its classpath.
 *
 * Old out first, and only then new in. If the old jar will not move, nothing else
 * happens: two Endsight jars in mods/ is a Fabric startup failure, one old one is just
 * an update for next time. If the new one then fails to go in, the old one goes back.
 * The old jar is kept as previous.jar, the rollback.
 *
 * Arguments: game pid, the installed jar, the downloaded jar, where to keep the old one.
 */
public final class Swap {

    private Swap() {
    }

    public static void main(String[] a) throws Exception {
        long pid = Long.parseLong(a[0]);
        Path old = Path.of(a[1]), fresh = Path.of(a[2]), keep = Path.of(a[3]);
        Path log = keep.resolveSibling("swap.log");
        long giveUp = System.currentTimeMillis() + 120_000;
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.currentTimeMillis() > giveUp) {
                log(log, "the game did not close within two minutes; nothing moved");
                result(log, "E6");
                return;
            }
            Thread.sleep(100);
        }
        // Windows lets go of a closed program's files a moment after it exits.
        boolean out = false;
        for (int i = 0; i < 100 && !out; i++) {
            try {
                Files.move(old, keep, StandardCopyOption.REPLACE_EXISTING);
                out = true;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        if (!out) {
            log(log, "could not move " + old.getFileName() + "; nothing moved");
            result(log, "E4");
            return;
        }
        Path target = old.resolveSibling(fresh.getFileName());
        try {
            Files.move(fresh, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(keep.resolveSibling("ready.txt"));
            log(log, "installed " + target.getFileName() + ", kept " + old.getFileName() + " as " + keep.getFileName());
            result(log, "OK");
        } catch (IOException e) {
            Files.move(keep, old, StandardCopyOption.REPLACE_EXISTING);
            log(log, "could not install " + fresh.getFileName() + ", put " + old.getFileName() + " back: " + e);
            result(log, "E5");
        }
    }

    /** The outcome as a code, read on the next launch and said in game. */
    private static void result(Path log, String code) {
        try {
            Files.writeString(log.resolveSibling("swap.txt"), code);
        } catch (IOException ignored) {
        }
    }

    private static void log(Path log, String line) {
        try {
            Files.writeString(log, LocalDateTime.now().withNano(0) + "  " + line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
