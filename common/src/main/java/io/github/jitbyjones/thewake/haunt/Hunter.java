package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import io.github.jitbyjones.thewake.stopped.StoppedManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The creature while you are not watching the world.
 *
 * <p>It only ever moves during a lapse: a menu (inventory, a chest, crafting, chat), the pause menu
 * (with the window focused), sleep, or standing idle. Each step is a cut: a new place, a new frame
 * of its walk, like stop-motion. When the lapse ends it is wherever it got to, frozen, and never in
 * the middle of your view. From Act IV, if it reaches you, it attacks.
 */
public final class Hunter {
    public enum Lapse { NONE, MENU, PAUSE, SLEEP, IDLE }

    /** Screen kinds reported by the client. */
    public static final int SCREEN_NONE = 0;
    public static final int SCREEN_MENU = 1;
    public static final int SCREEN_PAUSE = 2;
    public static final int SCREEN_CHAT = 3;

    private static final Map<UUID, Hunt> HUNTS = new HashMap<>();

    static final class Hunt {
        Lapse lapse = Lapse.NONE;
        int lapseTicks;
        int screen;
        boolean focused = true;
        @Nullable
        Phantom creature;
        @Nullable
        Field field;
        int fieldAge;
        int stepCooldown;
        int steps;
        /** The stretch it is walking: from one cell to the next, how far along, how far in all. */
        @Nullable
        Vec3 from;
        @Nullable
        Vec3 to;
        double progress;
        double travelled;
        int cells = 1;
        int frameClock;
        boolean movedThisLapse;
        boolean hunting;
        /** Lapse tick when it got to arm's length, or -1. */
        int reachedAt = -1;
        /** The lapse began with it already within reach: you opened the menu anyway. */
        boolean startedInReach;
        int lightCheck;
    }

    /** How far from home it can find you. Away from your bed, menus are only menus. */
    private static final double HOME_RANGE = 48.0;
    /** Blocks it has to be within, and how long it waits there with the menu still open. */
    private static final double REACH = 2.2;
    private static final int REACH_GRACE = 100;

    private Hunter() {}

    private static Hunt of(ServerPlayer player) {
        return HUNTS.computeIfAbsent(player.getUUID(), id -> new Hunt());
    }

    /** The creature that hunts this player, if it is anywhere. */
    @Nullable
    public static Phantom creature(ServerPlayer player) {
        Hunt hunt = HUNTS.get(player.getUUID());
        if (hunt == null || hunt.creature == null || hunt.creature.isRemoved() || hunt.creature.isDissolving()) {
            return null;
        }
        return hunt.creature;
    }

    /** Something placed it (a morning, a sighting): the next lapse starts from there. */
    public static void adopt(ServerPlayer player, Phantom phantom) {
        Hunt hunt = of(player);
        if (hunt.creature != null && hunt.creature != phantom && !hunt.creature.isRemoved()) {
            Phantoms.remove(player, hunt.creature);
        }
        phantom.setLapseGeneration(-1);
        hunt.creature = phantom;
    }

    /** It is gone (an attack, a new morning, the hush). */
    public static void release(ServerPlayer player, boolean remove) {
        Hunt hunt = HUNTS.get(player.getUUID());
        if (hunt == null) {
            return;
        }
        if (remove && hunt.creature != null) {
            Phantoms.remove(player, hunt.creature);
        }
        hunt.creature = null;
    }

    public static void forget(UUID id) {
        HUNTS.remove(id);
    }

    public static void onServerStopped() {
        HUNTS.clear();
    }

    /** Client report: which screen is open, and whether the game window has focus. */
    public static void onScreen(ServerPlayer player, int kind, boolean focused) {
        Hunt hunt = of(player);
        hunt.screen = Mth.clamp(kind, SCREEN_NONE, SCREEN_CHAT);
        hunt.focused = focused;
    }

    /** True while it is moving: the client plays the tape motor and thickens the noise. */
    public static boolean hunting(ServerPlayer player) {
        Hunt hunt = HUNTS.get(player.getUUID());
        return hunt != null && hunt.hunting;
    }

    // ---- ticking --------------------------------------------------------------------------

