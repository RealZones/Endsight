# Endsight

A Fabric client mod for **DragSim** — End Simulator / Dragon Sim.

Trackers for dragons, zealots and slayers, drop alerts and a drop tracker, storage
previews and a Voidgloom helper. Press **Right Shift** in game to open the module
browser; any module can be bound to a key.

---

## Requirements

| | |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.2+ |
| Fabric API | required |
| Java | 25 |

## Install

1. Download `endsight-0.2.1.jar` from [Releases](../../releases).
2. Drop it in your `mods` folder alongside Fabric API.
3. Launch, join DragSim, press **Right Shift**.

---

## Features

### Trackers

**Dragon Timer** — eyes placed out of eight, then the countdown to the next dragon.
Golden eyes are marked.

**Protector Stage** — the Endstone Protector's tier, 2 to 5, and how long it has been up.

**Zealot Tracker** — your zealot kills, Summoning Eyes and Golden Eyes this session,
each with a per-hour rate. Only your own kills count. Pauses when you stop moving.

**Slayer Tracker** — kills, average kill time, time spent and kills per hour. Pauses
when you stop moving. Each boss's kill time is printed in chat.

**Drop Tracker** — every drop you get, counted on screen. One button switches between
this session and all-time.

### Alerts

**Alerts** — mid-screen alerts for a miniboss, a slayer boss spawning and the Endstone
Protector spawning. Size, opacity and duration are adjustable.

**Loot Alerts** — mid-screen alert and a ping when a drop you care about lands. Which
drops count is set by tier — see *Drops*.

### Visual

**Voidgloom Helper** — your Seraph's hits left on its shield, big on screen, and its
health once the shield is down. A box and tracer on the boss and on the Yang Glyph
beacon, drawn in the world. Your own boss only.

**Protector Beacon** — a beam over the Protector so it can be found at a glance.

**Damage Numbers** — shortens damage popups to `8.49M`, or hides them.

**Storage Preview** — hover a storage page to see what is in it.

### Chat

**Debug on join** — turns `/debug` on for you after every join, so kills show their
loot number.

**Loot Number Filter** — hides `loot number:` lines except the close calls: rolls under
a number you set (2.5 to start) and, with *Both ends* on, the same distance from 100 —
a 99.98 is as rare as a 0.02. Lines that dropped something always show.

**Copy drops** — the last drop's name goes to your clipboard as it lands.

**Math Solver** — answers the Golden Dragon's math question under the question, or
copies the answer.

### Quality of Life

**Protect Placed Eyes** — stops a spam-click from pulling out the eye you just placed.

**Item Search** — a search box in Storage that marks which pages hold the item.

**Command Binds** — a key that sends a command you typed, like `/warp end` on F6.

### Drops

Loot Alerts, Drop Tracker and Copy drops share one list, `config/endsight/drops.txt`,
created on first run. Each line is a tier and an item name — `legendary`, `epic`,
`rare` or `common` — set by rarity and value rather than the game's colour. Add or move
items by editing the file; the Drop Tracker has a *Reload tiers* button. Anything not
listed uses the server's own tier. Drops other people paste into chat are ignored.

---

## Settings

Everything is a module with its own on/off switch and settings page, grouped by category
in the sidebar. Seven palettes; the whole UI follows whichever is picked.

Any module can be bound to a key or a mouse button: click the chip on its card, press
the key. Right-click the chip to clear it. A toast in the bottom-right says what the key
just did. Every on-screen readout can be dragged to where you want it.

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
