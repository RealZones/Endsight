# Endsight

A Fabric client mod for **DragSim** — End Simulator / Dragon Sim.

Trackers for dragons, zealots and slayers, drop alerts, a recipe browser, storage
previews and a Voidgloom helper. Press **Right Shift** in game to open the module
browser (rebind it under Controls → Endsight); any module can be bound to a key.

---

## Requirements

| | |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.1+ |
| Fabric API | required |
| Java | 25 |

## Install

1. Download the latest `endsight-<version>.jar` from [Releases](../../releases).
2. Drop it in your `mods` folder alongside Fabric API.
3. Launch, join DragSim, press **Right Shift**.

---

## Features

### Trackers

**Dragon Timer** — eyes placed out of eight, then the countdown to the next dragon.
Golden eyes are marked.

**Protector Stage** — the Endstone Protector's tier, 2 to 5, and how long it has been up.

**Boss Drops** — one readout for whichever boss you killed last — dragons, Warden,
Protector, zombie and enderman slayers — its kills and the drops it gave you, rarest
first in the item's own rarity colour, filtered by your tier list. Only your bosses
count: a dragon you placed an eye for, a Protector or Warden you placed top three on
(or whose catalyst you placed), your own slayer bosses. Click the chip on its title
with chat open to flip session / all-time; the chip beside it switches the dragon
readout between every kind added up and one kind, since a Golden Dragon Chestplate is
one in so many Golden dragons, not one in every dragon. `/drops` or `/drops dragon`
(`golden`, `protector`, `superior`…, `warden`, `golem`, `zombie`, `enderman`) print
any of them into chat.

**Zealot Tracker** — your zealot kills, Summoning Eyes and Golden Eyes this session,
each with a per-hour rate. Only your own kills count. Pauses when you stop moving.

**Slayer Tracker** — kills, average kill time, time spent and kills per hour. Pauses
when you stop moving. Each boss's kill time is printed in chat.

### Alerts

**Alerts** — mid-screen alerts for a miniboss, a slayer boss spawning, the Endstone
Protector spawning, the dragon's fireball and a full inventory. Size, opacity and
duration are adjustable.

**Loot Alerts** — mid-screen alert and a ping when a drop you care about lands, the
name in the item's own rarity colour. Which drops count is set by tier — see *Drops*.

### Visual

**Voidgloom Helper** — your Seraph's hits left on its shield, big on screen, and its
health once the shield is down. A box and tracer on the boss and on the Yang Glyph
beacon, drawn in the world. Your own boss only.

**Boss Beacon** — a beam over the Endstone Protector and one over the Warden, each its
own toggle, so they can be found at a glance.

**Damage Numbers** — shortens damage popups to `8.49M`, or hides them. *Crits only*
hides the plain numbers — non-crits and ability damage — and keeps the crits.

**Storage Preview** — hover a storage page to see what is in it. Its item search puts a
box in the Storage window that marks which pages hold the item.

**Ping / TPS** — FPS, your ping and the server's tick rate in one pill. Ping is a real
round trip measured once a second; TPS is worked out from the server's clock, good to
about half a tick.

### Chat

**Debug on join** — turns `/debug` on for you after every join, so kills show their
loot number.

**Loot Number Filter** — hides `loot number:` lines except the close calls: rolls under
a number you set (2.5 to start) and, with *Both ends* on, the same distance from 100 —
a 99.98 is as rare as a 0.02. Lines that dropped something always show.

**Compact Loot Roll** — the detailed `/debug` roll after a dragon, folded from twelve
lines into two with just the numbers: dragon, rank, damage, score, MF, pet luck; then
loot number, armour roll against what it needed, RNG meter, the result and bits.

**Ability Spam** — "This ability is on cooldown" once, with a count, instead of forty
times; or hidden altogether.

**Copy Chat** — right-click any chat line to copy it. Optionally each drop's name goes
to the clipboard on its own as it lands.

**Math Solver** — answers the Golden Dragon's math question under the question, or
copies the answer.

### Quality of Life

**Protect Placed Eyes** — stops a spam-click from pulling out the eye you just placed.

**Recipes** — an item panel beside any inventory window: every server recipe as a
grid of icons in the server's own categories, paged and searchable. Hover for what
you hold of each ingredient (your inventory, every storage page you have opened, and
your ender chest),
click for the recipe's grid — craftable ingredients are underlined and click through
to their own recipe — right-click for everything that uses an item. **R** over any
item in any window opens its recipe, **U** what it goes into. At a crafting
table, *Fill grid* places the recipe from your inventory; anywhere else the button
sends `/craft`. Every recipe ships with the mod; *Scan all recipes* re-reads the
server's menu if they change, into `config/endsight/recipes.txt`.

**Command Binds** — a key that sends a command you typed, like `/warp end` on F6.

A toast and a chat line with the link appear when a newer release is out, checked
shortly after launch and every half hour while you play.

### Drops

Loot Alerts and Copy Chat share one list, `config/endsight/drops.txt`,
created on first run. Each line is a tier and an item name — `legendary`, `epic`,
`rare` or `common` — set by rarity and value rather than the game's colour. Add or move
items by editing the file; Loot Alerts has a *Reload tiers* button. Anything not
listed uses the server's own tier. Drops other people paste into chat are ignored.

---

## Settings

Everything is a module with its own on/off switch and settings page, grouped by category
in the sidebar. Seven palettes; the whole UI follows whichever is picked.

Any module can be bound to a key or a mouse button: click the chip on its card, press
the key. Right-click the chip to clear it. A toast in the bottom-right says what the key
just did. Every on-screen readout can be dragged to where you want it.

Readouts about the End - dragon, protector, zealots, the beacon - stay off the screen
anywhere else, read off the server's own sidebar. In the placement screen the scroll
wheel over a readout resizes it, half to double.

The menu reopens where you left it - same category, same settings page, same scroll.
Your choices are saved to `config/endsight.properties` when you close the menu and again
on exit. Storage snapshots are saved separately to `config/endsight-storage.nbt`.

---

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`.

---

Built by Fear.

## Licence

All rights reserved - see [LICENSE](LICENSE). Read it, play with it, do not redistribute or reuse it.
