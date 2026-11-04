# The Wake — Design Document

> *"Every night you sleep through is skipped: seven minutes of world that nobody plays.
> Something has been playing them."*

Target: Minecraft Java 1.21.1, NeoForge 21.1 and Fabric (Fabric API), one shared codebase.

This document was produced in passes. Each pass is kept so the reasoning stays visible:
earlier passes are not edited to look smarter after the fact; later passes say what changed and why.

---

## Pass 1: Divergent concepts

Goal: generate many unrelated concepts, each anchored to a different vanilla system, and
score them honestly before falling in love with any one of them.

Research notes that shaped the scoring (Nov 2026 survey of Modrinth/CurseForge):

- "Something watches you sleep" is taken: *Eyeless Jack*, *Sleepless*, *Sweet Dreams*,
  *SleepBED*, *NoSleep* (Bedrock) all centre on beds, dreams or sleep paralysis.
- Skin-copying doppelgangers are taken: *Copycat*, *Horror Doppelganger*,
  *Ghost Doppelganger*, *From the Shadow*, *The Unnamed* ("observes, waits, learns").
- Time-stop exists only as a power fantasy (*Stasis Paradox*, *TimeStop*) or a random
  event (*Horror Prototems*).
- Nobody uses the **pause menu**, **AFK**, **lag**, or **the time between play sessions** as
  horror territory. Nobody renders a horror entity as a **photographic long exposure**.

Scores are 1–5. *Anchor* = the reason to exist only makes sense inside Minecraft.
*Look* = it looks wrong for Minecraft. *Depth* = it generates play, not one scare.

| # | Concept | Vanilla anchor | Scare | Anchor | Look | Original | Depth | Feasible | Total |
|---|---------|----------------|------:|-------:|-----:|---------:|------:|---------:|------:|
| 1 | **The Retracer**: something walks your exact historical path at walking pace, visible only on maps; when it reaches where you are now... | Maps, movement | 4 | 3 | 2 | 3 | 4 | 4 | 20 |
| 2 | **Death Echoes**: every death leaves you standing at the spot, frozen mid-death-tilt with the red hurt tint burned on; they step closer when unwatched | Death, respawn | 4 | 5 | 4 | 4 | 2 | 5 | 24 |
| 3 | **The Ninth Phase**: there are eight moon phases; tonight is the ninth | Moon phases | 2 | 4 | 3 | 3 | 2 | 4 | 18 |
| 4 | **Forward Compass**: the recovery compass points to where you *will* die, and the spot creeps toward your bed | Recovery compass | 4 | 5 | 2 | 4 | 3 | 5 | 23 |
| 5 | **The Painted Window**: a painting that is a live view of your house from the treeline, closer each time | Paintings | 5 | 3 | 5 | 5 | 2 | 1 | 21 |
| 6 | **Mob Cap**: animals and monsters stop spawning because the mob cap is full of something you can't see | Spawning | 3 | 5 | 1 | 4 | 2 | 5 | 20 |
| 7 | **Skipped Time**: something lives in the time Minecraft skips: slept-through nights, the paused game, AFK, lag, the days between sessions | Sleep, pause, tick rate | 5 | 5 | 4 | 5 | 5 | 3 | **27** |
| 8 | **Your Own Hand**: your first-person arm slowly becomes realistic | First-person model | 3 | 4 | 5 | 4 | 1 | 4 | 21 |

**Winner: #7.** It is the only concept where the anchor is a *category* of vanilla systems
rather than one item, so it can keep producing new scares from one coherent rule. It also
puts the player in a real dilemma, because vanilla already punishes the opposite choice:
**phantoms punish you for not sleeping. The Wake punishes you for sleeping.**

Fragments worth stealing from the losers:

- #1 → the entity *learns your routes*; in the finale it can only walk where you have walked.
- #2 → "a pose that should not hold" (the falling-asleep apparition, the head that turns).
- #5 → a view of yourself from outside (the ledger describes your day from outside).

---

## Pass 2: Expanding "Skipped Time"

### The one-sentence origin

When you sleep, Minecraft deletes the night. When you pause, the world stops. When you go
AFK, or the server lags, or you quit for three days, time passes without you in it.
**The Wake is whoever was there instead.**

The name carries three meanings and the design uses all of them:

1. **Awake.** It is the one who stays up while you sleep.
2. **A wake** is the trail a moving thing leaves behind. You never see the Wake move;
   you see its wake, the long-exposure smear of where it went while you were gone.
3. **A wake** is a vigil kept over someone lying still. That is what it does at your bed.

### Hook: the first minutes

Nothing. A normal survival world. You sleep the first nights like always.

Then one morning there is a **clock** lying at the foot of your bed. You did not craft it.
Your furnace finished smelting overnight (impossible in vanilla, because skipped nights do
not tick furnaces). The wheat by the door ripened while you slept. The door you left open is
closed. It reads like a cozy "night helper" feature. **This is the charm before the turn.**

### Cast

| Role | Who | Notes |
|------|-----|-------|
| The helper that turns | **The Wake**, early on | Does your chores in the night you skipped. Then it does *you*. |
| The threat | **The Wake**, later | A you-shaped hole in the world, full of night sky. Your silhouette, arm width, gait and routes. |
| The thing behind it | **The Night itself** | Only seen once, in the finale: the same silhouette on the horizon, larger than mountains. Its size is how much of your time you gave away. |
| The narrator | **The game** | Vanilla's own voice: the bed message, the Statistics screen, the clock needle, a written book in your name. No new UI. |

### Wrongness (how it looks wrong for Minecraft)

- **Rendering wrongness: a hole full of night.** The Wake is your player-model silhouette
  filled with a starfield that is fixed to *world* directions, like a window cut through the
  day into the night sky behind it. It is unlit, has no shadow and no outline. In daylight it
  is a crisp black cut-out with stars in it. **At night it is almost invisible: you notice it
  because there are stars in front of your wall.**
- **Temporal wrongness: long exposure.** You only ever see *exposures*, the image left behind
  where it stood while you were gone. Like an old photograph: a thing that moved is a smear
  of overlapping ghosts; a thing that stood still is sharp. **If you can see it clearly, it
  has been standing there a long time.**
- **Motion wrongness: time-lapse.** On the rare occasions it moves in your time, it moves like
  time-lapse footage with dropped frames: still, then suddenly further along, smeared.
- **Scale wrongness** (finale only): the Night, a mountain-sized silhouette on the horizon.
- **World wrongness** (finale only): the Stopped, the world as it is when time does not pass.
  Mobs frozen mid-stride, rain hanging in the air, water and fire frozen on one frame,
  the sky stuck, total silence apart from your footsteps.
- **Audio wrongness:** a recorded-sounding 3 a.m. bedroom (electrical hum, a far clock, slow
  breathing) leaking into a game whose palette is soft synths. Vanilla sounds in the wrong
  place: your own footsteps on the floor upstairs, your chest opening at home while you are
  in the mine, *your own hurt sound* from the next room.
- **Meta wrongness, all reversible:** the pause menu is not safe; lag that is not lag;
  the game uses the real hours you were away.

### Rules the player learns (and how they break)

1. **It only moves when you lose time.** Sleep, pause, AFK, lag, logging off, leaving home.
2. **Sharp means it stood there a long time.** Blurred means it was moving.
3. **Clocks know.** A clock's needle spins near it: in your hand, in an item frame, dropped.
   Players can build clock walls as alarms.
