# Endsight

A Fabric client mod for **DragSim** — End Simulator / Dragon Sim.

Storage previews, dragon and slayer timers, and on-screen calls for the things worth
looking up for. Press **Right Shift** in game to open the module browser.

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

### Dragons

**Dragon Timer** — one line. Counts the eight eyes going in, then counts down to the egg
respawning. The countdown is anchored to the server's own announcements, so it cannot
drift.

### Golem

**Protector Stage** — tracks the Endstone Protector climbing tier 2 through 5, and how
long it has been up once it spawns.

**Protector Beacon** — a beam over the Endstone Protector so it can be found at a
glance, with adjustable height and brightness.

### Slayers

**Slayer Tracker** — kills, average kill time, time spent and kills per hour for the
session. Breaks are subtracted rather than counted, and the readout hides itself when
you stop slaying. Each boss's own time is printed to chat as it dies.

### Visual

**Nukubi Highlight** — box and tracer marking Nukubi, with an option to ignore ones
tagged as another player's.

### Alerts

One page listing everything that can interrupt you, each with its own toggle:

- Miniboss appeared
- Slayer boss spawning
- Endstone Protector spawned

Shown mid-screen where Minecraft puts its own titles, with size, opacity and duration
sliders shared across all of them.

### Quality of Life

**Storage Preview** — snapshots each storage page as you leave it, then shows that page
above the item when you hover it in the Storage menu. Snapshots are saved to disk, so
they survive a restart, and are kept per server.

**Item Search** — a search box in the Storage window. Matches are ringed where they
already are rather than listed: pages holding a match light up with a count, items in an
open page light up individually, and pages you have never opened are marked as unknown
instead of reported empty.

**Protect Placed Eyes** — placing an eye and taking one back out are the same
right-click, so spam-clicking to place puts one in and immediately pulls it out. Clicks
are ignored for a moment after a placement lands. Duration is adjustable.

**Damage Numbers** — the floating damage popups, shortened to `8.49M` or removed
outright, switched between with one button. Spam-clicking a boss stacks dozens of
eight-digit numbers over it, so older ones can be hidden as new ones land, leaving
however many you want on screen at a time.

---

## Settings

Everything is a module with its own on/off switch and settings page, grouped by category
in the sidebar. Seven palettes; the whole UI follows whichever is picked.

Your choices are saved to `config/endsight.properties` when you close the menu and again
on exit. Storage snapshots are saved separately to `config/endsight-storage.nbt`.

---

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`.

---

## Not done yet

- **Loot Alerts** — on-screen alerts for drops
- **Boss Highlight** — marking your own slayer boss

---

Built by Fear.
