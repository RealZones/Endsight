package com.endsight.storage;

/** Which pages the server's menu offers, and the request that opens one. */
public final class StorageOverviewChecks {
    private static int checks;

    public static void main(String[] args) {
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

    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }
}
