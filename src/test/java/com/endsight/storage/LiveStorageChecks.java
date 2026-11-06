package com.endsight.storage;

/** Live and cached grids must never share transaction positions. */
public final class LiveStorageChecks {
    public static void main(String[] args) {
        for (int[] size : new int[][]{{320,240},{354,240},{371,326},{539,498},{600,360},{640,336},{640,360},{712,360},{853,480},{960,504},{960,540},{1920,1009}}) {
            for (int pages : new int[]{1,2,3,5,9,10,11,30,55}) {
                String at = " at "+size[0]+"x"+size[1]+" with "+pages+" pages";
                var layout = LiveStorageView.layout(size[0], size[1], 5, pages);
                require(layout != null && layout.pageCapacity() >= 1, "No room for page and inventory"+at);
                require(layout.cell() >= 16, "Native item icons overlap");
                int ix = layout.inventoryX(), iy = layout.inventoryY();
                int left = layout.panelX(), right = layout.right(), top = layout.panelY(), bottom = top+layout.panelHeight();
                require(left >= 0 && right <= size[0] && top >= 0 && bottom <= size[1], "Panel outside the window"+at);
                require(ix >= left && ix+layout.cardWidth() <= right && iy >= top && iy+layout.inventoryHeight() <= bottom, "Inventory outside the panel"+at);
                require(layout.searchX() >= left && layout.searchY()+20 <= layout.y(0), "Search box runs into the cards"+at);
                require(layout.arrows() == (pages > layout.pageCapacity()), "Arrows wrong"+at);
                for (int i=0; i<layout.pageCapacity(); i++) {
                    require(layout.x(i) >= left && layout.x(i)+layout.cardWidth() <= right, "Card outside the panel"+at);
                    require(layout.y(i) >= top+40 && layout.y(i)+layout.cardHeight() <= bottom, "Card outside the panel"+at);
                    boolean apart = ix+layout.cardWidth() <= layout.x(i) || layout.x(i)+layout.cardWidth() <= ix
                            || iy+layout.inventoryHeight() <= layout.y(i) || layout.y(i)+layout.cardHeight() <= iy;
                    require(apart, "Inventory overlaps a page"+at);
                }
            }
        }
        // His real window: every page fits and the inventory sits centred under them.
        var wide = LiveStorageView.layout(960,504,5,9);
        require(wide.below() && wide.cols() == 5 && wide.rows() == 2 && wide.pageCapacity() == 10 && !wide.arrows(),
                "His nine pages do not fit over the inventory");
        require(wide.inventoryX() == wide.x(2), "Inventory is not centred under the pages");
        require(wide.inventoryY() >= wide.y(5)+wide.cardHeight(), "Inventory is not under the pages");
        require(wide.left() == (960-(5*wide.cardWidth()+4*10))/2, "The inventory pushed the pages off centre");
        require(Math.abs(wide.panelY()*2+wide.panelHeight()-504) <= 1, "The panel is not centred in the window");
        // Room for a third row, and nothing to put in it: the inventory stays under the pages.
        var spare = LiveStorageView.layout(960,540,5,9);
        require(spare.rows() == 2 && spare.inventoryY() == wide.inventoryY()+spare.top()-wide.top(), "An empty row pushed the inventory down");
        var many = LiveStorageView.layout(960,540,5,14);
        require(many.rows() == 3 && many.below() && !many.arrows(), "A big window did not use its third row");
        var few = LiveStorageView.layout(960,504,5,3);
        require(few.cols() == 3 && few.rows() == 1 && few.below(), "Three pages should not sit in a five wide panel");
        var tall = LiveStorageView.layout(640,360,5,9);
        require(!tall.below() && tall.inventoryX() == tall.x(tall.capacity()-1), "No room under the pages must fall back to a card");
        var chest = LiveStorageView.layout(960,504,3,9);
        require(chest.left() == wide.left() && chest.top() == wide.top() && chest.cardHeight() == wide.cardHeight()
                        && chest.pageCapacity() == wide.pageCapacity(), "Opening the ender chest moved the cards");
        for (int i=0; i<9; i++) require(LiveStorageView.pageCell(i,54)==-1, "Server navigation becomes a stash slot");
        require(LiveStorageView.pageCell(9,54)==0 && LiveStorageView.pageCell(53,54)==44,
                "Native stash ordering changed");
        require(LiveStorageView.pageCell(54,54)==-1, "Out-of-range native slot mapped");
        require(LiveStorageView.playerCell(9)==0 && LiveStorageView.playerCell(35)==26,
                "Main inventory ordering changed");
        require(LiveStorageView.playerCell(0)==27 && LiveStorageView.playerCell(8)==35,
                "Hotbar ordering changed");
        require(LiveStorageView.playerCell(36)==-1 && LiveStorageView.playerCell(-1)==-1,
                "Armor or unavailable slot treated as transferable inventory");
        require(LiveStorageView.clamp(40,20,8)==12 && LiveStorageView.clamp(0,3,8)==0, "Scrolled past the last page");
        require(LiveStorageView.layout(180,120,5,1)==null && LiveStorageView.layout(180,120,5,55)==null, "Tiny screen must fall back to vanilla");
        require(LiveStorageView.reveal(0,6,8)==0, "A page already showing moved the grid");
        require(LiveStorageView.reveal(8,3,8)==0 && LiveStorageView.reveal(0,9,8)==8, "A page out of view was not brought back");
        require(LiveStorageView.reveal(4,6,1)==6, "One card must be the open page");
        for (int total=1; total<=55; total++) for (int page=0; page<total; page++) for (int room : new int[]{1,2,5,8}) {
            int first=LiveStorageView.clamp(LiveStorageView.reveal(0,page,room),total,room);
            require(page>=first && page<first+room, "Clamping pushed the open page back out of view");
        }
        System.out.println("Live storage geometry and native slot mapping checks passed");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
