package com.endsight.qol;

import java.util.List;

/** Native tooltip fixtures are transcribed from the user's 2026-11-04 recording. */
public final class ManualEquipmentBindsChecks {
    private static int checks;
    private static PetBinds.Entry entry(List<PetBinds.Learned> all, PetBinds.Learned pet) {
        return PetBinds.entries(all).stream().filter(entry -> entry.pet() == pet).findFirst().orElseThrow();
    }
    private static String id(List<PetBinds.Learned> all, PetBinds.Learned pet) { return entry(all, pet).id(); }
    private static PetBinds.Target target(List<PetBinds.Learned> all, PetBinds.Learned pet) { return entry(all, pet).target(); }

    public static void main(String[] args) {
        check(WardrobeBinds.wardrobeTitle("Wardrobe"), "recorded wardrobe identity");
        check(!WardrobeBinds.wardrobeTitle("Dragon Sim Menu"), "other server menu rejected");
        check(!WardrobeBinds.wardrobeTitle("Wardrobe Preview"), "similar title rejected");
        check(WardrobeBinds.selector("Slot 4", List.of("Click to equip!")).state() == WardrobeBinds.State.EQUIP, "recorded Slot 4 selector");
        check(WardrobeBinds.selector("§dSlot 4", List.of("§eClick to equip!")).outfit() == 4, "legacy colours stripped");
        check(WardrobeBinds.selector("Necron's Chestplate", List.of("Slot 4", "Click to equip!")) == null, "armour lore does not create a selector");
        check(WardrobeBinds.selector("Slot 0", List.of("Click to equip!")) == null, "invalid zero slot rejected");
        check(WardrobeBinds.selector("Slot 19", List.of("Click to equip!")) == null, "invalid high slot rejected");
        check(WardrobeBinds.selector("Slot 7", List.of("Click to unequip!")).state() == WardrobeBinds.State.EQUIPPED, "toggle-off action recognised");
        check(WardrobeBinds.selector("Slot 7", List.of("Equipped!", "Click to equip!")).state() == WardrobeBinds.State.EQUIPPED, "active status wins conflicting action text");
        check(WardrobeBinds.selector("Slot 2", List.of("This slot is locked!")).state() == WardrobeBinds.State.UNAVAILABLE, "locked wardrobe slot");
        check(WardrobeBinds.selector("Slot 2", List.of("Loading...")).state() == WardrobeBinds.State.UNKNOWN, "loading text cannot authorize a click");
        var equip = new WardrobeBinds.OutfitEntry(39, new WardrobeBinds.Selector(4, WardrobeBinds.State.EQUIP));
        var active = new WardrobeBinds.OutfitEntry(42, new WardrobeBinds.Selector(7, WardrobeBinds.State.EQUIPPED));
        check(WardrobeBinds.choose(List.of(equip, active), 4, false).slot() == 39, "label chooses native selector index");
        check(WardrobeBinds.choose(List.of(equip, active), 7, false).kind() == MenuBindRequest.Kind.DONE, "already equipped never toggles off");
        check(WardrobeBinds.choose(List.of(equip), 4, true).kind() == MenuBindRequest.Kind.WAIT, "unacknowledged click is never repeated");
        check(WardrobeBinds.choose(List.of(equip, equip), 4, false).kind() == MenuBindRequest.Kind.FAIL, "duplicate wardrobe labels fail closed");
        check(WardrobeBinds.choose(List.of(), 4, false).kind() == MenuBindRequest.Kind.WAIT, "missing metadata waits");
        check(!MenuBindRequest.ready(100, 100, 1_100, 1_199), "wardrobe cannot click before server delay");
        check(MenuBindRequest.ready(100, 100, 1_100, 1_200), "wardrobe delay starts at menu arrival");
        check(!MenuBindRequest.ready(100, 1_180, 1_100, 1_200), "late metadata must settle too");
        check(!MenuBindRequest.expired(100, 5_099), "request live before timeout");
        check(MenuBindRequest.expired(100, 5_100), "request expires at five seconds");
        check(PetBinds.page("Pets") == 1, "recorded Pets title");
        check(PetBinds.page("Pets (2/3)") == 2, "page read from server title");
        check(PetBinds.page("Pet Shop") == -1, "pet shop is not own Pets menu");
        List<String> lore = List.of("Held Item", "Dwarf Turtle Shelmet", "ACTIVE", "Click to deactivate!", "(LEGENDARY) (COMBAT PET)");
        var dragon = PetBinds.parse("[Lvl 200] Golden Dragon", lore, 12);
        check(dragon != null && dragon.active(), "recorded active dragon tooltip");
        check(dragon.rarity().equals("LEGENDARY") && dragon.item().equals("Dwarf Turtle Shelmet") && dragon.level() == 200, "recorded pet details");
        var inactive = PetBinds.parse("[Lvl 200] Golden Dragon", List.of("Held Item", "Dwarf Turtle Shelmet", "Click to activate!", "(LEGENDARY) (COMBAT PET)"), 12);
        check(inactive != null && !inactive.active(), "inactive activation tooltip");
        check(PetBinds.parse("Golden Dragon", lore, 12) == null, "plain shop item is not a learned pet");
        check(PetBinds.parse("[Lvl 200] Golden Dragon", List.of("Loading..."), 12) == null, "pet loading cannot authorize click");
        // The badge as the live server sends it, from the labels the old page saved on 2026-11-04.
        String badge = "\ue100\ue200\ue101\ue200\ue101\ue200\ue102\ue200\ue23fLEGENDARY\ue200\ue101\ue200\ue102\ue200\ue23fCOMBAT PET\ue405";
        check(MenuBindRequest.plain(badge).equals("LEGENDARY COMBAT PET"), "private-use badge reads as words, not tofu");
        var live = PetBinds.parse("[Lvl 100] Scatha", List.of(badge, "Click to summon!"), 10);
        check(live != null && live.rarity().equals("LEGENDARY"), "rarity read through the badge glyphs");
        var prose = PetBinds.parse("[Lvl 100] Scatha", List.of("Grants a rare bonus to your pet", "Click to summon!", badge), 10);
        check(prose.rarity().equals("LEGENDARY"), "ability prose is not a rarity badge");
        check(PetBinds.parse("[Lvl 100] Bal", List.of("Grants a rare bonus to your pet", "Click to summon!"), 10).rarity().isEmpty(), "lower-case prose alone gives no rarity");

        check(PetBinds.parse("[Lvl 100] Bal", List.of("Held Item:", "Lucky Clover", "Click to summon!"), 10).item().equals("Lucky Clover"), "colon heading with the item below");
        check(PetBinds.parse("[Lvl 100] Bal", List.of("Pet Item: Lucky Clover", "Click to summon!"), 10).item().equals("Lucky Clover"), "item on one line");
        check(PetBinds.parse("[Lvl 100] Bal", List.of("Held Item: None", "Click to summon!"), 10).item().isEmpty(), "None is no item");
        var gold = new PetBinds.Learned("Golden Dragon", "LEGENDARY", "", 200, 1, null);
        var levelled = new PetBinds.Learned("Golden Dragon", "LEGENDARY", "Lucky Clover", 201, 2, null);
        var epic = new PetBinds.Learned("Golden Dragon", "EPIC", "", 150, 1, null);
        var scatha = new PetBinds.Learned("Scatha", "LEGENDARY", "", 100, 1, null);
        check(id(List.of(gold, scatha), gold).equals(id(List.of(levelled, scatha), levelled)), "levelling or changing a held item keeps the key");
        check(id(List.of(gold, scatha), gold).equals("qol.pets.pet.golden_dragon.legendary"), "keybind id stays readable in the config");
        check(!id(List.of(gold, epic), gold).equals(id(List.of(gold, epic), epic)), "same species at another rarity is its own pet");
        check(PetBinds.entries(List.of(gold, epic, scatha)).size() == 3, "different pets are each their own tile");

        // Two the same in every way: one tile, one key, and it is the key the first already had.
        var copy = new PetBinds.Learned("Golden Dragon", "LEGENDARY", "", 200, 3, null);
        var copies = PetBinds.entries(List.of(gold, copy, scatha));
        check(copies.size() == 2 && copies.getFirst().copies() == 2, "identical copies share one tile that says how many");
        check(copies.getFirst().id().equals(id(List.of(gold, scatha), gold)), "getting a second copy keeps the first one's key");
        check(PetBinds.matches(dragon, copies.getFirst().target()), "a key on copies looks for any of them");

        // Same held item at different levels are different pets, and each gets a key.
        var younger = new PetBinds.Learned("Golden Dragon", "LEGENDARY", "", 150, 3, null);
        var pair = PetBinds.entries(List.of(gold, younger));
        check(pair.size() == 2 && !pair.get(0).id().equals(pair.get(1).id()), "copies at different levels get their own keys");
        check(PetBinds.matches(dragon, target(List.of(gold, younger), gold)), "the level picks the right copy");
        check(!PetBinds.matches(dragon, target(List.of(gold, younger), younger)), "and not the other one");
        var trained = new PetBinds.Learned("Golden Dragon", "LEGENDARY", "", 151, 3, null);
        check(id(List.of(gold, trained), trained).equals(id(List.of(gold, younger), younger)), "training a copy does not cost it its key");

        check(!id(List.of(gold, levelled), gold).equals(id(List.of(gold, levelled), levelled)), "a held item tells two copies apart");
        check(PetBinds.matches(dragon, target(List.of(gold, scatha), gold)), "a one-off pet matches whatever it holds");
        check(target(List.of(gold, levelled), levelled).item().equals("Lucky Clover"), "a twin is told apart by its item");
        check(!PetBinds.matches(dragon, target(List.of(gold, levelled), levelled)), "the other twin does not match");
        check(!PetBinds.matches(dragon, target(List.of(epic), epic)), "other rarity does not match");

        var one = new WardrobeBinds.OutfitEntry(36, new WardrobeBinds.Selector(1, WardrobeBinds.State.EQUIPPED));
        var two = new WardrobeBinds.OutfitEntry(37, new WardrobeBinds.Selector(2, WardrobeBinds.State.EQUIP));
        var oneOff = new WardrobeBinds.OutfitEntry(36, new WardrobeBinds.Selector(1, WardrobeBinds.State.EQUIP));
        var twoOn = new WardrobeBinds.OutfitEntry(37, new WardrobeBinds.Selector(2, WardrobeBinds.State.EQUIPPED));
        var loading = new WardrobeBinds.OutfitEntry(37, new WardrobeBinds.Selector(2, WardrobeBinds.State.UNKNOWN));
        check(WardrobeBinds.swapTarget(List.of(one, two), 1, 2) == 2, "swap leaves the outfit that is on");
        check(WardrobeBinds.swapTarget(List.of(oneOff, twoOn), 1, 2) == 1, "swap comes back");
        check(WardrobeBinds.swapTarget(List.of(oneOff, two), 1, 2) == 1, "neither on starts with the first");
        check(WardrobeBinds.swapTarget(List.of(one, loading), 1, 2) == 0, "swap waits for both selectors");
        check(WardrobeBinds.swapTarget(List.of(one), 1, 2) == 0, "swap waits for a missing selector");
        System.out.println(checks + " manual equipment bind checks passed");
    }
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
}