    public static void tick(ServerPlayer player, HauntState state) {
        Hunt hunt = of(player);
        Lapse now = current(player, state, hunt);
        if (now != hunt.lapse) {
            Lapse was = hunt.lapse;
            hunt.lapse = now;
            if (was == Lapse.NONE) {
                begin(player, state, hunt);
            } else if (now == Lapse.NONE) {
                end(player, state, hunt, was);
            }
        }
        boolean hunting = false;
        if (hunt.lapse != Lapse.NONE) {
            hunt.lapseTicks++;
            if (hunts(player, state)) {
                try {
                    advance(player, state, hunt);
                } catch (RuntimeException e) {
                    TheWake.LOG.error("Hunt failed for {}", player.getGameProfile().getName(), e);
                    release(player, true);
                }
                // The tape motor runs through every lapse it could be using, not only when it is.
                hunting = true;
            }
        } else if (++hunt.lightCheck >= 20) {
            hunt.lightCheck = 0;
            torchlight(player, hunt);
        }
        if (hunting != hunt.hunting) {
            hunt.hunting = hunting;
            state.syncDirty = true;
        }
    }

    /** Singleplayer, paused: the integrated server is not ticking the world, but the pause menu is a lapse. */
    public static void tickPaused(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            HauntState state = Haunt.state(player);
            tick(player, state);
            Phantoms.sync(player);
            Haunt.flushSync(player, state);
        }
    }

    private static Lapse current(ServerPlayer player, HauntState state, Hunt hunt) {
        if (player.isSleeping()) {
            return Lapse.SLEEP;
        }
        if (state.idleLapseOpen) {
            return Lapse.IDLE;
        }
        if (hunt.screen != SCREEN_NONE && hunt.focused) {
            WakeConfig config = WakeConfig.get();
            if (hunt.screen == SCREEN_PAUSE) {
                return config.pauseEvents ? Lapse.PAUSE : Lapse.NONE;
            }
            return config.menuHunts ? Lapse.MENU : Lapse.NONE;
        }
        return Lapse.NONE;
    }

    private static boolean hunts(ServerPlayer player, HauntState state) {
        if (LastNight.ejecting(player)) {
            // The end of it: the old rule, and nothing else.
            return Haunt.eligible(player) && !player.isCreative();
        }
        if (!Haunt.awake(state) || !Haunt.eligible(player) || StoppedManager.isIn(player) || player.isCreative() || state.chasing
                || Temper.removed(state) || LastNight.active(player)) {
            return false;
        }
        Stage stage = state.stage();
        if (!stage.atLeast(Stage.SIGHTINGS) || stage.atLeast(Stage.HUSH) || Beats.hush(state, player.serverLevel())) {
            return false;
        }
        // It haunts the house, not you: far from your bed a menu is only a menu.
        BlockPos home = state.bed();
        return home == null || home.distToCenterSqr(player.position()) < HOME_RANGE * HOME_RANGE;
    }

    /** A light put down at its feet: it is not there any more, the next moment you look. */
    private static void torchlight(ServerPlayer player, Hunt hunt) {
        Phantom creature = creature(player);
        if (creature == null || creature.scale() != 1.0F) {
            return;
        }
        BlockPos at = BlockPos.containing(creature.pos()).above();
        if (player.serverLevel().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at) >= 12) {
            Phantoms.dissolve(player, creature, true);
            hunt.creature = null;
        }
    }

    /** Dark enough for it to come from: no more light than a monster spawns in, give or take. */
    static boolean dark(ServerLevel level, BlockPos feet) {
        BlockPos head = feet.above();
        int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, head);
        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, head) - level.getSkyDarken();
        return Math.max(block, sky) <= 7;
    }

    private static void begin(ServerPlayer player, HauntState state, Hunt hunt) {
        if (DemoScript.ENABLED) {
            TheWake.LOG.info("[demo] lapse begins: {}", hunt.lapse);
        }
        hunt.lapseTicks = 0;
        hunt.movedThisLapse = false;
        hunt.stepCooldown = 0;
        hunt.to = null;
        hunt.frameClock = 0;
        hunt.reachedAt = -1;
        Phantom creature = creature(player);
        hunt.startedInReach = creature != null && creature.scale() == 1.0F && hunt.lapse == Lapse.MENU
                && creature.pos().distanceTo(player.position()) <= REACH;
    }

    private static void end(ServerPlayer player, HauntState state, Hunt hunt, Lapse was) {
        int seconds = hunt.lapseTicks / 20;
        Phantom near = creature(player);
        if (was == Lapse.PAUSE && near != null && hunt.reachedAt >= 0 && near.pos().distanceTo(player.position()) <= REACH
                && Attack.allowed(player, state)) {
            // It never tears the pause screen away. It waits for you to press play.
            Attack.begin(player, state, near);
            hunt.creature = null;
            return;
        }
        if (hunt.creature != null && !hunt.creature.isRemoved() && hunt.movedThisLapse && was != Lapse.SLEEP) {
            settle(player, state, hunt);
        }
        if (hunt.movedThisLapse && (was == Lapse.MENU || was == Lapse.PAUSE)) {
            Torment.lostTime(player, state, seconds);
        }
        // A long menu or pause is lost time like any other.
        if ((was == Lapse.MENU || was == Lapse.PAUSE) && seconds >= 20 && Haunt.awake(state) && Haunt.eligible(player)) {
            state.lapse();
            Haunt.gain(player, state, Math.min(0.006F, 0.002F + seconds / 60.0F * 0.001F));
            if (state.stage() == Stage.WATCHING) {
                maybeGiant(player, state);
            }
        }
    }

    /** Act II: after lost time, sometimes, far away. One a day. */
    static void maybeGiant(ServerPlayer player, HauntState state) {
        long day = Haunt.day(player.serverLevel());
        if (state.lastGiantDay() == day || player.getRandom().nextFloat() > 0.55F || Beats.hush(state, player.serverLevel())) {
            return;
        }
        if (Appearances.giant(player) != null) {
            state.setLastGiantDay(day);
        }
    }

    // ---- moving ---------------------------------------------------------------------------

    private static void advance(ServerPlayer player, HauntState state, Hunt hunt) {
        Stage stage = state.stage();
        Phantom creature = creature(player);
        if (creature == null || creature.pos().distanceToSqr(player.position()) > 48.0 * 48.0 || creature.scale() != 1.0F) {
            if (--hunt.stepCooldown > 0) {
                return;
            }
            if (hunt.lapseTicks < arrivalDelay(stage, hunt.lapse)) {
                hunt.stepCooldown = 1;
                return;
            }
            arrive(player, state, hunt);
            hunt.stepCooldown = interval(stage, hunt.lapse);
            return;
        }
        if (hunt.stepCooldown > 0) {
            hunt.stepCooldown--;
            return;
        }
        walk(player, state, hunt, creature);
    }

    /** Ticks per block. Sleep is fast: it has a few seconds of fading screen to reach you. */
    private static int interval(Stage stage, Lapse lapse) {
        if (lapse == Lapse.SLEEP) {
            return 5;
        }
        int base = stage.atLeast(Stage.HUNT) ? 11 : stage.atLeast(Stage.ROUTINE) ? 16 : 24;
        return lapse == Lapse.IDLE ? base * 3 / 2 : base;
    }

    /**
     * Ticks between frames: it is shot in stop-motion, a few drawings a second. Asleep the film runs
     * faster; in a menu it is slow and deliberate.
     */
    private static int frameTicks(Lapse lapse) {
        return lapse == Lapse.SLEEP ? 2 : 3;
    }

    /** Blocks a full eight-frame cycle of each clip carries it. */
    private static double stride(CreaturePose pose) {
        return pose == CreaturePose.CRAWL ? 1.25 : pose == CreaturePose.STOOP_WALK ? 1.6 : 2.0;
    }

    private static int arrivalDelay(Stage stage, Lapse lapse) {
        if (lapse == Lapse.SLEEP) {
            return 8;
        }
        return stage.atLeast(Stage.HUNT) ? 30 : stage.atLeast(Stage.ROUTINE) ? 60 : 110;
    }

    /** Steps from you it will not come closer than. */
    private static int closest(Stage stage, boolean canAttack) {
        if (stage.atLeast(Stage.HUNT)) {
            return canAttack ? 0 : 2;
        }
        return stage.atLeast(Stage.ROUTINE) ? 2 : 9;
    }

    private static Field field(ServerPlayer player, Hunt hunt) {
        BlockPos root = Field.feet(player);
        if (hunt.field == null || hunt.field.root.distSqr(root) > 4.0 || ++hunt.fieldAge > 400) {
            hunt.field = Field.build(player.serverLevel(), root, 32, 7000);
            hunt.fieldAge = 0;
        }
        return hunt.field;
    }

    /** It comes from somewhere you cannot see: the edge of your view, so you can watch, or behind you. */
    private static void arrive(ServerPlayer player, HauntState state, Hunt hunt) {
        Field field = field(player, hunt);
        if (field.size() < 40) {
            return;
        }
        RandomSource random = player.getRandom();
        ServerLevel level = player.serverLevel();
        // Mostly where you can see it come, at the side of the screen beside the menu; sometimes
        // behind you. Always out of somewhere dark.
        boolean side = random.nextFloat() < 0.8F;
        int min = state.stage().atLeast(Stage.HUNT) ? 13 : 16;
        int max = min + 10;
        BlockPos cell = field.pick(min, max, key -> {
            BlockPos p = BlockPos.of(key);
            float a = Appearances.angleFromView(player, Vec3.atBottomCenterOf(p).add(0.0, 1.8, 0.0));
            return (side ? a > 26.0F && a < 48.0F : a > 100.0F) && dark(level, p);
        }, random);
        if (cell == null) {
            cell = field.pick(min, max + 6, key -> Appearances.angleFromView(player, Vec3.atCenterOf(BlockPos.of(key))) > 26.0F
                    && dark(level, BlockPos.of(key)), random);
        }
        if (cell == null) {
            return;
        }
        Vec3 at = jitter(cell, random);
        Phantom creature = Appearances.still(player, cell, Spots.yawTowards(at, player.position()), CreaturePose.WALK);
        creature.moveTo(at, creature.yaw()).setTouch(0.0, false);
        adopt(player, creature);
        hunt.from = null;
        hunt.to = null;
        hunt.movedThisLapse = true;
        sound(player, creature, WakeRegistry.CRACK.get(), 0.7F, 0.9F + random.nextFloat() * 0.2F);
        if (DemoScript.ENABLED) {
            TheWake.LOG.info("[demo] it arrives {} blocks away", String.format("%.1f", at.distanceTo(player.position())));
        }
    }

    /**
     * One tick of walking. It travels a little every tick, but you only ever see it every few
     * ticks, a frame at a time, its walk cycle advancing with the ground it covers.
     */
    private static void walk(ServerPlayer player, HauntState state, Hunt hunt, Phantom creature) {
        if (hunt.to == null || hunt.progress >= 1.0) {
            if (!plan(player, state, hunt, creature)) {
                return;
            }
        }
        double length = Math.max(0.05, hunt.from.distanceTo(hunt.to));
        double speed = hunt.cells / (double) interval(state.stage(), hunt.lapse);
        double before = hunt.progress;
        CreaturePose clip = creature.pose().isMoving() ? creature.pose() : CreaturePose.WALK;
        double perFrame = stride(clip) / clip.frames;
        int frameBefore = (int) Math.floor(hunt.travelled / perFrame);
        hunt.progress = Math.min(1.0, hunt.progress + speed / length);
        hunt.travelled += (hunt.progress - before) * length;
        // Stop-motion: nothing moves until the next drawing, and then the pose and the place change
        // together, a quarter of a stride at a time.
        if ((int) Math.floor(hunt.travelled / perFrame) == frameBefore && hunt.progress < 1.0) {
            return;
        }
        frame(player, hunt, creature);
    }

    /** Shows where it has got to: a new frame, a new place. */
    private static void frame(ServerPlayer player, Hunt hunt, Phantom creature) {
        Vec3 at = hunt.from.lerp(hunt.to, hunt.progress);
        Vec3 heading = hunt.to.subtract(hunt.from);
        float yaw = heading.horizontalDistanceSqr() > 1.0E-4 ? Spots.yawTowards(hunt.from, hunt.to) : creature.yaw();
        // Headroom where it is about to be: tall rooms it walks, three blocks it stoops, doorways it crawls.
        BlockPos ahead = BlockPos.containing(at.add(heading.lengthSqr() > 1.0E-6 ? heading.normalize().scale(0.45) : Vec3.ZERO));
        int room = Math.min(Field.clearance(player.serverLevel(), ahead, 4), Field.clearance(player.serverLevel(), BlockPos.containing(at), 4));
        CreaturePose clip = room >= 4 ? CreaturePose.WALK : room == 3 ? CreaturePose.STOOP_WALK : CreaturePose.CRAWL;
        int frame = (int) Math.floor(hunt.travelled / stride(clip) * clip.frames);
        int previous = creature.pose() == clip ? creature.frame() : -1;
        creature.moveTo(at, yaw).setPose(clip, frame);
        // Its body faces the way it walks; its face is on you.
        float toPlayer = Spots.yawTowards(at, player.position());
        creature.setHead(Mth.clamp(Mth.wrapDegrees(toPlayer - yaw), -110.0F, 110.0F), 0.0F);
        hunt.movedThisLapse = true;
        // A foot comes down on frames 0 and 4.
        int now = creature.frame();
        if (previous != now && (now == 0 || now == clip.frames / 2 || (previous >= 0 && crossed(previous, now, clip.frames)))) {
            RandomSource random = player.getRandom();
            double d = at.distanceTo(player.position());
            sound(player, creature, WakeRegistry.STEP.get(), (float) Mth.clamp(1.1 - d / 30.0, 0.15, 1.0), 0.85F + random.nextFloat() * 0.25F);
            if (random.nextInt(7) == 0) {
                sound(player, creature, WakeRegistry.CRACK.get(), 0.6F, 0.8F + random.nextFloat() * 0.4F);
            }
            if (d < 10.0 && random.nextInt(3) == 0) {
                sound(player, creature, WakeRegistry.WET_BREATH.get(), 0.55F, 0.85F + random.nextFloat() * 0.2F);
            }
        }
    }

    /** True if going from frame a to frame b passed a footfall (frame 0 or the half cycle). */
    private static boolean crossed(int a, int b, int frames) {
        int half = frames / 2;
        for (int f = (a + 1) % frames; f != (b + 1) % frames; f = (f + 1) % frames) {
            if (f == 0 || f == half) {
                return true;
            }
        }
        return false;
    }

    /**
     * At a cell: decides what it does next. Returns false when it is not walking (it attacked, it
     * waits, it went another way).
     */
    private static boolean plan(ServerPlayer player, HauntState state, Hunt hunt, Phantom creature) {
        ServerLevel level = player.serverLevel();
        RandomSource random = player.getRandom();
        Field field = field(player, hunt);
        if (field.size() == 0) {
            // You are somewhere it cannot find a floor to (swimming, say). It waits where it is.
            hunt.to = null;
            hunt.stepCooldown = 20;
            return false;
        }
        BlockPos cell = BlockPos.containing(creature.pos());
        if (!field.contains(cell)) {
            BlockPos near = field.nearest(cell, 3.5);
            if (near == null) {
                // It cannot reach you from there. It comes another way.
                Phantoms.remove(player, creature);
                hunt.creature = null;
                hunt.to = null;
                return false;
            }
            cell = near;
        }
        int distance = field.distance(cell);
        boolean canAttack = Attack.allowed(player, state);
        int closest = closest(state.stage(), canAttack);
        if (distance <= Math.max(1, closest)) {
            hunt.to = null;
            if (canAttack && distance <= 1) {
                // Arm's length. It stops there, its jaw a frame open, and waits. It bites if you
                // opened the menu with it already this close, or if you stay in the menu now that
                // it is. Never in your sleep, never while you are away, never through the pause
                // screen: that waits for you to press play.
                if (hunt.reachedAt < 0) {
                    hunt.reachedAt = hunt.lapseTicks;
                    float yaw = Spots.yawTowards(creature.pos(), player.position());
                    creature.moveTo(creature.pos(), yaw).setPose(Appearances.fitting(level, cell, CreaturePose.REACH, random))
                            .setHead(0.0F, 0.0F).setJaw(14.0F);
                    hunt.movedThisLapse = true;
                    sound(player, creature, WakeRegistry.WET_BREATH.get(), 0.7F, 0.8F);
                }
                if (hunt.lapse == Lapse.MENU && (hunt.startedInReach || hunt.lapseTicks - hunt.reachedAt >= REACH_GRACE)) {
                    Attack.begin(player, state, creature);
                    hunt.creature = null;
                }
                hunt.stepCooldown = 5;
                return false;
            }
            // As close as it may come. It stands there and waits for you to look.
            float yaw = Spots.yawTowards(creature.pos(), player.position());
            creature.setYaw(yaw);
            if (creature.pose().isMoving()) {
                creature.setPose(Appearances.fitting(level, cell, distance <= 3 ? CreaturePose.REACH : CreaturePose.STAND, random));
                creature.setHead(0.0F, 0.0F);
                hunt.movedThisLapse = true;
            }
            hunt.stepCooldown = 10;
            if (random.nextInt(6) == 0) {
                sound(player, creature, WakeRegistry.WET_BREATH.get(), 0.5F, 0.85F + random.nextFloat() * 0.2F);
            }
            return false;
        }
        BlockPos next = field.toward(cell);
        if (next == null) {
            hunt.to = null;
            return false;
        }
        hunt.cells = 1;
        // It covers ground quickly while it is far.
        if (distance > 12) {
            BlockPos further = field.toward(next);
            if (further != null && field.distance(further) > closest) {
                next = further;
                hunt.cells = 2;
            }
        }
        hunt.from = creature.pos();
        hunt.to = jitter(next, random);
        hunt.progress = 0.0;
        hunt.steps++;
        // A closed door in the way costs it a few seconds, and you hear the latch.
        if (Field.closedDoor(level, next) || Field.closedDoor(level, next.above())) {
            BlockPos door = Field.closedDoor(level, next) ? next : next.above();
            hunt.stepCooldown = 40;
            Household.send(player, net.minecraft.sounds.SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, Vec3.atCenterOf(door),
                    0.7F * WakeConfig.get().soundVolume, 0.85F);
            Director.schedule(46, () -> Household.send(player, net.minecraft.sounds.SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS,
                    Vec3.atCenterOf(door), 0.6F * WakeConfig.get().soundVolume, 0.8F));
        }
        return true;
    }

    /** Not quite the middle of the block: nothing about it is tidy. */
    private static Vec3 jitter(BlockPos cell, RandomSource random) {
        return Vec3.atBottomCenterOf(cell).add((random.nextDouble() - 0.5) * 0.3, 0.0, (random.nextDouble() - 0.5) * 0.3);
    }

    private static void sound(ServerPlayer player, Phantom creature, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        Household.send(player, sound, SoundSource.HOSTILE, creature.pos().add(0.0, 1.6, 0.0), volume * WakeConfig.get().soundVolume, pitch);
    }

    // ---- stopping -------------------------------------------------------------------------

    /**
     * The lapse is over: it is frozen wherever it got to. Never dead centre: if that is where it
     * ended up, it is a little to one side. And if it is near a wall's edge, it is behind it,
     * leaning out, fingers curled round the corner.
     */
    private static void settle(ServerPlayer player, HauntState state, Hunt hunt) {
        Phantom creature = hunt.creature;
        ServerLevel level = player.serverLevel();
        RandomSource random = player.getRandom();
        Field field = field(player, hunt);
        BlockPos cell = BlockPos.containing(creature.pos());
        Vec3 head = creature.pos().add(0.0, creature.pose().height * 0.85, 0.0);
        double dist = head.distanceTo(player.getEyePosition());
        if (Appearances.angleFromView(player, head) < 24.0F && dist < 28.0 && Spots.visibleFrom(player, cell)) {
            int d = Math.max(2, field.distance(cell));
            BlockPos aside = field.pick(Math.max(1, d - 2), d + 3,
                    key -> Appearances.angleFromView(player, Vec3.atBottomCenterOf(BlockPos.of(key)).add(0.0, 2.2, 0.0)) > 32.0F, random);
            if (aside == null) {
                Phantoms.remove(player, creature);
                hunt.creature = null;
                return;
            }
            cell = aside;
            creature.moveTo(jitter(aside, random), creature.yaw());
        }
        if (!dark(level, cell) && level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, cell.above()) >= 11) {
            int d0 = Math.max(1, field.distance(cell));
            BlockPos dim = field.pick(Math.max(1, d0 - 6), d0 + 6, key -> dark(level, BlockPos.of(key))
                    && Appearances.angleFromView(player, Vec3.atBottomCenterOf(BlockPos.of(key)).add(0.0, 2.2, 0.0)) > 26.0F, random);
            if (dim != null) {
                cell = dim;
                creature.moveTo(jitter(dim, random), creature.yaw());
            }
        }
        Vec3 at = creature.pos();
        float yaw = Spots.yawTowards(at, player.position());
        int distance = field.distance(cell);
        if (distance >= 0 && distance <= 16 && peek(player, creature, cell, level, random)) {
            return;
        }
        CreaturePose wanted;
        float r = random.nextFloat();
        if (distance >= 0 && distance <= 4) {
            wanted = r < 0.45F ? CreaturePose.REACH : r < 0.75F ? CreaturePose.STAND : CreaturePose.CROUCH;
        } else {
            wanted = r < 0.55F ? CreaturePose.STAND : r < 0.8F ? CreaturePose.CROUCH : creature.pose();
        }
        creature.moveTo(at, yaw).setPose(Appearances.fitting(level, cell, wanted, random)).setHead(0.0F, 0.0F).setTouch(0.9, true);
    }

    /** Behind a wall, leaning out past its edge, if there is such a wall right here. */
    private static boolean peek(ServerPlayer player, Phantom creature, BlockPos cell, ServerLevel level, RandomSource random) {
        for (BlockPos candidate : BlockPos.betweenClosed(cell.offset(-1, 0, -1), cell.offset(1, 0, 1))) {
            if (!Field.walkable(level, candidate) || Field.clearance(level, candidate, 3) < 3) {
                continue;
            }
            Vec3 at = Vec3.atBottomCenterOf(candidate);
            float yaw = Spots.yawTowards(at, player.position());
            Direction facing = Direction.fromYRot(yaw);
            BlockPos front = candidate.relative(facing);
            if (Field.passable(level, front.above()) || Field.passable(level, front.above(2))) {
                continue;
            }
            Direction right = facing.getClockWise();
            boolean rightOpen = Field.passable(level, front.relative(right).above(2)) && Field.passable(level, candidate.relative(right).above(2));
            boolean leftOpen = Field.passable(level, front.relative(right.getOpposite()).above(2))
                    && Field.passable(level, candidate.relative(right.getOpposite()).above(2));
            if (!rightOpen && !leftOpen) {
                continue;
            }
            boolean useRight = rightOpen && (!leftOpen || random.nextBoolean());
            // Square on to the wall, a little toward the side it leans from.
            Vec3 offset = Vec3.atLowerCornerOf(useRight ? right.getNormal() : right.getOpposite().getNormal()).scale(0.22);
            creature.moveTo(at.add(offset), facing.toYRot()).setPose(useRight ? CreaturePose.PEEK_RIGHT : CreaturePose.PEEK_LEFT)
                    .setHead(0.0F, 0.0F).setTouch(0.9, true);
            return true;
        }
        return false;
    }

    // ---- lost time all at once ------------------------------------------------------------

    /** A real hitch: the steps it took while the game was not drawing are taken all at once. */
    public static void jump(ServerPlayer player, HauntState state, int millis) {
        if (!hunts(player, state)) {
            return;
        }
        Hunt hunt = of(player);
        Phantom creature = creature(player);
        if (creature == null) {
            return;
        }
        int ticks = Mth.clamp(millis / 50, 1, 8 * interval(state.stage(), Lapse.MENU));
        Lapse was = hunt.lapse;
        hunt.lapse = Lapse.MENU;
        for (int i = 0; i < ticks && creature(player) != null; i++) {
            walk(player, state, hunt, creature);
        }
        hunt.lapse = was;
        if (creature(player) != null) {
            settle(player, state, hunt);
        }
    }
}
