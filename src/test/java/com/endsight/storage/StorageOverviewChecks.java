package com.endsight.storage;

/** Geometry checks for the page index at common Minecraft GUI sizes. */
public final class StorageOverviewChecks {
    private static int checks;

    public static void main(String[] args) {
        verify(539, 498, 7, 2, 3);
        verify(320, 240, 7, 1, 1);
        verify(960, 540, 7, 4, 3);
        System.out.println(checks + " storage overview checks passed");
    }

    private static void verify(int width, int height, int pages, int cols, int visibleRows) {
        StorageOverview.Layout l = StorageOverview.layout(width, height, pages);
        equal(l.cols(), cols);
        equal(l.visibleRows(), visibleRows);
        equal(l.totalRows(), (pages + cols - 1) / cols);
        for (int col = 0; col < cols; col++) {
            int x = l.x(col);
            check(x >= 0 && x + StorageOverview.CARD_W <= width, "card outside width");
        }
        for (int row = 0; row < Math.min(l.totalRows(), visibleRows); row++) {
            int y = l.y(row, 0);
            check(y >= 52 && y + StorageOverview.CARD_H <= height - 28,
                    "card outside content height");
        }
        check(l.maxScroll() == Math.max(0, l.totalRows() - visibleRows), "scroll range");
    }

    private static void equal(int actual, int expected) {
        check(actual == expected, "expected " + expected + ", got " + actual);
    }

    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }
}
