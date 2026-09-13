# Endsight

A Fabric client mod for **DragSim** — End Simulator / Dragon Sim.

Storage previews, dragon, slayer and zealot trackers, drop alerts, and on-screen calls
for the things worth looking up for. Press **Right Shift** in game to open the module
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

**Zealot Tracker** — kills, Summoning Eyes and Golden Eyes for the session, each with
its hourly rate. Drops are read from your own chat. A kill is only counted when one of
your own swings or scythe casts explains it, so other people farming the same nest do
not inflate your number. Breaks over thirty seconds come off the clock.

**Damage Numbers** — the floating damage popups, shortened to `8.49M` or removed
outright, switched between with one button. Colours are kept, so a shortened number
still looks like the server wrote it.

**Storage Preview** — snapshots each storage page as you leave it, then shows that page
above the item when you hover it in the Storage menu. Snapshots are saved to disk, so
they survive a restart, and are kept per server.

**Nukubi Highlight** — box and tracer marking Nukubi, with an option to ignore ones
tagged as another player's.

### Alerts

One page listing everything that can interrupt you, each with its own toggle:

- Miniboss appeared
- Slayer boss spawning
- Endstone Protector spawned

Shown mid-screen where Minecraft puts its own titles, with size, opacity and duration
sliders shared across all of them.

**Loot Alerts** — the same mid-screen call, plus a ping, when a drop worth stopping for
lands. Just the item's name, in its rarity colour, heavier for legendary. Which drops
count is set by tier — see *Drops* below.

### Quality of Life

**Drop Tracker** — every drop counted, on screen, newest at the top. One button switches
between this session and your all-time total, which is kept in
`config/endsight/drops-total.txt`.

**Copy drops** — the last drop's name goes straight to your clipboard as it lands, for
pasting into Discord.

**Debug on join** — runs `/debug` for you two seconds after every join, so kills show
their loot number. It is a toggle on the server's side, so the reply is read and it is
re-sent if it flipped the wrong way.

**Math Solver** — the Golden Dragon's "Find the answer to 76.2 + 19.6?" is answered
under the question, as a line only you see, or put on the clipboard. Exact decimal
arithmetic, so `95.8` and never `95.80000000000001`.

**Item Search** — a search box in the Storage window. Matches are ringed where they
already are rather than listed: pages holding a match light up with a count, items in an
open page light up individually, and pages you have never opened are marked as unknown
instead of reported empty.

**Protect Placed Eyes** — placing an eye and taking one back out are the same
right-click, so spam-clicking to place puts one in and immediately pulls it out. A
second click on the frame you just filled is ignored for a moment; the frame beside it
takes your next eye straight away. Duration is adjustable.

### Drops

Loot Alerts, Drop Tracker and Copy drops all read one list:
`config/endsight/drops.txt`, created on first run from a default built out of the
bestiary. Each line is a tier and an item name — `legendary`, `epic`, `rare` or
`common` — set by rarity **and value** rather than the game's colour, so Aspect of
the Dragons (gold in game, one kill in fourteen) is common there. New items are a line
in that file, not a new build; the Drop Tracker has a *Reload tiers* button. Anything
not listed falls back to the server's own tier word.

A drop that someone else pastes into chat is ignored, and with `/debug` on the loot
line and the announcement for one drop are counted once.

---

## Settings

Everything is a module with its own on/off switch and settings page, grouped by category
in the sidebar. Seven palettes; the whole UI follows whichever is picked.

Any module can be bound to a key: click the chip on its card, press a key. Right-click
the chip to clear it. A toast in the bottom-right says what the key just did.

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

- **Boss Highlight** — marking your own slayer boss

---

Built by Fear.
