<p align="center"><img src="docs/media/icon.png" width="128" alt="The Wake"></p>

# The Wake

> *A world folder was sent to us with one note: "I never wrote this."*

**The Wake** is a slow horror mod for **Minecraft Java 1.21.1**, for **NeoForge** and **Fabric**.
It adds no mobs, blocks or items. It works through things you already do without thinking about
them: sleeping, pausing, opening your inventory.

Something in your world only moves while you are not watching it.

---

## Install

| | NeoForge | Fabric |
|---|---|---|
| Minecraft | 1.21.1 | 1.21.1 |
| Loader | NeoForge 21.1.265 or newer | Fabric Loader 0.16.14 or newer + **Fabric API** |
| Java | 21 | 21 |

Drop the jar for your loader into `mods/`. The haunting needs it **on both the client and the
server**. A client with the mod can still join servers without it (nothing happens there), and
the other way round.

How to play it: start (or keep playing) an ordinary survival world and live in it the way you
normally would. It takes a few in-game days to begin. Singleplayer is the intended experience.
Headphones help.

## Content notes

- **One jump scare**, from the fourth act on: a face filling the screen, a slow blink, a jaw that
  keeps opening, a picture tear and a short distorted scream. Only if you stay in a menu with it
  at arm's length, never in your sleep or through the pause screen, and not again for a few
  minutes after. The scream has its own volume setting (`screamVolume`), and the tape tear can be
  turned off with everything else visual (`tapeEffect`).
- A tall, flat-black stick figure like a stop-motion puppet, with pale long-fingered hands sewn on
  at the wrists. Its head is an old index card cut to the shape of a face that is too long, a real
  face pressed into it from behind, wet eyes looking through holes torn in it, its chin a
  ventriloquist's dummy's held on with stitches. Some blood, bloodshot eyes, teeth. No gore.
- The picture is made to look like worn videotape at times: grain, scanlines, rolling tracking
  noise. No strobing.
- It can damage you (default six hearts, through armour). In hardcore it leaves you at half a
  heart instead of killing you.
- It can move you, change the time of day in singleplayer, make you hungrier, stop you sleeping,
  and open a door. It never breaks blocks or takes items. Optional world edits (`worldEdits`):
  a placed torch, ripened crops, closed doors, furnace output moved into your chest, and, a few
  times in a whole haunting, a new name on the thing in your hand.
- Everything "meta" stays inside the game: no files outside the game folder, no fake crashes, no
  window-title tricks. It notices real lag spikes but never fakes one.

## Config

`config/thewake.json` is created on first launch. Every option is explained inside the file.

| Option | Default | What it does |
|---|---|---|
| `pace` | `1.0` | Speed of the whole arc. `2.0` is twice as fast. |
| `quietNights` | `1` | Nights you can sleep through before anything happens. |
| `menuHunts` | `true` | It moves while you are in a menu (inventory, chests, crafting, chat), within 48 blocks of your bed. |
| `pauseEvents` | `true` | The pause menu counts too, while the game window has focus. Alt-tabbing never does. |
| `afkEvents`, `afkSeconds` | `true`, `150` | Standing idle is a lapse. |
| `hitchEvents` | `true` | Real frame stalls over one second count (capped per day). |
| `attacks` | `true` | From Act IV it bites if you stay in a menu with it at arm's length. |
| `attackDamage` | `12.0` | In half-hearts. Armour does not help. |
| `attackHardcoreKills` | `false` | Hardcore: off leaves you at half a heart. |
| `blinkEvents` | `true` | Late game: short blackouts in daylight. Never in hardcore. |
| `stolenMornings` | `true` | Late game: sometimes you do not wake where you went to sleep. Never in hardcore. |
| `worldEdits` | `true` | The small Act I chores near your bed, and the door it leaves open. |
| `finale` | `true` | The finale. |
| `finaleOnServers` | `false` | Allow the finale on multiplayer servers (see below). |
| `tapeEffect` | `true` | Client: the VHS look. |
| `nightSkyShader` | `true` | Client: the finale's ground shader. Turned off automatically while an Iris/Oculus shader pack is active. |
| `soundVolume` | `1.0` | Client: volume of this mod's own sounds. |
| `screamVolume` | `0.8` | Client: volume of the one loud sound. |
| `dimensions` | `["minecraft:overworld"]` | Where it can reach you. |

## Commands

All need permission level 2 (cheats on in singleplayer):

- `/thewake status [player]`: how far along a player is.
- `/thewake stage <player> <stage>`: jump to an act (`night_shift`, `watching`, `sightings`,
  `routine`, `hunt`, `hush`, `stopped`).
- `/thewake beat <player> <beat>`: play one beat right now (`sighting`, `giant`, `morning`,
  `hunt`, `attack`, `headturn`, `ledger`, `stopped`, …). Meant for testing; it spoils things.
- `/thewake reset <player>`

## Multiplayer

Each player has their own haunting, and nobody else can see it: it is not an entity. On a server
the pause menu does not stop the world, so it counts like any other menu. The finale is off by
default on servers, because it freezes the world for one player while the server keeps running
for everyone else; turn on `finaleOnServers` to allow it.

## Compatibility

- **Shader packs (Iris/Oculus):** the creature is drawn with vanilla's entity shader, so packs
  treat it like any entity. The finale's ground switches to a texture while a pack is active.
