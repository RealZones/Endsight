package com.endsight.dungeon;

/** Compact controls must map to the original slot without clickable gaps or hidden inventory slots. */
public final class TerminalUiChecks {
    public static void main(String[] args) throws Exception {
        int checks = 0;
        for (TerminalSolver.Frame f : java.util.List.of(
                new TerminalSolver.Frame(50, 80, 7, 2, 1, 1),
                new TerminalSolver.Frame(50, 80, 3, 3, 3, 1),
                new TerminalSolver.Frame(50, 80, 7, 4, 1, 0))) {
            for (int r = 0; r < f.rows(); r++) for (int c = 0; c < f.columns(); c++) {
                int expected = (r + f.firstRow()) * 9 + c + f.firstColumn();
                int x = f.x() + c * 24, y = f.y() + r * 24;
                if (f.slot(x + .1, y + .1) != expected || f.slot(x + 21.9, y + 21.9) != expected
                        || f.sx(expected) != x || f.sy(expected) != y) throw new AssertionError("Misaligned compact slot " + expected);
                if (f.slot(x + 22, y + 10) != -1 || f.slot(x + 10, y + 22) != -1)
                    throw new AssertionError("Slot gap accepted");
                checks += 2;
            }
            if (f.slot(f.x() - .1, f.y()) != -1 || f.slot(f.x(), f.y() - .1) != -1
                    || f.slot(f.x() + f.columns() * 24, f.y()) != -1
                    || f.slot(f.x(), f.y() + f.rows() * 24) != -1) throw new AssertionError("Outside grid accepted");
            checks++;
        }
        // A new terminal screen may arrive before its slots. Compact owns even
        // that first frame so the underlying chest cannot flash into view.
        var mode = TerminalSolver.class.getDeclaredField("layout");
        mode.setAccessible(true);
        var shown = TerminalSolver.class.getDeclaredMethod("compactShown", TerminalLogic.Plan.class);
        shown.setAccessible(true);
        var ready = new TerminalLogic.Plan(TerminalLogic.Type.RUBIX, java.util.List.of(), "Ready", true);
        var waiting = new TerminalLogic.Plan(TerminalLogic.Type.RUBIX, java.util.List.of(), "Waiting for server", false);
        mode.set(null, "Compact");
        if (!(boolean) shown.invoke(null, ready) || !(boolean) shown.invoke(null, waiting))
            throw new AssertionError("Compact menu flashed before or during a partial click update");
        checks += 2;
        mode.set(null, "Normal chest");
        if ((boolean) shown.invoke(null, ready)) throw new AssertionError("Normal chest layout stayed suppressed");
        checks++;
        mode.set(null, "Compact");
        System.out.println(checks + " terminal UI slot checks passed");
    }
}
