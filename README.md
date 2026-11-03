# The Wake

> *A world folder was sent to us with one note: "I never wrote this."*

**The Wake** is a slow, quiet horror mod for **Minecraft Java 1.21.1**, for **NeoForge** and
**Fabric**. It adds no mobs, blocks or items. It works through things you already do every night
without thinking about them.

Every night you sleep through is skipped. Someone has to stay up for it.

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
normally would. Singleplayer is the intended experience. Headphones help.

## Content notes

- No gore, no jumpscare screams, no flashing. Its loud moments are quiet ones.
- It can move you, change the time of day in singleplayer, make you hungrier, and stop you from
  sleeping sometimes.
- It never deletes your blocks or items. The only world edits are optional (`worldEdits`): it
  places a torch into empty air, ripens crops, closes doors and moves furnace output into your
  chest.
- Everything "meta" stays inside the game: no files outside the game folder, no fake crashes, no
  window-title tricks. It notices real lag spikes but never fakes one.

## Config

`config/thewake.json` is created on first launch. Every option is explained inside the file.

| Option | Default | What it does |
|---|---|---|
| `pace` | `1.0` | Speed of the whole arc. `2.0` is twice as fast. |
| `quietNights` | `1` | Nights you can sleep through before anything happens. |
| `pauseEvents` | `true` | Singleplayer: long pauses count, and the pause menu is not entirely safe. |
| `afkEvents`, `afkSeconds` | `true`, `150` | Standing idle counts as lost time. |
| `hitchEvents` | `true` | Real frame stalls over one second count (capped per day). |
| `blinkEvents` | `true` | Late game: short blackouts in daylight. Never in hardcore. |
| `stolenMornings` | `true` | Late game: sometimes you do not wake where you went to sleep. Never in hardcore. |
| `worldEdits` | `true` | The small Act I chores near your bed. |
| `finale` | `true` | The finale. |
| `finaleOnServers` | `false` | Allow the finale on multiplayer servers (see below). |
| `nightSkyShader` | `true` | Client: the custom shader. Turned off automatically while an Iris/Oculus shader pack is active. |
| `soundVolume` | `1.0` | Client: volume of this mod's own sounds. |
| `dimensions` | `["minecraft:overworld"]` | Where it can reach you. |

## Commands

All need permission level 2 (cheats on in singleplayer):

- `/thewake status [player]`: how far along a player is.
- `/thewake stage <player> <stage>`: jump to an act (`night_shift`, `exposure`, `sightings`,
  `routine`, `theft`, `likeness`, `stopped`).
- `/thewake beat <player> <beat>`: play one beat right now (`sighting`, `residue`, `headturn`,
  `ledger`, `pause`, `stopped`, …). This is meant for testing and spoils things.
- `/thewake reset <player>`

## Multiplayer

Each player has their own haunting, and nobody else can see it. On servers the finale is off by
default, because it freezes the world for one player while the server keeps running for everyone
else. Turn on `finaleOnServers` to allow it. Only players who actually sleep through a night are
affected by that night.

## Compatibility

- **Shader packs (Iris/Oculus):** the custom night-sky shader is swapped for a texture fallback
  while a pack is active.
- **Sleep mods** that speed nights up instead of skipping them still count.
- **Other horror mods** work alongside it. It adds no entities, so it does not collide with
  their spawns.

## Building from source

```bash
./gradlew build
```

The jars end up in `fabric/build/libs/` and `neoforge/build/libs/`. Run a test client with
`./gradlew :fabric:runClient` or `./gradlew :neoforge:runClient`.

A scripted visual demo stages every beat in order and saves screenshots to the run folder. It
needs a singleplayer world called `demo`:

```bash
./gradlew :fabric:runClient -PwakeDemo
```

The sounds and textures are original and synthesized. `tools/generate_assets.py` rebuilds them
(it needs numpy, Pillow and ffmpeg with libvorbis).

<details>
<summary><b>How it works (spoilers)</b></summary>

When you sleep, Minecraft deletes the night. When you pause, the world stops. When you go AFK,
or your game hitches, time passes without you in it. **The Wake is whoever was there instead.**
It looks like your own silhouette cut out of the night sky, and you only ever see the *image* it
left behind, like a long-exposure photograph: smeared where it moved, solid where it stood a
long time.

- **Act I.** Small chores happen overnight, but only after nights in a familiar bed. You wake up
  hungrier.
- **Act II.** You see afterimages when you wake. A ledger book appears on your bed, written in
  your name. Sleep stops resetting *Time Since Last Rest*, so phantoms come even though you sleep.
- **Acts III–IV.** Sightings appear after lost time. The bed refuses you ("there are monsters
  nearby"). Clocks stop near it. Some mornings you wake somewhere else. Nights you stayed awake
  for get collected later, in daylight.
- **Act V.** Silence, then one thing that breaks the rules.
- **The Stopped.** The finale. Every place you ever walked becomes a ribbon of night, and it
  follows them.

Counterplay: staying awake through a whole night pushes it back, and sleeping somewhere new
slows it down. There are two endings.

The full reasoning, including the independent critique that reshaped it, is in
[`docs/DESIGN.md`](docs/DESIGN.md).

</details>

## License

MIT. See [LICENSE](LICENSE).