4. **The bed knows.** *"You may not rest now; there are monsters nearby"* with nothing
   around. The game's own monster check found it.
5. **Keeping the night pushes it back.** Stay awake dusk to dawn and it loses ground.
   This costs you phantoms and a night of mobs.
6. **Routine feeds it.** Sleeping in the same bed every night teaches it faster than
   sleeping somewhere new.

**Where the rules break:**

- Rule 1 breaks when it starts **taking time itself**: frame-freezing "lag" spikes, and
  "blinks" where the screen cuts out and you come back a minute later somewhere along your
  usual route, hungrier, holding something else.
- Rule 2 breaks exactly once: a perfectly sharp exposure, standing over your bed when you
  wake, **turns its head toward you**.

### Arc

Progress is one hidden number: **clarity** (0 to 1), how complete its picture of you is.
It is also visible: exposures get sharper as it grows. **The progress bar is the monster's
focus.**

| Clarity | Act | What starts happening |
|--------:|-----|-----------------------|
| 0.00 | Quiet | Nothing at all for the first nights (configurable). |
| 0.06 | I: Night Shift | Kindness after skips: the clock gift, finished furnaces, ripened crops, doors closed, a torch where you stumbled. |
| 0.15 | II: Exposure | On waking, for a few seconds, a blurred smear of stars along the path it walked through your house. Clocks spin after waking. The first **ledger**: a written book on your bed, authored by you. |
| 0.30 | II | **Sightings** after lapses: back from the pause menu or AFK, an exposure stands somewhere in view, perfectly still. It does not leave while you are present. It only leaves at your next lapse. Bed refusals. Pets stare at it. Sounds of you around the house. |
| 0.45 | III: Routine | Waking residue follows *your* routes from yesterday. A **second bed** appears beside yours. Tally marks on signs count the hours you were logged off. The pause-menu figure. As the screen darkens when you fall asleep, it is at the foot of the bed. |
| 0.65 | IV: Theft | Lag spikes and blinks. Some nights you are not allowed to skip: the screen stays black and you hear it do your night. Doors you closed are open in the morning. |
| 0.85 | IV | The head turn. Once. |
| 0.95 | V: The Stopped | The next time you sleep, you wake up in the night it lives in. |

### The ledger

A written book left on your bed, authored with your name, in the style of the vanilla
Statistics screen:

```
Day 14                 Night 14
Distance Walked 1204   Distance Walked 1204
Stone Mined 311        Stone Mined 311
Bread Eaten 3          Bread Eaten 0
Times Slept 1          Times Slept 0
                       Time Stood By The Bed 6:52
```

The night column copies your day, because it relived your day while you slept. Then it stood
by the bed. Later ledgers stop pretending: walked 0, stood 9:00. Later still: *Lay Down 9:00*.

The same numbers live in the real **Statistics** screen: *Nights Skipped*, *Nights Kept*,
*Distance Sleepwalked*. They are there from the first day, reading zero, for anyone curious.

### The Stopped (finale)

You lie down. The screen fades. You wake, but nobody else in the world is moving.

- Everything is frozen: a zombie mid-stride outside the door, rain hanging, the fire in the
  furnace stuck on one frame, the water still. No sound except your footsteps.
- **The Wake is in your bed**, a sharp you-shaped hole of night lying under your blanket.
- On the horizon, the Night: the same silhouette, larger than the mountains, not moving.
- After a few seconds the Wake sits up, the vanilla wake-up motion, and starts walking
  **your routes**: to the furnace, to the chest, to the mine. It can only walk where you have
  walked. If you are on your paths, it comes for you.
- **Escape:** sleep somewhere you have never been. Your bed is empty now but it is on your
  paths: *"You may not rest now; there are monsters nearby."* Break it, carry it off your
  routes, place it, lie down. While you fall asleep it stands at the edge of your known
  world, watching, sharpening. Then morning, colour, sound.
- Over time it learns to step off your paths, so hiding forever does not work.
- **Caught:** a black cut, and you wake in your old bed on the evening of the *next* day.
  It lived your day. A ledger written in first person sits on the bed. Clarity stays high,
  so the next sleep puts you back in the Stopped.
- **After escaping:** it is over. Clarity resets, your routes are forgotten. One thing
  follows you: if you ever go back to where your old bed was, an exposure is standing there.
  If you ever sleep at that spot again, it starts over from halfway.

### Counterplay summary

| Action | Effect | Cost |
|--------|--------|------|
| Keep a night (awake dusk to dawn in the overworld) | Large clarity loss | Phantoms, a night of mobs |
| Sleep somewhere new | Half the clarity gain | Nomad life, bed logistics |
| Don't pause, don't go AFK | No lapse gains | Tension. Alt-tabbing pauses too. |
| Hold or hang clocks | Warning before thefts, detection | Gold |
| Escape the Stopped | Ends it | You have to survive the Stopped |

### Audio plan

All custom sounds are original, synthesized for this mod, normalized well below vanilla mob
volume. Fear comes from proximity and silence, not loudness. No stingers.

| Sound | Use | Loudness |
|-------|-----|----------|
| `room_tone` | 3 a.m. room: hum, distant clock, slow breath; near exposures | very quiet, 12-block falloff |
| `shutter` | an exposure dissolving | soft click |
| `breath` | falling-asleep apparition, black-screen night | close, quiet |
| `tick_stop` / `tick_start` | entering and leaving the Stopped | quiet |
| `silence` | the Stopped's near-silent ring | barely there |
| vanilla, misplaced | footsteps, chests, doors, eating, your hurt sound | vanilla levels at distance |

### Config and safety

- Pace multiplier, safe period, and per-feature switches (pause, lag, blinks, world edits,
  finale). Meta events are in-game only: no files outside the game, no real crashes, no
  window title edits, nothing irreversible.
- World edits only ever **place into air** or move items between containers. They never
  destroy builds. They can all be disabled.
- No flashing. Cuts are fades.
- Multiplayer: each player has their own Wake and clarity. The pause figure is singleplayer
  only. The Stopped in singleplayer uses the vanilla tick-freeze; on servers it is a per-player
  client-side freeze with server protections, so nobody else is frozen.

### Pitch (mod page)

> *A world folder was sent to us with one note: "I never wrote this." Inside, on the bed,
> a written book called* Night 31*, authored by the world's only player. Every line was
> correct.*
>
> **The Wake.** Every night you sleep through is skipped. Someone has to stay up for it.

---

## Pass 3: Adversarial critique

An independent reviewer, given only this document and the horror-design principles, was asked
to break it: weakest beats, telegraphing, cheese, pacing, multiplayer risk, and whether the
finale can be solved without text. Its verdict, condensed:

**Protect:** the world-fixed starfield that disappears against the real night sky, vanilla's own
*"You may not rest now; there are monsters nearby"*, the ledger authored in your name, and coming
back from AFK to a perfectly sharp figure.

**What would not scare anyone**

- Act I is invisible (most players already believe furnaces tick overnight) and borrowed (bed
  gifts and "helper turns" are known tropes).
- Five separate bed beats (foot-of-bed apparition, second bed, *Lay Down*, head turn over the bed,
  the Wake in your bed) is the sleep-watcher genre the research already called taken.
