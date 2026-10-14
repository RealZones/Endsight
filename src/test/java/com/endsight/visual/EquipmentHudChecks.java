package com.endsight.visual;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

/** Server pet selection and native armor slot boundaries, independent of the rendering style. */
public final class EquipmentHudChecks {
    private static int checks;

    public static void main(String[] args) {
        EquipmentHud.Pet golden = EquipmentHud.petLine("§fPet: §7[Lvl 200] §6Golden Dragon");
        check(golden != null && golden.level() == 200 && golden.name().equals("Golden Dragon")
                && golden.colour() == 0xFFFFAA00, "sidebar retains level and legendary rarity");
        EquipmentHud.Pet bal = EquipmentHud.petLine("  §fPet: §7[Lvl100] §5Bal  ");
        check(bal != null && bal.level() == 100 && bal.name().equals("Bal")
                && bal.colour() == 0xFFAA00AA, "compact level and surrounding whitespace");
        check(EquipmentHud.petLine("Pet: None") == null, "unequipped pet clears");
        check(EquipmentHud.petLine("Pet: [Lvl 0] Golden Dragon") == null, "invalid level rejected");
        check(EquipmentHud.petLine("Pet: Golden Dragon") == null, "partial scoreboard update rejected");
        check(EquipmentHud.petLine("[MVP+] Player: [Lvl 200] Golden Dragon") == null, "player chat is not pet selection");
        check(EquipmentHud.petLine("Pet: [Lvl 200] Golden Dragon\nother line") == null, "multiline source rejected");
        check(EquipmentHud.tabPet(List.of("§6Active Pet", "§7[Lvl 200] §6Golden Dragon")).equals(golden),
                "explicit active-pet tab header");
        check(EquipmentHud.tabPet(List.of("§6Active Pet:", "§7[Lvl 200] §6Golden Dragon")).equals(golden),
                "active-pet header with colon");
        check(EquipmentHud.tabPet(List.of("§7[Lvl 200] §6Golden Dragon")) == null,
                "unlabelled tab line is not an active pet");
        check(EquipmentHud.tabPet(List.of("Pet: None", "Active Pet", "[Lvl 200] Golden Dragon")) == null,
                "explicit no-pet does not use stale fallback");
        check(EquipmentHud.tabPet(List.of("Active Pet", "None")) == null, "no pet under header");
        check(EquipmentHud.tabNoPet(List.of("§6Active Pet", "§cNone")), "explicit tab unequip clears remembered pet and item");
        check(EquipmentHud.tabNoPet(List.of("Pet: None", "Active Pet", "[Lvl 200] Golden Dragon")),
                "unequipped line overrides stale following pet name");
        check(!EquipmentHud.tabNoPet(List.of("Players", "Nothing here")), "missing pet information does not erase memory");
        check(!EquipmentHud.tabNoPet(List.of("Active Pet", "[Lvl 200] Golden Dragon")), "selected pet is not an unequip");
        check(new EquipmentHud.Pet(196, "Golden Dragon", 0xFFFFAA00)
                        .equals(EquipmentHud.selectedPet("§7Selected Pet: §7[Lvl 196] §6Golden Dragon")),
                "main menu's Pets button names the selected pet");
        check(EquipmentHud.selectedPet("§7Selected Pet: §cNone") == null, "no selected pet");
        check(EquipmentHud.selectedPet("§7Gain 4 Strength per [Lvl 196] Golden Dragon") == null,
                "only the Selected Pet line counts");
        EquipmentHud.HeldItem shelmet = EquipmentHud.heldItem(List.of(
                "§7Golden Dragon bonuses", "§6Held Item: Dwarf Turtle Shelmet", "§aClick to deactivate!"));
        check(shelmet != null && shelmet.name().equals("Dwarf Turtle Shelmet"),
                "real pet lore identifies Shelmet instead of the pet itself");
        check(EquipmentHud.heldItem(List.of("§7Held Item: §5Lucky Clover")).name().equals("Lucky Clover"),
                "Clover held-item name");
        check(EquipmentHud.heldItem(List.of("§7Held Item: §5Combat XP Boost")).name().equals("Combat XP Boost"),
                "XP Boost held-item name");
        check(EquipmentHud.heldItem(List.of("§7Held Item:", "§9Dwarf Turtle Shelmet")).name().equals("Dwarf Turtle Shelmet"),
                "separate held-item value line");
        check(EquipmentHud.heldItem(List.of("§7Held Item: None")) == null, "removed pet item clears");
        check(EquipmentHud.heldItem(List.of("§6Combat Exp Boost", "§7Gain 10% more XP")) == null,
                "pet ability is not an equipped XP Boost");
        check(EquipmentHud.heldItem(List.of("[Lvl 200] Golden Dragon", "Click to deactivate!")) == null,
                "pet name and its icon do not stand in for a held item");
        check(EquipmentHud.retainedHeldItem(golden, shelmet, new EquipmentHud.Pet(201, "Golden Dragon", golden.colour()), null, false)
                        .equals(shelmet), "sidebar level updates retain the held item");
        check(EquipmentHud.retainedHeldItem(golden, shelmet, bal, null, false) == null,
                "switching pet cannot borrow the previous pet's Shelmet");
        check(EquipmentHud.retainedHeldItem(golden, shelmet, golden, null, true) == null,
                "full Pets-menu refresh without held item clears a remembered Shelmet");
        check(EquipmentHud.armorIndex(2, 2) == 0 && EquipmentHud.armorIndex(17.99, 17.99) == 0,
                "helmet exact native icon bounds");
        check(EquipmentHud.armorIndex(18, 2) == -1 && EquipmentHud.armorIndex(21.99, 2) == -1,
                "gap between helmet and chestplate is not clickable");
        check(EquipmentHud.armorIndex(22, 2) == 1 && EquipmentHud.armorIndex(42, 2) == 2
                && EquipmentHud.armorIndex(62, 2) == 3, "four slots retain equipment order");
        check(EquipmentHud.armorIndex(1.99, 2) == -1 && EquipmentHud.armorIndex(2, 18) == -1
                && EquipmentHud.armorIndex(82, 2) == -1, "outer padding is not clickable");
        check(EquipmentHud.armorIndex((45 - 41) / 2.0, (24 - 20) / 2.0) == 0,
                "scaled screen coordinates resolve to helmet");
        // "Equipped Combat XP Boost on [Lvl 199] Golden Dragon!" - the server telling
        // us the pet's item changed. Before this the icon stayed on the previous item
        // until the Pets menu was opened again.
        EquipmentHud.Equip boost = EquipmentHud.equipped("§aEquipped §9Combat XP Boost §aon §7[Lvl 199] §6Golden Dragon§a!");
        check(boost != null && boost.item().name().equals("Combat XP Boost"), "the equipped item is read from chat");
        check(boost.pet().level() == 199 && boost.pet().name().equals("Golden Dragon"), "and so is the pet it went on");
        EquipmentHud.Equip plainLine = EquipmentHud.equipped("Equipped Lucky Clover on [Lvl 100] Tiger!");
        check(plainLine != null && plainLine.item().name().equals("Lucky Clover"), "and without colour codes");
        check(EquipmentHud.equipped("Equipped Combat XP Boost on [Lvl 0] Golden Dragon!") == null,
                "a level of zero is not a pet");
        check(EquipmentHud.equipped("someone: Equipped Combat XP Boost on [Lvl 199] Golden Dragon!") == null,
                "a player quoting the line does not change the icon");
        check(EquipmentHud.equipped("Selected Pet: [Lvl 196] Golden Dragon") == null,
                "the selection line is not an equip");
        check(EquipmentHud.equipped(null) == null, "null is not an equip");

        EquipmentHud.Pet activated = EquipmentHud.activated("§aActivated §7[Lvl 200] §6Golden Dragon§a!");
        check(golden.equals(activated), "recorded Activated line immediately selects the new pet with its rarity");
        check(EquipmentHud.activated("Activated [Lvl 100] Bal").name().equals("Bal"),
                "activation announcement without exclamation mark");
        check(EquipmentHud.activated("Player: Activated [Lvl 200] Golden Dragon!") == null,
                "player quoting activation is not a server switch");
        check(EquipmentHud.activated("Activated [Lvl 0] Golden Dragon!") == null
                && EquipmentHud.activated(null) == null, "invalid activation cannot replace the current pet");

        EquipmentHud.MenuPet activeGolden = EquipmentHud.menuPet("§7[Lvl 200] §6Golden Dragon", List.of(
                "§7Held Item: §9Dwarf Turtle Shelmet", "§aClick to deactivate!"));
        EquipmentHud.MenuPet inactiveBal = EquipmentHud.menuPet("§7[Lvl 100] §5Bal", List.of(
                "§7Held Item: §5Lucky Clover", "§aClick to summon!"));
        check(activeGolden.selected() && activeGolden.item().name().equals("Dwarf Turtle Shelmet"),
                "selected menu pet has its actual held item");
        check(!inactiveBal.selected() && inactiveBal.item().name().equals("Lucky Clover"),
                "inactive Pets-menu entries also teach their held items");

        EquipmentHud.PetItems cache = new EquipmentHud.PetItems();
        cache.readMenu(List.of(activeGolden, inactiveBal));
        check(cache.get(golden).name().equals("Dwarf Turtle Shelmet"), "Golden Dragon's Shelmet is cached");
        check(cache.retain(golden, activeGolden.item(), bal).name().equals("Lucky Clover"),
                "Golden Dragon to Bal uses Bal's item instead of clearing or borrowing the Shelmet");
        check(cache.retain(bal, inactiveBal.item(), golden).name().equals("Dwarf Turtle Shelmet"),
                "Bal back to Golden Dragon restores the original Shelmet");
        EquipmentHud.Pet levelled = new EquipmentHud.Pet(201, golden.name(), golden.colour());
        check(cache.get(levelled).equals(activeGolden.item()), "level changes retain the same pet's cache identity");
        EquipmentHud.Pet unseen = new EquipmentHud.Pet(100, "Tiger", 0xFFFFAA00);
        check(cache.retain(golden, activeGolden.item(), unseen) == null,
                "unseen pet cannot inherit another pet's held item");
        check(cache.get(new EquipmentHud.Pet(200, golden.name(), 0xFFAA00AA)) == null,
                "a different rarity is not the same known pet");
        cache.remember(golden, null);
        check(cache.get(golden) == null && cache.get(bal).equals(inactiveBal.item()),
                "authoritative no-item clears only that pet's held item");
        cache.remember(golden, activeGolden.item());

        CompoundTag saved = new CompoundTag();
        cache.save(saved);
        EquipmentHud.PetItems reloaded = new EquipmentHud.PetItems();
        reloaded.load(saved);
        check(reloaded.get(golden).equals(activeGolden.item()) && reloaded.get(bal).equals(inactiveBal.item()),
                "all known pets survive a restart rather than only the last active pet");
        EquipmentHud.MenuPet noItem = EquipmentHud.menuPet("§7[Lvl 100] §5Bal", List.of(
                "§7Held Item: None", "§aClick to summon!"));
        reloaded.readMenu(List.of(noItem));
        CompoundTag removed = new CompoundTag();
        reloaded.save(removed);
        EquipmentHud.PetItems removedReload = new EquipmentHud.PetItems();
        removedReload.load(removed);
        check(removedReload.get(bal) == null && removedReload.get(golden).equals(activeGolden.item()),
                "explicit removed item survives restart without erasing other pets");

        EquipmentHud.PetItems duplicate = new EquipmentHud.PetItems();
        EquipmentHud.MenuPet otherGolden = new EquipmentHud.MenuPet(golden,
                new EquipmentHud.HeldItem("Combat XP Boost", 0xFF5555FF), false);
        duplicate.readMenu(List.of(activeGolden, otherGolden));
        check(duplicate.get(golden) == null,
                "same-name and rarity pets with different items cannot restore an arbitrary inactive pet's item");
        check(duplicate.retain(golden, activeGolden.item(), golden).equals(activeGolden.item()),
                "current selected pet's direct menu read remains valid despite ambiguous name-only cache");
        CompoundTag duplicatesSaved = new CompoundTag();
        duplicate.save(duplicatesSaved);
        EquipmentHud.PetItems duplicateReload = new EquipmentHud.PetItems();
        duplicateReload.load(duplicatesSaved);
        check(duplicateReload.get(golden) == null, "ambiguous pet identities remain conservative after restart");

        System.out.println(checks + " equipment HUD checks passed");
    }

    private static void check(boolean passed, String name) {
        checks++;
        if (!passed) throw new AssertionError(name);
    }
}