- **Sleep mods** that speed nights up instead of skipping them still count.
- **Other horror mods** work alongside it. It adds no entities, so it does not collide with
  their spawns.

## Building from source

```bash
./gradlew build
```

The jars end up in `fabric/build/libs/` and `neoforge/build/libs/`. Run a test client with
`./gradlew :fabric:runClient` or `./gradlew :neoforge:runClient`.

A scripted visual demo stages every beat in order and saves screenshots to
`<loader>/runs/client/screenshots`. It needs a singleplayer world called `demo` in that run
folder:

```bash
./gradlew :fabric:runClient -PwakeDemo
```

The creature was designed as pixel art first (`tools/concepts/`, sheets in
[`docs/concepts/`](docs/concepts/)) and is built procedurally in headless Blender, with no GUI and
no MCP server: Blender as a Python module. Its head is drawn by `tools/creature/card.py` from the
same numbers as the pixel art; its skeleton, poses and stop-motion clips are in
`tools/creature/poses.py`.

```bash
pip install bpy==5.2.3 numpy pillow
```

```bash
python tools/creature/build_creature.py --previews
```

That writes the model (`models/creature/creature.bin.gz`: a skeleton, skin weights, rigid parts and
baked clips, format in `tools/creature/export_v3.py`), its two textures and Cycles preview renders.
The game skins it on the CPU with dual quaternions, the same as Blender, checked on every build. The sounds and small textures are synthesized by
`tools/generate_assets.py` (numpy, Pillow, ffmpeg with libvorbis).

<details>
<summary><b>Screenshots (spoilers)</b></summary>

![The creature: a black stick figure like a stop-motion puppet, with pale long-fingered hands; its head an index card cut to a face, real eyes behind holes torn in it, pins at the corners, the chin sewn on like a dummy's](docs/media/creature.jpg)

*The model, rendered in Blender by `tools/creature/build_creature.py`.*

![Five versions of the same index-card face drawn as pixel art, each one a little worse: lids heavy, then open, then the eyes not quite on you, a smile, bleeding](docs/media/concept-faces.jpg)

*It was designed as pixel art first. These are its replacement faces, like a stop-motion puppet's; the other sheets are in [`docs/concepts/`](docs/concepts/).*

Captured in game by the scripted demo:

| | |
|---|---|
| ![Daylight: the figure standing at the left edge of the view](docs/media/corner-of-eye.jpg) | ![The card face turned toward you over a wooden wall](docs/media/turned.jpg) |
| *At the edge of your view. Look straight at it and it is still.* | *Look away and back, and it is facing you.* |
| ![The inventory screen; to its left, the creature walking in, its card face at the top of the screen](docs/media/inventory.jpg) | ![The pause menu over a blurred, noisy picture, PAUSE in the corner, the creature sharp at the side](docs/media/pause-tape.jpg) |
| *While you sort your inventory, it walks in, a few drawings a second.* | *The pause menu is a paused tape. It is still coming.* |
| ![The card face filling the screen, torn by tape damage, the dummy's chin dropped into a dark mouth of teeth](docs/media/attack.jpg) | ![A dark wooden room: the black figure lying in a red bed, its legs far past the foot of it](docs/media/in-bed.jpg) |
| *If you stay in the menu with it at arm's length.* | *The finale. It is in your bed.* |

</details>

<details>
<summary><b>How it works (spoilers)</b></summary>

When you sleep, Minecraft deletes the night. When you pause, the world stops. When you open a
chest, you stop looking. **The Wake is whatever uses that time.** It is a black puppet of a thing
with an index card for a face and real eyes behind it, and it never moves while you watch the
world: only while you are in a menu, asleep, or away from the keyboard. Every step it takes is a
frame of stop-motion: behind your inventory you can watch it come, a few drawings a second.

- **Act I.** Small chores happen overnight, but only after nights in a familiar bed. You wake up
  hungrier.
- **Act II.** After lost time, sometimes, far away: four times its size, over the trees, facing
  your house. A ledger appears on your bed, written in your name.
- **Act III.** It comes closer while you are in menus near home, out of the dark, but keeps its
  distance. At the edge of your view it is wearing a different face; look straight at it and it
  is back to the first one, almost. Doors cost it time, and you hear the latch. The pause menu is
  a paused tape. The mornings become a film shot one frame a night: in each room it is a step
  nearer your pillow for every night you slept there.
- **Act IV.** It comes all the way, and stops at arm's length. If you stay in the menu, or open
  one with it already that close, it bites.
- **Act V.** One silent day, then one thing that breaks the rules.
- **The Stopped.** The finale. The world freezes like a paused tape, every place you ever walked
  is burned into it as static, and for once it moves while you watch.

Counterplay:

- **Keep the night.** Staying awake through a whole night pushes it back, and its film back a
  frame.
- **Sleep somewhere new.** A new bed slows it down and starts a new film.
- **Light.** It only ever comes out of the dark, and a torch at its feet makes it not be there.
- **Iron doors** stop it.
- **Walk into it** and it goes away, for an hour of your time. Then two, then three.

There are two endings.

The full reasoning, including the redesign that turned it into this, is in
[`docs/DESIGN.md`](docs/DESIGN.md).

</details>

## License

MIT. See [LICENSE](LICENSE).