- A head turn is what every vanilla mob does; it reads as look-at-player AI.
- An exposure is a statue: nothing defines what happens when you walk into it.
- Tally-mark signs, footsteps upstairs and opened doors are creepypasta stock.
- The Night on the horizon is a static giant.
- "Leaving home" is the Weeping-Angel *unobserved* rule, not lost time.

**What gives it away**

- The pitch states the rule. A *Distance Sleepwalked* stat names the mechanism on day one.
- A smear every morning and a spinning clock every wake become wallpaper by day three.
- `tick_stop` is a stinger.

**What players will cheese**

- Never sleeping switches the mod off; with a roofed base, keeping the night costs nothing.
- Two beds three blocks apart defeat "a new bed".
- Every alt-tab to the wiki feeds it; F3+P opts out of every pause beat.
- Four gold buys a permanent radar (the spinning clock).
- The finale's "never been" is invisible, quitting or dying inside it is undefined, and a frozen
  world you can still walk around in looks exactly like an integrated-server hang.

**Pacing:** illegible first hour, then everything at once for a player who sleeps in one bed and
alt-tabs. No numbers. Targets proposed: a noticeable beat by 45 minutes, first sighting around
2.5 h, head turn around 6 h, the Stopped around 7 h; acts one-way; a cap per in-game day.

**Multiplayer and compatibility:** as a real entity it shows on minimap radar, gets a Jade
nameplate, appears in F3, glows from spectral arrows and dies to `/kill @e`. A client-side freeze
over a live server means real mobs and rubber-banding. Server lag hits every player at once.
Sleep mods that fast-forward instead of skipping would bypass the hook. Shader packs may render
the starfield flat or black.

**Its top changes, ranked by fear:** draw the wake (your paths as ribbons of night in the
finale); make the Stopped inevitable; touching an exposure costs time; steal inside the sleep
fade (wake up where it went); schedule silence; separate the photographic signals (opacity,
smear and focus mean different things); count real hitches instead of faking lag; never make it
an entity.

---

## Pass 4: Revision

Every point was weighed against the one measure: does it make the player more afraid, without
making the game worse to live in. Most were adopted. Where the reviewer's fix was not taken, the
reason is given.

### Cut

| Cut | Why |
|-----|-----|
| Foot-of-bed apparition, second bed | Bed saturation. The bed keeps three beats: the refusal, the bedside image, and the finale. |
| Tally marks, phantom footsteps / chests / doors, doors opening overnight | Stock. One misplaced sound survives: **your own hurt sound from the next room**. |
| The Night on the horizon | Replaced by something that teaches the finale's rule: the ribbons. |
| Homecoming lapse | It was the unobserved rule in disguise. Lost time only. |
| Fake lag spikes, black-screen "kept awake" nights | Fake lag reads as bad optimisation; black screens with no cap are a chore. |
| Real-time absence as a clarity source | It punishes weekend players. Logging in is still a lapse, but a small one. |
| Custom statistics, `tick_stop` / `tick_start`, the clock gift on the bed | They name the mechanism, telegraph, or reuse a trope. |

### Changed

- **The Wake is not an entity.** The server keeps a private list of images per player and sends
  them only to their owner, who renders them client-side. No radar dot, no nameplate, no F3 count,
  no `/kill`, nothing other players can see.
- **Three photographic signals, three meanings.** *Opacity* = how long it stood there. *Smear* =
  it was moving. *Focus* = how well it knows you (clarity). They no longer overlap.
- **The head turn** is a solid, in-focus figure whose head alone smears into a double image as it
  turns, and keeps turning past where a neck stops. It comes after three silent in-game days.
- **Touching an exposure costs time.** Your view fills with night; you step out an hour later,
  hungrier, and it is gone.
- **The pause figure** is the one thing *not* blurred behind 1.21's blurred pause menu. Gone when
  you resume. Once.
- **Clocks stop** near it, at the minute you last lay down, instead of spinning. A stopped clock
  is easy to miss and impossible to forget. Before a theft, the clock in your hand stops.
- **Real hitches count.** Any real frame stall over a second (measured per client, so server lag
  does not hit everyone) is a lapse. Capped per day. Sometimes it ends with something in view.
- **Vanilla statistics, corrupted.** *Distance Walked* rises while you sleep. From Act II,
  sleeping no longer resets *Time Since Last Rest*, so **phantoms come even though you sleep every
  night**, and the Statistics screen says you have not rested in fourteen days.
- **Act I is a bribe.** Help only follows nights in a familiar bed: furnace output turns up in
  your chest, crops ripen, doors close, and you wake hungrier. It rewards the routine that feeds it.
- **"New" means 32 blocks** from every earlier sleep spot, so two beds side by side do nothing.
- **Kept nights have diminishing returns and a debt.** Each consecutive kept night pushes it back
  less, and every kept night is owed: later, in daylight, it takes that time back as a **blink**:
  the screen cuts out and you come back further along your usual route.
- **Stolen mornings.** From Act IV, some mornings you wake where it went instead of in bed: at the
  end of your strip mine, facing the stone, in the dark.
- **Teaching the finale early.** After a night in a new bed, the ledger reads a long Distance
  Walked and *Time Stood By The Bed 0 h 00 m*: it searched and could not find you.
- **Silence is scheduled.** At most one beat per lapse; at least a third of lapses produce nothing;
  each act retires the last act's beats; at most five ledgers; the pause figure and head turn
  happen once.
- **Sleep mods.** Any night that passes while the player is asleep counts, not just vanilla's skip.

### The Stopped, revised

- **Draw the wake.** In the Stopped, everywhere you have ever walked is a ribbon of night lying on
  the ground, denser where worn. The Wake can only walk on ribbons. **Your own steps lay a faint new
  ribbon behind you, and it follows that too.** Running is not hiding; it is a race. A fresh tunnel
  is a way out.
- **It moves like you.** Smoothly, with the vanilla walk cycle and footsteps that sound like yours.
  The first time it moves in your time, it moves like you. Pausing moves it forward by the real
  time that passed.
