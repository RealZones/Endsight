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
        nativePageAccess();
        System.out.println(checks + " storage overview checks passed");
    }

    private static void nativePageAccess() {
        StorageOverview.MenuPage purchased = StorageOverview.menuPage("§5Storage Page 8",
                "minecraft:purple_stained_glass_pane", java.util.List.of("§eClick to view this storage page!"), 42);
        check(purchased != null && purchased.page() == 8 && purchased.menuSlot() == 42
                && purchased.access() == StorageOverview.PageAccess.OPEN,
                "purchased page keeps the actual menu slot rather than calculating it from the page");
        check(StorageOverview.menuPage("Locked Page", "minecraft:red_stained_glass_pane",
                java.util.List.of("Unlock more storage at the community shop"), 43) == null,
                "shop entry has no numbered page and cannot become a navigation target");
        check(StorageOverview.menuPage("Storage Page 8", "minecraft:purple_stained_glass_pane",
                java.util.List.of(), 42).access() == StorageOverview.PageAccess.UNKNOWN,
                "title or old snapshot alone cannot grant access");
        check(StorageOverview.menuPage("Storage Page 8", "minecraft:red_stained_glass_pane",
                java.util.List.of("Click to view this storage page!"), 42).access() == StorageOverview.PageAccess.LOCKED,
                "locked red pane overrides misleading view text");
        check(StorageOverview.menuPage("Storage Page 8", "minecraft:purple_stained_glass_pane",
                java.util.List.of("Click to view this storage page!", "Click to purchase this page"), 42)
                        .access() == StorageOverview.PageAccess.LOCKED,
                "purchase and unlock actions are never automated");
        check(StorageOverview.menuPage("Storage Page 8", "minecraft:purple_stained_glass_pane",
                java.util.List.of("Unlocked", "Click to open!"), 42).access() == StorageOverview.PageAccess.OPEN,
                "unlocked status is not mistaken for a locked action");
        check(StorageOverview.menuPage("Storage Page 8 recipe", "minecraft:purple_stained_glass_pane",
                java.util.List.of("Click to view"), 42) == null, "only exact page titles qualify");
        check(StorageOverview.menuPage("Storage Page 8", "minecraft:purple_stained_glass_pane",
                java.util.List.of("Click to view"), -1) == null, "missing native slot cannot be clicked");

        Object world = new Object(), source = new Object(), root = new Object();
        StorageOverview.PageRequest request = new StorageOverview.PageRequest(8, world, source, 1000);
        check(request.advance(world, source, false, null, 1100) == StorageOverview.OpenAction.WAIT,
                "wait for the root menu after sending bare storage");
        check(request.advance(world, root, true, null, 1200) == StorageOverview.OpenAction.WAIT,
                "opening root before its items arrive does not click an invented slot");
        check(request.advance(world, root, true, purchased, 1300) == StorageOverview.OpenAction.CLICK,
                "only observed accessible metadata allows the requested native click");
        request = new StorageOverview.PageRequest(8, world, source, 1000);
        check(request.advance(world, root, true,
                new StorageOverview.MenuPage(8, 42, StorageOverview.PageAccess.LOCKED), 1200)
                        == StorageOverview.OpenAction.LOCKED, "locked target aborts navigation");
        request = new StorageOverview.PageRequest(8, world, source, 1000);
        check(request.advance(world, new Object(), false, null, 1200) == StorageOverview.OpenAction.CANCEL,
                "an unrelated screen cancels a pending request");
        check(request.advance(new Object(), root, true, purchased, 1200) == StorageOverview.OpenAction.CANCEL,
                "world transfer cancels a pending request");
        check(request.advance(world, null, false, null, 1200) == StorageOverview.OpenAction.CANCEL,
                "closing the source screen cancels navigation");
        check(request.advance(world, root, true, purchased, 6000) == StorageOverview.OpenAction.EXPIRED,
                "late menus cannot trigger a forgotten click");
        request = new StorageOverview.PageRequest(8, world, source, 1000);
        request.advance(world, root, true, null, 1100);
        check(request.advance(world, new Object(), true, purchased, 1200) == StorageOverview.OpenAction.CANCEL,
                "replacing the observed root menu cancels pending navigation");
        check(new StorageOverview.PageRequest(8, world, source, 1000).advance(world, root, true,
                new StorageOverview.MenuPage(7, 42, StorageOverview.PageAccess.OPEN), 1200)
                        == StorageOverview.OpenAction.WAIT, "a different observed page cannot satisfy the request");
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
