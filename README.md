<h1 align="center">Endsight</h1>

<p align="center">
  <b>Trackers, storage and dungeon tools for Dragsim, all in one clean menu.</b>
</p>

<p align="center">
  <a href="https://github.com/RealZones/Endsight/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/RealZones/Endsight?style=for-the-badge&color=8b5cf6&label=release"></a>
  <a href="https://modrinth.com/mod/endsight"><img alt="Modrinth" src="https://img.shields.io/badge/modrinth-endsight-1bd96a?style=for-the-badge&logo=modrinth&logoColor=white"></a>
  <a href="https://discord.gg/jSQDjqfZfV"><img alt="Discord" src="https://img.shields.io/badge/discord-join-5865f2?style=for-the-badge&logo=discord&logoColor=white"></a>
  <img alt="Minecraft 26.1.2" src="https://img.shields.io/badge/minecraft-26.1.2-3a3a45?style=for-the-badge">
</p>

<p align="center">
  <img src="docs/menu.png" width="860" alt="The Endsight menu open on the Inventory page">
</p>

<br>

## Install

You need **Minecraft 26.1.2**, **Fabric Loader 0.19.1+**, **Fabric API** and **Java 25**.

1. Download one jar from the [latest release](https://github.com/RealZones/Endsight/releases/latest).
2. Put it in your `mods` folder next to Fabric API.
3. Join Dragsim and press **Right Shift** to open the menu.

| Jar | What it does |
|---|---|
| `endsight-autoupdate-<version>.jar` | Updates itself when you launch the game. |
| `endsight-<version>.jar` | Never updates itself. You download each new version. |

Endsight is on [Modrinth](https://modrinth.com/mod/endsight) too.

<br>

## A look inside

<h3 align="center">Every storage page on one screen</h3>
<p align="center">
  Click an item on any page and it opens and picks that item up, shift click included.
</p>
<p align="center">
  <img src="docs/storage.png" width="860" alt="All storage pages at once, with your inventory underneath">
</p>

<br>

<table>
  <tr>
    <td colspan="2" width="100%" valign="top">
      <img src="docs/profile.png" width="100%" alt="A player's profile: net worth, gear, skills and slayers">
      <h3>Profile viewer</h3>
      <code>/pv &lt;player&gt;</code> shows anyone's net worth, gear, pets, storage, progress and bestiary.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/boss-drops.png" width="100%" alt="The Boss Drops readout during a Superior Dragon fight">
      <h3>Boss Drops</h3>
      Every dragon, Warden and Protector drop you got, rarest first, for the session or all time.
    </td>
    <td width="50%" valign="top">
      <img src="docs/mining.png" width="100%" alt="Mining Session, powder, Forge timers and the drill line">
      <h3>Mining</h3>
      Profit an hour, live powder, Forge timers, and your drill's fuel and cooldown.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <img src="docs/voidgloom.png" width="100%" alt="The Voidgloom boss and its Yang Glyph boxed, with hits left on screen">
      <h3>Voidgloom Helper</h3>
      Shield hits left, big on screen, with a box and tracer on your boss and the Yang Glyph.
    </td>
    <td width="50%" valign="top">
      <img src="docs/dungeon-map.png" width="100%" alt="The dungeon map with named rooms">
      <h3>Dungeons</h3>
      A live room map, key alerts, room puzzle solvers and terminal hints.
    </td>
  </tr>
</table>

<br>

## Everything it does

Click a section to open it.

<details>
<summary><b>Slayers</b></summary>
<br>

- **Slayer Tracker:** kills, average kill time, kills an hour, drops and profit.
- **Voidgloom Helper:** shield hits, boss health and phase cues.
- **Spawn markers:** where your boss will spawn, and the Revenant tiles in the Crypts.
- **Quest alert:** a popup and a link when you fight in a slayer area with no quest.

</details>

<details>
<summary><b>The End</b></summary>
<br>

- **Zealot Tracker:** kills and drops, with session profit and profit an hour.
- **Boss Drops:** your dragon, Warden and Protector loot. `/drops` prints it in chat.
- **Dragon tools:** dragon timer, dragon hitboxes and protection for placed eyes.
- **Boss indicators:** Golem stage, kills to its spawn, and beams over the Protector and Warden.
- **Goop boss:** countdowns to its next form, plus your fights, loot and profit an hour.
- **Cache Tracker:** Crypt Caches opened, every reward, and what each one made you.
- **End nodes:** an outline on confirmed shiny End nodes.
- **Golden Dragon math:** answers its sum for you.

</details>

<details>
<summary><b>Dungeons</b></summary>
<br>

- **Dungeon Map:** a live map of the rooms.
- **Key alerts:** tells you when a key drops.
- **Room puzzles:** Three Weirdos, Tic Tac Toe, Quiz, Higher or Lower and Ice Path.
- **Blood Room:** a timer on screen.
- **Terminal Solvers:** compact menu hints and Simon Says guidance.

</details>

<details>
<summary><b>Mining</b></summary>
<br>

- **Mining Session:** what you are mining, rate, profit and active time. Pauses when you stop.
- **Powder Tracker:** Ender and Void powder live, with session gain and perks you can afford.
- **Mining Spread:** reads your stat and learns the extra blocks you mine.
- **Forge Utils:** timers, ready slot outlines, recent crafts and missing materials.
- **Drill line:** ability cooldown and fuel, read off the drill in your hand.
- **Menu Buttons:** a Heart of the Dragon button in the Dragon Sim Menu.

</details>

<details>
<summary><b>Inventory</b></summary>
<br>

- **Storage:** every page on one screen, with search and live item moving.
- **Storage Preview:** hover a page to see inside it.
- **Recipes:** every recipe and Forge craft beside your inventory. **R** shows a recipe, **U** shows its uses.
- **Rarity Outlines:** rarity colour around every item, in menus and on the hotbar.
- **Glass Inventory:** see-through menus with an opacity control.
- **Item protection:** slot locking, and no armor swaps by accident.

</details>

<details>
<summary><b>Chat</b></summary>
<br>

- **Loot rolls:** your `/debug` rolls, compact, with close calls and real drops kept.
- **Ability Spam:** one cooldown line with a count, or none.
- **Goop cleanup:** hides Goop boss chat.
- **Copy Chat:** right click a line to copy it.
- **Global chat on join:** a lobby switch no longer leaves you in instance chat.

</details>

<details>
<summary><b>General</b></summary>
<br>

- **Alerts:** bosses, fireballs, full inventory, stash, Goop Bait and drops you care about.
- **Player HUD:** armor, pet, ability cooldowns, and FPS, ping and TPS.
- **Perspective Mode:** third person where the mouse moves the camera, not you.
- **World:** Fullbright and an explosion cloud hider.
- **Damage Numbers:** shorten them, keep crits only, or hide them.
- **Auto Clicker:** hold left click to repeat attacks at a varying rate.
- **Etherwarp Outline:** boxes the block you will land on.
- **Command Binds:** a key that sends a command, like `/warp end` on F6.
- **Discord Presence:** shows Dragsim, your name, area and purse on Discord.

</details>

<br>

## Make it yours

- **Keys:** bind any key or mouse button from the Keybinds page. Right click a key to clear it.
- **Prices:** set what any drop is worth, and every profit readout follows.
- **HUD:** click Edit HUD to drag any readout, or drag it straight from chat. Scroll over one to resize it.
- **Themes:** seven palettes, and everything follows the one you pick.
- **Search:** the search box finds any feature or setting.

| Command | What it does |
|---|---|
| `/endsight` or `/es` | Opens the menu |
| `/pv <player>` | Opens a profile |
| `/drops` | Prints your Boss Drops in chat |
| `/cache` | Prints your Crypt Caches in chat |

<br>

## Discord

<table>
  <tr>
    <td width="60%" valign="top">
      <b><a href="https://discord.gg/jSQDjqfZfV">Join the Discord</a></b> for help, updates and the Endsight bot.
      <br><br>
      The bot does your forge maths: <code>/forge &lt;item&gt;</code> gives the whole build, from refined parts and gems to crafts and the time on your Quick Forge level.
      <br><br>
      If the autoupdate jar fails, it shows a code from E1 to E7 in chat. Send that code in the Discord.
    </td>
    <td width="40%" valign="top">
      <img src="docs/bot-forge.png" width="100%" alt="The /forge command showing a full build">
    </td>
  </tr>
</table>

<br>

<p align="center">
  Built by Fear · All rights reserved, see <a href="LICENSE">LICENSE</a>
</p>
