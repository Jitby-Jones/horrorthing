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
        boolean frameB;
        int steps;
        boolean movedThisLapse;
        boolean hunting;
    }

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
                hunting = creature(player) != null;
            }
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
        if (!Haunt.awake(state) || !Haunt.eligible(player) || StoppedManager.isIn(player) || player.isCreative()) {
            return false;
        }
        Stage stage = state.stage();
        return stage.atLeast(Stage.SIGHTINGS) && !stage.atLeast(Stage.HUSH) && !Beats.hush(state, player.serverLevel());
    }

    private static void begin(ServerPlayer player, HauntState state, Hunt hunt) {
        if (DemoScript.ENABLED) {
            TheWake.LOG.info("[demo] lapse begins: {}", hunt.lapse);
        }
        hunt.lapseTicks = 0;
        hunt.movedThisLapse = false;
        hunt.stepCooldown = 0;
    }

    private static void end(ServerPlayer player, HauntState state, Hunt hunt, Lapse was) {
        int seconds = hunt.lapseTicks / 20;
        if (hunt.creature != null && !hunt.creature.isRemoved() && hunt.movedThisLapse && was != Lapse.SLEEP) {
            settle(player, state, hunt);
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
        if (--hunt.stepCooldown > 0) {
            return;
        }
        Stage stage = state.stage();
        Phantom creature = creature(player);
        if (creature == null || creature.pos().distanceToSqr(player.position()) > 48.0 * 48.0 || creature.scale() != 1.0F) {
            if (hunt.lapseTicks < arrivalDelay(stage, hunt.lapse)) {
                hunt.stepCooldown = 1;
                return;
            }
            arrive(player, state, hunt);
            hunt.stepCooldown = interval(stage, hunt.lapse) * 2;
            return;
        }
        hunt.stepCooldown = interval(stage, hunt.lapse);
        step(player, state, hunt, creature);
    }

    /** Ticks per block. Sleep is fast: it has a few seconds of fading screen to reach you. */
    private static int interval(Stage stage, Lapse lapse) {
        if (lapse == Lapse.SLEEP) {
            return 5;
        }
        int base = stage.atLeast(Stage.HUNT) ? 11 : stage.atLeast(Stage.ROUTINE) ? 16 : 24;
        return lapse == Lapse.IDLE ? base * 3 / 2 : base;
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
        BlockPos root = player.blockPosition();
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
        boolean side = random.nextFloat() < 0.6F;
        int min = state.stage().atLeast(Stage.HUNT) ? 13 : 16;
        int max = min + 10;
        BlockPos cell = field.pick(min, max, key -> {
            BlockPos p = BlockPos.of(key);
            float a = Appearances.angleFromView(player, Vec3.atBottomCenterOf(p).add(0.0, 1.8, 0.0));
            return side ? a > 40.0F && a < 75.0F : a > 100.0F;
        }, random);
        if (cell == null) {
            cell = field.pick(min, max, key -> Appearances.angleFromView(player, Vec3.atCenterOf(BlockPos.of(key))) > 40.0F, random);
        }
        if (cell == null) {
            return;
        }
        Vec3 at = jitter(cell, random);
        Phantom creature = Appearances.still(player, cell, Spots.yawTowards(at, player.position()), CreaturePose.WALK_A);
        creature.moveTo(at, creature.yaw()).setTouch(0.0, false);
        adopt(player, creature);
        hunt.movedThisLapse = true;
        sound(player, creature, WakeRegistry.CRACK.get(), 0.7F, 0.9F + random.nextFloat() * 0.2F);
        if (DemoScript.ENABLED) {
            TheWake.LOG.info("[demo] it arrives {} blocks away", String.format("%.1f", at.distanceTo(player.position())));
        }
    }

    private static void step(ServerPlayer player, HauntState state, Hunt hunt, Phantom creature) {
        ServerLevel level = player.serverLevel();
        RandomSource random = player.getRandom();
        Field field = field(player, hunt);
        BlockPos cell = BlockPos.containing(creature.pos());
        if (!field.contains(cell)) {
            BlockPos near = field.nearest(cell, 3.5);
            if (near == null) {
                // It cannot reach you from there. It comes another way.
                Phantoms.remove(player, creature);
                hunt.creature = null;
                return;
            }
            cell = near;
        }
        int distance = field.distance(cell);
        boolean canAttack = Attack.allowed(player, state);
        int closest = closest(state.stage(), canAttack);
        if (distance <= Math.max(1, closest)) {
            if (canAttack && distance <= 1) {
                Attack.begin(player, state, creature);
                hunt.creature = null;
                return;
            }
            // As close as it may come. It stands there and waits for you to look.
            float yaw = Spots.yawTowards(creature.pos(), player.position());
            creature.setYaw(yaw);
            if (creature.pose().isWalkFrame() || creature.pose().isCrawlFrame()) {
                creature.setPose(Appearances.fitting(level, cell, distance <= 3 ? CreaturePose.REACH : CreaturePose.STAND, random));
                hunt.movedThisLapse = true;
            }
            if (random.nextInt(6) == 0) {
                sound(player, creature, WakeRegistry.WET_BREATH.get(), 0.5F, 0.85F + random.nextFloat() * 0.2F);
            }
            return;
        }
        BlockPos next = field.toward(cell);
        if (next == null) {
            return;
        }
        // Two cells per step when it is far: it covers ground while you are away.
        if (distance > 12) {
            BlockPos further = field.toward(next);
            if (further != null && field.distance(further) > closest) {
                next = further;
            }
        }
        BlockPos ahead = field.toward(next);
        Vec3 at = jitter(next, random);
        Vec3 facing = ahead != null && !ahead.equals(next) ? Vec3.atBottomCenterOf(ahead) : player.position();
        float yaw = Spots.yawTowards(at, facing);
        hunt.frameB = !hunt.frameB;
        int room = Field.clearance(level, next, 3);
        CreaturePose pose = room >= 3 ? (hunt.frameB ? CreaturePose.WALK_B : CreaturePose.WALK_A)
                : (hunt.frameB ? CreaturePose.CRAWL_B : CreaturePose.CRAWL_A);
        creature.moveTo(at, yaw).setPose(pose);
        // Its body faces the way it walks; its face is on you.
        float toPlayer = Spots.yawTowards(at, player.position());
        creature.setHead(Mth.clamp(Mth.wrapDegrees(toPlayer - yaw), -110.0F, 110.0F), 0.0F);
        hunt.movedThisLapse = true;
        hunt.steps++;
        double d = at.distanceTo(player.position());
        sound(player, creature, WakeRegistry.STEP.get(), (float) Mth.clamp(1.1 - d / 30.0, 0.15, 1.0), 0.85F + random.nextFloat() * 0.25F);
        if (random.nextInt(7) == 0) {
            sound(player, creature, WakeRegistry.CRACK.get(), 0.6F, 0.8F + random.nextFloat() * 0.4F);
        }
        if (d < 10.0 && random.nextInt(3) == 0) {
            sound(player, creature, WakeRegistry.WET_BREATH.get(), 0.55F, 0.85F + random.nextFloat() * 0.2F);
        }
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
        int steps = Mth.clamp(millis / (interval(state.stage(), Lapse.MENU) * 50), 1, 8);
        for (int i = 0; i < steps && creature(player) != null; i++) {
            step(player, state, hunt, creature);
        }
        if (creature(player) != null) {
            settle(player, state, hunt);
        }
    }
}