- **Your bed is occupied** (vanilla's own message) while it lies in it. When it gets up, the bed is
  on the ribbons: *"You may not rest now; there are monsters nearby."*
- **Escape:** a bed at least 16 blocks from every old ribbon, and asleep before it arrives.
- **Inevitable:** at full clarity the Stopped begins at the next sleep *or* the next login.
  Quitting or dying inside it counts as being caught.
- **Caught:** you are laid down in your bed, the screen fades, and you wake on the evening of the
  next day it lived for you. Each catch starts it closer. **The third catch is an ending: it keeps
  your bed.** It lies in it, for good, in your colours of night, and the haunting stops.
- **Escaped:** the other ending. It forgets your routes. One image stays where your old bed was.
  Sleep there again and it starts over from halfway.

### Not taken

- *Never use vanilla tick-freeze.* Kept for singleplayer, because it is the only way to freeze
  mobs, projectiles, fluids and time without desync. The reviewer's specific problems are fixed
  directly: beds broken in the Stopped go straight to your inventory (frozen pickup delay), riders
  are dismounted, music and rain are stopped client-side, and quitting counts as caught so the
  freeze never needs saving.
- *Make the Stopped singleplayer-only.* Instead it is **opt-in on servers** (`finaleOnServers`,
  off by default) using a per-player client-side freeze with server-side protection. Off, server
  players plateau in Act V with the head turn as the last beat.

### Pacing targets (default pace 1.0)

A typical player who sleeps most nights in the same bed, with a little AFK and pausing:

| Beat | In-game nights | Play time |
|------|---------------:|----------:|
| First Act I chore | 2–3 | ~45 min |
| First smeared waking residue | ~4 | ~1.3 h |
| First sighting after a lapse | ~8 | ~2.5 h |
| Ribbon-learning ledger, pause figure window | ~10 | ~3.3 h |
| Stolen mornings, blinks collect debts | ~13 | ~4.3 h |
| Head turn (after three silent days) | ~19 | ~6.3 h |
| The Stopped | ~20 | ~6.7 h |

Clarity per skipped night is 0.038 × familiarity (0.5 for a new place, 1.0 after three nights
within 32 blocks). Repeating yesterday's routes adds 0.008 a day. Total gain is capped at 0.07 per
in-game day. A kept night removes 0.06 × 0.7^n for the n-th consecutive kept night.

---

## Pass 5: Technical design

How each part of the revised design is actually built, and why that way.

### Shape of the code

One codebase, three Gradle projects (the MultiLoader layout): `common` holds every system and is
compiled against vanilla (NeoForm, Mojang mappings + Parchment); `fabric` and `neoforge` are thin
glue that register things and forward loader events to `WakeHooks` / `WakeClient`. Shared hooks
that neither loader exposes are common mixins, so both loaders run identical logic.

| Concern | Where | Notes |
|---|---|---|
| Night skip ("sleepers still in bed") | `ServerLevelMixin` → `Lapses.onNightSkipped` | Injects at the head of vanilla's private `wakeUpAllPlayers`, which only runs right after the skip. Day time from the start of the tick is captured so the Stopped can restore the night. |
| Sleep mods that fast-forward instead of skipping | `Director.trackSleep` | Any sleep during which ≥3000 ticks passed and dawn arrived counts. |
| Bed refusal | Fabric `EntitySleepEvents.ALLOW_SLEEPING`, NeoForge `CanPlayerSleepEvent` → `WakeHooks.sleepProblem` | Returns vanilla's own `NOT_SAFE`, so the game prints its own line. Only at night, only when vanilla would have allowed sleep. |
| State | `HauntData` (`SavedData` on the overworld) → `HauntState` per player | Clarity, peak act (one-way), routes, sleep spots, ledger snapshot, debt, flags, cooldowns. |
| Routes | `Breadcrumbs` | Every block stood on (8192 most recent, with wear counts), spatially indexed for nearest/neighbour queries; BFS in the finale. |
| The Wake | `Phantom` (server) → `PhantomPayload` → `ClientPhantom` | Not an entity. Only the owner is ever sent anything. Touch/lifecycle checks are server-side. |
| Silhouette | `Silhouette` + `WakeRenderTypes` + `rendertype_wake_night` | The player model with the player's arm width, drawn N times (N from smear and focus) with compounding alpha. The core shader colours each fragment by its *world* view direction, so the starfield lines up like a window onto a sky behind the world. No lighting, no fog, no shadow. |
| Shader packs | `WakeShaders.usable()` | Detects Iris/Oculus via reflection; falls back to a starfield texture with `entityTranslucentEmissive`. Config switch too. |
| Pause figure | `ScreenMixin` (after `renderBackground`) + `PauseFigure` | Drawn after the blur with the world matrices captured during the level render: the one sharp thing behind the menu. |
| Old-photograph look | `GameRendererMixin` + `PhotoEffect` (vanilla `PostChain`) | Program lives in the `minecraft` namespace under a prefixed name, because vanilla post passes cannot load namespaced programs. |
| Clocks | `ItemPropertiesMixin` → `ClockOverride` | Wraps the clock's `time` property; returns the dial for the minute you last lay down. Works in hands, item frames and on the ground. |
| Hitches | `Hitches` → `HitchPayload` | Client-measured frame stalls; validated, rate-limited and day-capped on the server. |
| The Stopped (singleplayer) | `StoppedManager` / `StoppedSession` | Vanilla tick freeze on the integrated server, night restored; client additionally holds weather, music and non-player sounds, and draws the ribbons. Broken beds go straight to the inventory because frozen item entities can never be picked up. Escape uses vanilla `startSleeping`, so the real lying-down view and fade. |
| The Stopped (servers, opt-in) | same, without the server freeze | Per-player client freeze, server-side invulnerability, `TargetingConditionsMixin` so nothing targets them, a custom fade instead of vanilla sleep (servers are in daytime after the skip). |
| Commands | `/thewake status|clarity|stage|beat|reset` | Op level 2. `beat` runs any single beat now, for testing or for players who want to skip ahead. |

### Safety rules in code

- World edits only place into replaceable air or move items between containers.
- Blinks and stolen mornings only target standable crumbs with no lava, fire, magma, powder snow or
  hostile mob within reach, never in hardcore, never mid-fall, mid-fight, swimming or riding.
- Invulnerability is only ever granted for the Stopped and is restored on every exit path
  (escape, catch, logout, death, dimension change, server stop).
- The tick freeze is only applied by this mod when the server was not already frozen, and only
  undone in that case.

---

## Pass 6: Scope

**Shipped in 1.0:** everything in passes 4–5: five acts, eleven beats, two endings, the Stopped on
singleplayer (servers opt-in), config, commands, four original sounds, two shaders and a post
effect, the asset generator, a scripted visual demo for development.

**Deliberately left out:** a second "Doorkeeper"-style entity behind the Wake (the Night itself
fills that role by implication, and the reviewer was right that a giant on the horizon is stock),
voice lines (any voice would explain too much), and custom blocks or items (everything the player
sees is a vanilla thing behaving wrongly).

**Next, if it earns it:** a private copy of the player's paths for the server finale instead of a
client freeze; per-language ledgers beyond English; a resource-pack hook so modpack makers can
retune the sounds.

---

## Pass 7: Playtest fixes

The mod was run in a real client (software OpenGL under a virtual display) with a scripted demo
that stages every beat and takes screenshots. What the screenshots showed, and what changed:

| Found | Fix |
|---|---|
| Stars in the silhouette were sub-pixel at normal resolutions: the Wake read as a flat black shape, not as night sky. | Stars now live on a cube-mapped sky and never shrink below about a pixel (`fwidth`), with a neighbourhood search so none are clipped. The figure now reads as a hole full of stars, by day and by night. |
| The ribbons in the Stopped existed but were invisible against dark ground once desaturated. | Ribbons get a neutral lift and brighter stars; the Stopped's photo effect dropped from 0.62 to 0.5 strength. |
| Standing just off the ribbons left the Wake parked at their edge forever: a soft-lock that looks like a hang. | After about 45 seconds at the end of its paths, it starts to step off them toward you, slowly. Visible, not a hidden timer. |
| A head turn on a featureless silhouette is unreadable: a cube turned 168 degrees looks like a cube. | As the turn passes the neck's limit, **your own face** (from your skin) surfaces on its head. Used exactly twice in the whole mod: the head turn, and the bad ending's figure in your bed. |
| The pause figure required window focus to even draw, so it never appeared in some setups. | It always draws; only the "seen" counter needs focus, so an alt-tabbed player still gets it later. |
| A bed on a pillar above your paths counted as "far from your paths". | Escape distance is horizontal, at any height. |
| On servers, inside your own Stopped, other players kept moving (vanilla never freezes players). | A client-only freeze for every player but you. |
| A black frame flashed at client start. | Overlay guard. |
| Payloads were required, so clients with the mod could not join servers without it. | Payloads are optional on NeoForge; Fabric already allowed it. Nothing haunts anyone the other side cannot hear. |

---

## Pass 8: Redesign, "the thing in the corner"

### Why

Playtest verdict from the owner: **not scary enough.** The night-sky silhouette is a clever
idea and a blank object: a flat shape with stars in it reads as *strange*, not as *something
that wants you*. Kept: the core rule (it only moves while you are not watching the world: menus,
sleep), the lapse framework, the acts, the finale structure. Rebuilt: the creature, how it moves,
what happens when it reaches you, and the whole presentation, now aimed at Trevor Henderson /
Doctor Nowhere / analogue horror.

### Research: what to take (and what not to)

**Trevor Henderson** (cryptid "photographs"): monsters composited into ordinary photos so they
look *photographed*, not drawn; **faces are the focus**: grinning, staring, gnashing; pale,
decrepit, emaciated anatomy with black eye sockets; elongated limbs and necks; enormous scale
disparity (giants behind treelines, faces peering out of bridges); "false faces"; peering through
doors, sinking back into walls; quiet, repetitive behaviour (staring, following, mumbling) that
implies intelligence; everyday settings.

**Doctor Nowhere** (Silas Orion; *The Boiled One Phenomenon*, *The Organator Enterprise*): a tall
humanoid with raw, melted-looking skin and a horrific open face, met through a hijacked broadcast
and a "warm yet deeply disconcerting" voice; *the Locust*: tall, lanky, black, an uncanny face with
soulless eyes and a long jaw. Early work openly shares Henderson's language; the horror comes from
institutional framing (broadcasts, tapes, corporate video) and wrong anatomy shown plainly.

**Analogue horror**: VHS grain, tracking errors, rolling noise bands, colour bleed, flicker,
static, warped audio; slow pacing; partial reveals; distorted familiar faces.

**Not taken:** any specific character (no speaker heads, no cartoon cat, no long horse, no red
boiled giant, no locust). The rule from the design skill still applies: it must *look* wrong for
Minecraft and *belong* to Minecraft.

### The creature

Built as a real high-detail 3D mesh (sculpted procedurally in Blender, see below), rendered
inside a blocky world: the fidelity intrusion is the first scare.

- **Too tall and too thin.** 3.4 blocks upright. Emaciated, ribs and spine showing, joints
  swollen. Arms hang to its knees; fingers are almost half a block long and slightly curled.
- **It has to fold to fit in your house.** Under a normal ceiling it stoops with its head pressed
  flat against the ceiling and its neck bent; it crouches with its knees by its ears; it crawls
  through doorways.
- **Skin that looks photographed.** Grey-yellow, mottled with bruise-violet, veins showing,
  dirt at the fingertips: a texture with photographic grain, not pixel art.
- **The face.** Long and pale, slightly too big, sitting a little forward of the skull like a
  mask. Deep black sockets with small wet glints. No nose, two slits. A lipless grin far too wide,
  packed with small flat human teeth. The jaw is hinged and opens further than a jaw should.

### How it moves: never while you watch

The world is a tape. When you look away from it, the tape keeps playing.

- **It only moves while you are in a menu** (inventory, chests, crafting, the pause menu, chat),
  **asleep**, **AFK**, or during a **real hitch**. The moment you are back in the world, it stops,
  frozen mid-motion wherever it got to, in whatever pose it was in.
- **You can watch it come, sideways.** Most menus do not cover the world: behind your inventory
  the world is still there, dimmed. At the edges of the screen, in stop-motion, something crawls
  closer while you sort your chest. Close the chest: silence; it is standing behind the door.
- **It freezes in the corner of your eye.** When a lapse ends, it is placed at the edge of your
  view or behind you, never dead centre: peeking round a wall edge with its fingers curled round
  it, standing in a dark corner with its head on the ceiling, crouched behind the furnace.
- **Peripheral vision.** While it is near the centre of your view it is perfectly still. In the
  corner of your eye, it is not: its head twitches and tilts, its fingers flex. Snap your view to
  it and it is still. Look away and back, and its head has turned to face you.
- **You can hear it.** While you are in a menu: slow steps that are not yours, joints cracking,
  wet breathing through a cheap microphone, a faint warble like a tape motor. Out of the menu:
  nothing.
- **The paperdoll.** Your inventory screen shows your character in a little window. When it is
  behind you, it is behind your character too: its face rising over your doll's shoulder, larger
  the closer it is. It is the real one.

### When it reaches you

From Act IV, if it reaches you during a lapse, the menu is torn away, your view is wrenched round
to it, and its face fills the screen with its jaw opening; the picture tears like a chewed tape;
a short distorted scream; real damage (default six hearts, armour does not help).
*"<player> looked away."* Then it is gone, somewhere far off, and will not do it again for a day.
In hardcore it never kills.

### Sleep

- Lying down is a lapse. If it is within reach as the screen darkens, you see it come: crawling
  up toward the bed while the picture fades. Click *Leave Bed* and it stops.
- A skipped night is a long lapse: by morning it is in the doorway (Act II), at the foot of the
  bed (Act III), or bent over you (Act IV), and sometimes it does not wait for you to get up.

### Before it hunts: scale

Act II is Henderson's giants. Before it ever comes close, after a lapse you find it far off: on
a ridge, behind a treeline, its head and shoulders above the trees, four times its size, facing
your house. Next time, somewhere else on the horizon. It can be any size it likes while nobody
is looking.

### The tape

- A VHS layer (grain, scanlines, colour bleed, a rolling tracking band, slight jitter) that
  thickens when it is on screen and close, burst-tears when it attacks, and is constant in the
  finale.
- Once it is awake, the pause menu is a paused tape: a small "PAUSE" in the corner, tracking noise
  across the frozen frame behind the menu, and in that frame, it is still moving toward you.
  Resume and "PLAY" flickers in the corner.
- Its sounds carry subtitles for players who use them, with direction arrows: a tool and a scare.

### What stays, what changes

| Beat | Fate |
|---|---|
| Act I chores (a bribe for routine) | Kept. In hindsight you know who did them. |
| Bed refusal, the ledger, clocks stopping near it, pets staring, your own hurt sound | Kept. |
| Stolen mornings, daylight blinks for kept nights | Kept: more lost time for it to use. |
| Sightings and waking afterimages | Replaced by the creature itself, frozen wherever its lapses left it. |
| The head turn | Kept, with its own face, lit, close. The "becoming you" arc is dropped. |
| The pause figure (once) | Replaced by the hunt: every long pause, it is behind the menu coming closer. |
| The finale | Kept: the world freezes like a paused tape, and now it moves while you watch, in stop-motion. Your worn routes show as burned-in static on the ground. Escape and both endings unchanged. |

### Fairness

- Alt-tabbing does not count: an unfocused pause waits for you.
- Short menus barely move it; it needs time. A quick inventory check is safe early, and stops
  being safe in Act IV.
- Only one attack per in-game day; damage and hardcore behaviour are configurable.
- It never breaks blocks or takes items.

### The model pipeline

The creature is generated by `tools/creature/build_creature.py`, run in headless Blender (the
`bpy` module, no GUI or MCP needed): a skin-modifier skeleton for the body, a sculpted head with
sockets, slits and a hinged jaw full of teeth, an armature, a pose library, and a baked
photographic texture. Each pose is exported as an OBJ with `body`, `head` and `jaw` groups plus a
small JSON of pivots. The mod loads them at runtime, so a resource pack (or the owner, from
Blender, Meshy, Tripo, Hunyuan3D…) can replace the model without touching code.

## Pass 9: As built (and what changed on the way)

Notes from implementing Pass 8, where reality pushed back.

### The creature in the game

- **Not an entity.** It is still a per-player "appearance" that only its owner's client knows
  about (the old phantom system, re-skinned): no entity, nothing for other players to see, nothing
  to hit, no spawn rules to collide with other mods.
- **Drawn as a real mesh.** About 29k triangles across 13 poses, with the head and jaw rigid so
  the client can turn the head and open the jaw on top of any pose. Each pose lives in a static
  vertex buffer and is drawn with vanilla's entity shader and lightmap: it is exactly as dark as
  the corner it stands in, and shader packs see an ordinary entity draw.
- **Two wet pinpoints.** The eyes are black, but a tiny additive glint sits at the bottom of each
  socket, so in a dark room you see two points of light at head height before anything else.
- **Stop-motion.** Position and pose never interpolate on the client: every change is a cut.
  Walking is two frames (`walk_a`/`walk_b`, `crawl_a`/`crawl_b`) alternating a block at a time.

### How it moves (the hunter)

- A breadth-first field from the player's feet (radius 36) gives it real paths through houses.
  It walks through doors (it opens and closes them while you are not looking), crawls where there
  are only two blocks of room, stoops or presses its head against three-block ceilings.
- Lapses are computed every tick from facts, not events: sleeping, idle, or a screen reported
  open by the client with the window focused (`menuHunts` / `pauseEvents`). Singleplayer pauses
  stop the integrated server's world, so a mixin on `IntegratedServer.tickPaused` lets only the
  creature take its steps.
- Speed by act: 24 / 16 / 11 ticks per block (Act III / routine / Act IV), two blocks per step
  while it is more than 12 away. It arrives 5.5 / 3 / 1.5 seconds into a lapse, 16-26 blocks
  out, at the edge of your view (60%) or behind you.
- Closest approach: 9 blocks in Act III, 2 later, 0 in Act IV (where reaching you is the attack).
- When the lapse ends it is frozen where it got to. If that is the middle of your view it is moved
  aside along its paths (or is simply gone). Within 16 blocks it looks for a wall edge to stand
  behind and lean out from; otherwise it settles into a standing, crouching or reaching pose.

### Seeing it

- Looked at directly (within about 22 degrees of centre) it is perfectly still. At the edge of
  your view the head jerks, tilts and the jaw works. Out of sight for half a second, its head is
  turned to face you, at an angle no neck holds, and that is how you find it when you look back.
  All client-side, per frame, nothing sent over the network.
- Menus no longer hide it. Container screens dim the world and the pause menu blurs it; the
  creature is drawn again over that, undimmed and sharp, but against the world's saved depth, so
  walls still hide it. You watch it come at the edges of the screen while you sort a chest.
- The paperdoll: if it is within seven blocks behind you, its face rises over your doll's
  shoulder in the inventory window.

### The tape

- One post effect, `thewake_tape`: chroma bleed, horizontal softness, scanlines, grain, a
  tracking band rolling down the picture, head-switching noise at the bottom, and a tear (sheared
  line blocks, vertical skips, snow) for the attack. Its strength follows reasons: the creature on
  screen and close, the creature moving, the pause menu, the morning after, the Stopped.
- Behind the pause menu: a paused tape, `PAUSE` in the corner; `PLAY ▶` when you resume.
- The Stopped's routes are burned-in static now, not night sky.

### Sound

All synthesized (`tools/generate_assets.py`): bare footsteps (3), joint cracks (3, one of them a
neck), wet breathing through a cheap microphone (2), the tape motor (a loop, only while it
moves), static, a VCR key, and the scream: short, pitch lurching up and collapsing, saturated and
bit-crushed. The scream is the only loud sound in the mod and has its own volume setting. Every
sound has a subtitle; positional ones get vanilla's direction arrows for free.

### Acts, renamed

`EXPOSURE` became `WATCHING` (Act II: the giants), `THEFT` became `HUNT` (Act IV: it attacks),
`LIKENESS` became `HUSH` (Act V: the silence and the head turn). Mornings by act: a giant on the
horizon, then the doorway (the door left open), then the foot of the bed, then bent over you.

### Fairness, revisited

- Walking into it still costs you an hour (the blackout), but now it also dispels it: a way out
  of a creature planted next to your bed, at a price.
- The bed refuses you ("monsters nearby") only while it is within five blocks.
- The attack is once per in-game day, armour does not help, and in hardcore it leaves you at half
  a heart unless `attackHardcoreKills` is on. `attacks`, `attackDamage`, `screamVolume` and
  `tapeEffect` are all in the config.

### The model pipeline, as built

`tools/creature/build_creature.py` runs in headless Blender (`pip install bpy`), no GUI or MCP
server needed. Skin-modifier skeleton with extra "shape" joints for knobbed, wasted limbs;
anatomy sculpted in numpy (ribs sloping to a sternum, costal margin over a sucked-in belly,
vertebrae, shoulder blades, collarbones, hip bones, neck tendons); a head sculpted from a dense
sphere (sockets, slits, a recessed ear-to-ear grin band, a chin), split into skull and hinged
jaw, with sixty boxy teeth and two eyes. Position, normal, AO and part maps are baked with
Cycles and painted in numpy (mottling, bruises, veins, grime on hands and feet, nails, gums,
yellowed teeth). Decimated, rigged with bone-heat weights, posed with dual-quaternion skinning,
and exported as one gzipped binary (`models/creature/creature.bin.gz`; `--obj` also writes OBJs
for editing elsewhere). A replacement mesh (sculpted by hand, or generated with Meshy, Tripo or
Hunyuan3D) can be dropped into the same pipeline as long as it is rigged to the same skeleton.

## Pass 10: The card (a new creature, from Doctor Nowhere's work)

### Why

Owner feedback on Pass 9's model: it needs a new model, uniquely shaped, taking heavy
inspiration from Doctor Nowhere, with his art as direct reference. The grinning skull-mask on a
pale starved body read as generic: a "creepy humanoid", not a picture you remember.

### References (his originals only, studied, not copied)

The Boiled One (the broadcast still, and the full-body still of it standing in a field over a
house); the Locust / Organator from *T.O.E.*; Longboy Bonsai; *the boy and the bath* (the white
figure with strings from its jaw to its chest); the square paper face from *where_did_life_go*; the
bald bust with its eyes in the wrong place, from *paper souls*; the heart hung in a wallpapered
corner. (Pass 10 first had three of these titles swapped; corrected in Pass 11.)

What they share:

- **Bodies that are barely there.** Flat black, silhouette-like, impossibly thin. The Locust's
  waist is not a waist: between ribs and pelvis there is only a bare, jointed spine. Arms like
  sticks, hanging past the knees.
- **Real faces on wrong shapes.** A realistic nose and eyes on a flat square card; tiny eyes
  stuck to the side of a smooth bald head; a small, ordinary human face in a mass of black tubes.
  The features are photographic; what they are attached to is not.
- **Lines.** Needles radiating from the Boiled One's head and standing out of its body; tubes and
  hair around the Locust's face; pale strings stretched from a jaw to a chest; veins hanging a
  heart on a wall.
- **The jaw.** Mouths that open far past where a mouth stops, the face dragged down with them.
- **Where they are.** Wallpapered corners, a field behind a house, a doorway: ordinary rooms,
  photographed.

### The creature now

- **Body:** flat black, stick limbs, arms past the knees, fingers almost half a block long with
  knotted joints. No belly: the waist is a bare knotted spine with spurs off the back; the ribs
  flare out above it over nothing. Long pins driven through a shoulder, the back, an arm, a
  thigh, a neck. A long, thin neck.
- **Head: a card.** A pale slab about 0.27 by 0.49 blocks and only four centimetres thick,
  slightly bent, wider at the top, leaning forward over you. Old, stained, handled. A real face is
  pressed into it, like a photograph of a face: a long nose, sunken staring eyes with small pupils
  (one set lower than the other, one not quite looking where the other does), no eyebrows, thin
  lips barely apart. Above the face, far too much blank card.
- **Pins** driven through the card and out of its edges like a halo; two straight through the
  face, one through the forehead, one through a cheek.
- **Strings:** pale, wet, too many to be hair, hanging from the card's lower edge down over the
  chest.
- **The mouth:** the card is cut straight across at the lips. The lower part hangs on a hinge at
  the back; when it opens it drops away into a dark wet slot with two rows of small teeth, and in
  the attack it slides down further than any jaw.

**Not taken:** any of his actual creatures: no boiled red head, no tube-haired face, no Longboy.

### Why it works in the corner of your eye

In a dark room the black body vanishes; what is left is a pale rectangle at head height, three
blocks up, with a face on it, and two wet points of light. In daylight it is a black stick figure
with a white card for a head and a halo of pins: a silhouette nothing in Minecraft has. Close
up, the face is the worst part: it is an ordinary face, and it is on a card.

## Pass 11: The rework, in stages

Owner request: rework and polish the whole mod, going through stages to find the best model,
animation, concept and gameplay loop. Draw the monster designs as pixel art first, as reference,
and reference Doctor Nowhere heavily. Mid-way: "the pixel art needs to be higher res so you can add
the horrific details."

### Stage 1: a wider reference board

Pass 10 worked from about ten images. This time: all fifteen videos on his channel, with the
thumbnail and three frames from inside each (sixty images), contact-sheeted and studied. New to
the board:

- the Heliogene (a grinning sun-face on a draped stalk);
- the *Smilehood* portrait with half its face gone;
- a doorway figure with an eye-star for a head;
- *new ear*'s crowd of identical cone-heads on black thorns;
- *Desiderio* (a long-nosed pentagon skull waving a long-fingered hand; a white figure with
  branching tree arms and a seam down its chest; a man with a lampshade for a head);
- the Locust in a wallpapered corner, its hand pressed to the glass until it cracks (*T.O.E.*);
- the white figure folded into a crib under a clock (*the boy and the bath*);
- the text cards ("why did you leave me here?", "it was all your fault", "the body appeared
  untouched").

His visual language, as a checklist:

1. A pale, photographic face on the wrong thing: a card, a box, a sun, a cone, a lampshade.
2. Bodies that are barely there: flat black silhouettes, or bone-white starved sculpture, too long
   in the neck and limbs.
3. Thin radiating lines: pins, rays, branches, strings, hair.
4. Hands: huge, long-fingered, pressed toward you.
5. Domestic and old: wallpaper, doorways, cribs, framed photos, a clock.
6. Frames within frames: photos held in the dark, TVs, chat windows.
7. Repetition, children's drawings, and calm text over terrible pictures.
8. Sadness and guilt under the horror: things left behind, asking why.

### Stage 2: six designs, drawn in pixel art

Drawn in code (`tools/concepts/`), first at 32 pixels per block. At the owner's request, redrawn at
80 pixels per block with 300-pixel faces, so the details could be horrible: capillaries, worn teeth
and gums, saliva, pin wounds with rust running down, paper tears. Each sheet shows:

- front and side views;
- a dark-room corner-of-the-eye test;
- far away as a giant behind trees;
- the bare silhouette;
- the face, and the face when it reaches you;
- one close detail.

The sheets are in `docs/concepts/a_card.png` to `f_sheet.png`, and `board.png`.

| | Design | Idea |
|---|---|---|
| A | The Card | The creature as built in Pass 10: the baseline to beat. |
| B | The Puppeteer | Black-light theatre: all matte black but a long pale face and two pale hands, the face on a lengthening rod of a neck. |
| C | The Collage | Eyes, nose and mouth cut from photographs of different people, taped onto a square of paper, sliding around in the corner of your eye. |
| D | The Radiant | A round face ringed with long fingers like a sunburst, on a black robe-spire. |
| E | The Bust | A plaster bust and a bald egg of a head, its eyes in the wrong places. |
| F | The Sleeper | Something under an old red bedsheet, its face pushed through the cloth. |

### Stage 3: critique and choice

An independent reviewer scored each design on six things: the corner-of-the-eye read, the
close-up, the silhouette, how much it feels like Doctor Nowhere, originality, and how well it
suits peeking, folding and crawling. In summary:

- **A** has the best idea (real wet eyes in a dead stained card), but its pin halo is the Boiled
  One's and its string fringe reads as a jellyfish.
- **B** fits the behaviour best and wins the dark room (three pale things: a face and two hands),
  but its egg mask is generic.
- **C** has the best mechanic ("a little more wrong each time") and the best close-up, but it is
  *where_did_life_go*'s paper face and turns cute at a distance.
- **D** has the strongest silhouette, but it is a Heliogene with fingers, and it can neither stoop
  nor crawl.
- **E** is a near-copy of *paper souls*.
- **F** is the most visceral up close, but standing, it silhouettes as a pointed hood.

Its recommendation, which I agree with: **A's head on B's body, with C's mechanic and F's
wetness.** That means:

- the card cut to a face too long, so that it reads as a face from across a room;
- a real face pressed through it from behind;
- the eyes seen through ragged holes;
- cut across the lips into a ventriloquist dummy's jaw, held by stitches;
- B's pale hands with a knuckle too many, on the black puppet body;
- C's sliding features turned into stop-motion replacement faces;
- F kept for one scene only: its face pushed up through your bedsheet.

Dropped: the pin halo (four short pins remain, at the corners) and the string fringe.

Round 2 (`docs/concepts/final_*.png`) draws the chosen design from the game's own rig. The card is
now an old index card, its faint ruled lines bending over the face behind it, with a typed number
in the corner. The sheets show:

- a turnaround;
- the hand, sewn on at the wrist;
- five replacement faces, each worse than the last;
- the attack frame by frame: a slow blink, then the stitches give and the chin piece drops onto
  a real mouth, and the last frame, fingers curling in round the edges of your screen;
- the places you find it: round a door frame, its face flat against your ceiling over your bed,
  crawling through a doorway, as a giant over the trees, under your sheet, and at light levels 0,
  4 and 7.

### Stage 4: the model, built from the pixel art

`tools/creature/card.py` draws the card at any resolution, from the same numbers as the pixel art:

- the scissor-cut outline;
- the eye holes torn in it;
- the dummy's chin piece;
- the face behind it, as a height field;
- the painted front.

The Blender build turns the masks and height field into geometry: a grid over the mask, pushed out
by the face, given a paper's thickness, its outer edge smoothed and its eye holes left ragged. It
then samples the painted card as the texture, so the model cannot drift from the reference.

Behind the holes sit real eyeballs (bloodshot, grey-green, wet), in dark sockets, with lids on their
own joints. Behind the chin piece is a wet dark mouth with two rows of small worn teeth. A small
black skull behind the card hides all of it from behind; from the front, the card covers it.

The body is the black stick body, with:

- lacquered ball joints at every joint, like a stop-motion armature;
- thicker limbs, so it reads at a distance;
- a longer neck;
- pale hands sewn on at the wrist, with dirt in the creases, dark nails and four phalanges a
  finger.

Previews are rendered with Cycles and checked against the pixel art. Fixes found that way:

- the card's edge was stair-stepped;
- the mouth was not deep enough for the jaw's full drop;
- the tongue read as a floating disc;
- seen from behind, the sockets showed through the card.

### Stage 5: animation

Pass 9 and 10 drew the creature from thirteen baked poses: a static pose library, with a two-frame
walk. Now:

- **A real skeleton in the game.** The model file (format v3, `tools/creature/export_v3.py`) carries
  125 joints, four skin weights per vertex, rigid parts riding on joints, named attachment points,
  and baked clips.
  - The game skins on the CPU with dual quaternions, matching Blender's own deform to within
    0.1 mm (checked on every build).
  - It does this only when the pose actually changes, a few times a second in stop-motion. Indexed
    uploads keep each re-skin under a megabyte.
- **Clips**, authored as keyed poses with feet put on the floor frame by frame (`poses.py`):
  - a marionette walk (knees lifted too high, shoulders hitching, fingers trailing, the head level
    and on you);
  - a stoop-walk, bent double under three-block ceilings;
  - a low crawl with elbows and knees splayed, to fit doorways;
  - leaning out from behind a wall edge;
  - the lunge;
  - sitting up out of a bed, straight-backed, for the finale.
- **The server drives the stop-motion.** It walks in sub-block steps, and the client gets a new
  frame (pose and place together) every quarter stride. Headroom picks the clip: tall rooms walk,
  three blocks stoop, doorways crawl. Behind your inventory you watch it come, a few drawings a
  second.
- **Procedural layers** on top of any frame:
  - the head turned to you, spread down the neck;
  - eyes that find you (up to 28 degrees);
  - lids that close;
  - the dummy's chin dropping straight down its cuts;
  - a curl of the fingers, a hitch of the shoulder.
- **Replacement faces.** In the corner of your eye it is wearing another face: lids half down, eyes
  not quite on you or not quite together, the jaw ajar. Look straight at it and it is back to the
  first face, almost. The head shaking every few ticks at the edge of the screen (which read as a
  glitch) is gone: the periphery starts at 36 degrees, and twitches are rare and small.
- **The attack** now takes about a second and a half: the face comes at you, blinks once, slowly,
  then the stitches go and the chin keeps coming. It is drawn with its own fixed lens, so it fills
  the screen the same way at any field of view.

### Stage 6: the concept and the loop

A second independent reviewer read this document and the code. Its five weaknesses:

1. **Act IV taxed ordinary play.** Any menu held eight to twelve seconds cost six hearts:
   crafting, anvils, trading, even reading its own ledger. Bites came at bedtime and, 30% of the
   time, on waking. Players would take the daily bite at full health and then craft in peace.
2. **Free tells, and an off switch.** The tape motor started only when it arrived, so the player
   always knew. Then the hush turned the hunt off for three days.
3. **Space meant nothing,** except by accident.
   - It re-arrived near you wherever you went.
   - Doors, iron ones too, counted as air.
   - Standing on carpet, slabs, paths, farmland or soul sand, or swimming, it could not find you
     at all and was silently deleted.
4. **Routine.** Each act had one fixed morning spot, and "walk into it" dismissed anything.
5. **The founding dilemma was gone.** Sleeping no longer reset Time Since Last Rest, so phantoms
   came whether you slept or not.

What changed, keeping the core rule (it moves only while you are not watching the world):

- **Two-stage reach.** In Act IV it stops at arm's length, its jaw a frame open, and waits. It
  bites only if you opened the menu with it already that close, or if you stay in the menu five
  seconds longer now that it is.
  - Never in your sleep, never while you are away from the keys, never on waking.
  - Never through the pause screen: that waits until you press play.
  - A random cooldown of two to six minutes replaces the daily cap, so there is no "take the
    bite and then craft in peace".
- **It comes out of the dark.** It only arrives where it is dark enough for a monster, and if a
  lapse leaves it standing in torchlight, you find it in the nearest dim place instead. A torch
  set down at its feet makes it not be there any more. Light buys distance, not immunity.
- **It haunts the house, not you.** Menus only count within 48 blocks of your bed.
- **Doors.** A closed wooden door costs it a few seconds, and you hear the latch while you are in
  the menu. An iron door stops it.
- **The morning film.** From Act III the mornings are shot one frame a night, per room: for every
  skipped night in a bed it is a step further from the door toward your pillow, and for every night
  you keep, a step back. The act holds it back: the doorway first, then near the bed, then bent
  over you. A new bed starts a new film, and the old room keeps its last frame.
- **Calm words, in the game's own text.** After a stolen morning, the thing in your hand has been
  renamed, *why did you leave me here?*, in the italics of an anvil. It does this at most a handful
  of times, never takes anything, and never overwrites a name you gave.
- **Fixes:**
  - The path field is rooted where your feet really are, and it searches up as well as down.
    Thin collision (carpet, snow) does not block it. When it cannot find you it waits instead of
    vanishing.
  - Sleeping rests you again, so phantoms are back on the scale against sleep.
- **Numbers:**
  - Arrivals at 26-48 degrees from where you look (80%), beside the menu panel, where you can
    watch it come; otherwise behind you.
  - The tape motor runs through every lapse it could use, not just the ones it does.
  - Walking into it costs an hour, then two, then three in a day.
  - The hush is one silent day, not three.
  - A kept night takes back 0.08, not 0.06.
  - In the finale it walks at 0.2 to 0.25 blocks a tick, faster than you walk.
- **The giant** is only placed where its head and shoulders clear the hills and the trees from
  where you stand (Pass 10's demo had hidden it in a valley).

**Considered, not taken (yet):**

- Four place-based phases instead of seven stages. Its point stands (Sightings and Routine differ
  mostly in tick counts), but it is a larger rewrite than this pass.
- A "frames" hush, where it appears only over the paperdoll's shoulder and in the paused frame.
- Children's drawings on framed maps.
- The hand on the glass: that is *T.O.E.*'s move.
- Retuning the finale's paths to the ones it walked.
