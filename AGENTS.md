

**Minecraft 26.1.2, Fabric loader 0.19.2, Java 25.** Windows. Gradle wrapper is in
the repo — build with `./gradlew build`, jar lands in `build/libs/endsight-0.1.0.jar`.

Everything is under `src/client/java/com/endsight/`.

| File | What it is |
|---|---|
| `EndsightClient.java` | Entrypoint. Polls the open key, owns the registry. |
| `ui/EndsightScreen.java` | Module browser: sidebar, categories, search, card grid. |
| `ui/SettingsScreen.java` | One module's settings, laid out from its `Setting` list. |
| `ui/Module.java` / `ModuleRegistry.java` | Modules as data, grouped by category. |
| `ui/Setting.java` | Sealed: `Toggle`, `Slider`, `Choice`, `Section`. |
| `ui/Theme.java` / `Palette.java` | Current palette, derived surfaces, geometry. |
| `ui/ThemeRow.java` | The palette swatches at the foot of a sidebar. |
| `ui/Draw.java` | Rounded rects, per-corner rounding, wrap, gear icon, colour lerp. |
| `ui/Anim.java` | One float that chases a target, framerate-independent. |
| `ui/EndsightDemo.java` | 19 placeholder modules. **Not real features.** |
| `zealots/Zealots.java` | What counts as a zealot and what a scythe is. |
| `qol/Drops.java` | Drop parsing and the tier list (`config/endsight/drops.txt`); Loot Alerts, Drop Tracker and Copy drops read it. |
| `ui/Keybinds.java` | id -> key, for modules and for toggle/choice/action settings. |

## The things that will bite you

**There is no scissor, no blit, no pose stack.** This version's `GuiGraphicsExtractor`
gives `fill` and `text` and that is all this code uses. Rounded corners are drawn as
banded caps; clipping is done by hand, drawing only the part of a card between the two
boundaries. Do not reach for a shader or a texture without a reason — the render API
is the part that changes every Minecraft version, and this survives that.

**Input uses event objects, not loose args.** `mouseClicked(MouseButtonEvent, boolean)`,
`keyPressed(KeyEvent)`, `charTyped(CharacterEvent)`, and `mouseScrolled` still takes
four doubles. Check with `javap` against the Loom jar before guessing — the signatures
changed recently and older examples online are wrong for 26.1.2.

**Clip both edges, and only round real ends.** A card that runs past the top or bottom
of the viewport must be drawn short, with the cut edge left square. Rounding a cut edge
makes a half-scrolled row look like a small card instead of a clipped one. Masking with
a fixed strip does not work — the strip is always shorter than a card.

**Surfaces are solved, not listed.** `Palette` lists only colours that carry meaning;
`input`/`hair`/`surface`/`line`/`raised`/`hover` are each a target contrast ratio
against the background, blended along that palette's own muted tint. That is why a new
palette needs no hand-tuning. Do not paste literal surface colours in — that is what
made the desk dashboard look flat before it worked this way.

**Hit testing is recomputed every frame**, not stored in widgets. `layout()` runs in
both render and click handling. Vanilla `Button`s would need repositioning on every
scroll and filter change, and keeping widget bounds in sync with a scrolling grid is a
bug factory.

## Adding things

A module is one line in `EndsightClient.registry()`. The category is free text — the
sidebar builds itself from whatever categories are present, so a new one needs no enum,
no sidebar edit and no layout change.

A setting is one entry in that module's list. `SettingsScreen` draws it. If you need a
control that does not exist, add it to the sealed `Setting` interface — sealed on
purpose, so a missing case is a compile error rather than a control that silently
renders as nothing.

## House style

Comments explain **why**, especially where the code looks odd. Several things here look
wrong until you know what they are avoiding — the hand-clipping, the recomputed layout,
the solved surfaces. Keep that. If you fix something subtle, say what it was doing
wrong and how it was found, not just what the fix is.

## Known rough edges

- `SettingsScreen` **culls** rows that do not fully fit instead of clipping them. Fine
  at three settings, will pop when scrolling a long page. Wants the same hand-clipping
  the cards got.
- The gear icon is drawn from rectangles. It reads as a gear now but a small PNG would
  be better; Fear offered to supply one.
  between turns and no bad days, which over an hour is its own pattern.
- Sliders get no keybind chip - there is no second value for a key to jump to.
- Modules left in `EndsightDemo` as `unimplemented` are placeholders wired to nothing.
