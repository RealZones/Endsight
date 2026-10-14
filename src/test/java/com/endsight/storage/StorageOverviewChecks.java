package com.endsight.storage;

/** Geometry checks for the page index at common Minecraft GUI sizes. */
public final class StorageOverviewChecks {
    private static int checks;

    public static void main(String[] args) {
        verify(539, 498, 7, 3, 3);
        verify(320, 240, 7, 1, 1);
        verify(960, 540, 7, 3, 3);
        verify(600, 360, 10, 3, 2);
        verify(371, 326, 7, 2, 2);
        System.out.println(checks + " storage overview checks passed");
    }

    private static void verify(int width, int height, int pages, int cols, int visibleRows) {
        StorageOverview.Layout l = StorageOverview.layout(width, height, pages);
        equal(l.cols(), cols);
        equal(l.visibleRows(), visibleRows);
        equal(l.totalRows(), (pages + cols - 1) / cols);
        for (int col = 0; col < cols; col++) {
            int x = l.x(col);
            check(x >= 10 && x + StorageOverview.CARD_W <= width - 10, "page grid outside screen margins");
            if (col > 0) check(x - (l.x(col - 1) + StorageOverview.CARD_W) >= 10,
                    "adjacent page grids overlap or lose their gap");
        }
        check(Math.abs(l.x(0) - (width - l.x(cols - 1) - StorageOverview.CARD_W)) <= 1,
                "page grids are not centered");
        for (int row = 0; row < Math.min(l.totalRows(), visibleRows); row++) {
            int y = l.y(row, 0);
            check(y >= 52 && y + StorageOverview.CARD_H <= height - 28,
                    "page grid overlaps the header or navigation");
            if (row > 0) check(y - (l.y(row - 1, 0) + StorageOverview.CARD_H) >= 10,
                    "adjacent page rows overlap or lose their gap");
        }
        check(l.maxScroll() == Math.max(0, l.totalRows() - visibleRows), "scroll range");
        int lastRow = l.totalRows() - 1;
        int lastY = l.y(lastRow, l.maxScroll());
        check(lastY >= 52 && lastY + StorageOverview.CARD_H <= height - 28,
                "last page row is not reachable inside the content area");
        check(l.y(l.maxScroll(), l.maxScroll()) == 52, "scrolled first row does not align below header");
    }

    private static void equal(int actual, int expected) {
        check(actual == expected, "expected " + expected + ", got " + actual);
    }

    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }
}
