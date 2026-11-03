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
