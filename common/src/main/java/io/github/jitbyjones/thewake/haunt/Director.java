package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import io.github.jitbyjones.thewake.stopped.StoppedManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.phys.Vec3;

/**
 * The server-side heartbeat. Cheap per-tick bookkeeping (activity, routes, nights, sleep) plus the
 * occasional decision to let something happen. Heavy work is spread over tick intervals.
 */
public final class Director {
    private static final List<Scheduled> SCHEDULED = new ArrayList<>();
    private static final List<Scheduled> PENDING = new ArrayList<>();
    private static long serverTicks;

    private record Scheduled(long at, Runnable task) {}

    private Director() {}

    /** Runs {@code task} on the server thread after {@code delayTicks} server ticks. */
    public static void schedule(int delayTicks, Runnable task) {
        PENDING.add(new Scheduled(serverTicks + Math.max(1, delayTicks), task));
    }

    public static void onServerStopped() {
        SCHEDULED.clear();
        PENDING.clear();
        serverTicks = 0;
        Phantoms.onServerStopped();
        Hunter.onServerStopped();
        Pursuit.onServerStopped();
        Possession.onServerStopped();
        LastNight.onServerStopped();
    }

    public static void tick(MinecraftServer server) {
        serverTicks++;
        runScheduled();

        long gameTime = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            HauntState state = Haunt.state(player);
            try {
                tickPlayer(player, state, gameTime);
            } catch (RuntimeException e) {
                TheWake.LOG.error("Haunt tick failed for {}", player.getGameProfile().getName(), e);
            }
            Haunt.flushSync(player, state);
        }
        StoppedManager.tickServer(server);
        Phantoms.tick(server);
    }

    private static void runScheduled() {
        if (!PENDING.isEmpty()) {
            SCHEDULED.addAll(PENDING);
            PENDING.clear();
        }
        List<Scheduled> due = new ArrayList<>();
        Iterator<Scheduled> it = SCHEDULED.iterator();
        while (it.hasNext()) {
            Scheduled s = it.next();
            if (s.at <= serverTicks) {
                due.add(s);
                it.remove();
            }
        }
        for (Scheduled s : due) {
            try {
                s.task.run();
            } catch (RuntimeException e) {
                TheWake.LOG.error("Scheduled haunt task failed", e);
            }
        }
    }

    private static void tickPlayer(ServerPlayer player, HauntState state, long gameTime) {
        Possession.tick(player, state, gameTime);
        if (StoppedManager.isIn(player)) {
            return;
        }
        if (!Haunt.eligible(player)) {
            state.idleTicks = 0;
            return;
        }
        ServerLevel level = player.serverLevel();
        trackSleep(player, state, level);
        trackActivity(player, state);
        Hunter.tick(player, state);
        LastNight.tick(player, state);
        Pursuit.tick(player, state, gameTime);
        if (gameTime % 20L == 0L) {
            Tension.tick(player, state, gameTime);
            Torment.tick(player, state, gameTime);
            syncTemper(player, state);
        }
        if (gameTime % 10L == 0L) {
            trackSteps(player, state);
        }
        if (gameTime % 20L == 0L) {
            NightWatch.tick(player, state, level);
            if (!player.isSleeping()) {
                state.setRestTicks(Haunt.stat(player, Stats.TIME_SINCE_REST));
            }
            newDay(player, state, level);
        }
        if (state.ended()) {
            afterward(player, state, level, gameTime);
            return;
        }
        if (!Haunt.awake(state)) {
            return;
        }
        Household.tick(player, state, level, gameTime);
        Theft.tick(player, state, level, gameTime);
    }

    /** Sleep mods may pass the night without vanilla's skip; catch any night slept through. */
    private static void trackSleep(ServerPlayer player, HauntState state, ServerLevel level) {
        boolean sleeping = player.isSleeping();
        if (sleeping && !state.wasSleeping) {
            state.sleepStartDayTime = level.getDayTime();
            state.sleepProcessed = false;
        } else if (!sleeping && state.wasSleeping) {
            if (!state.sleepProcessed && state.sleepStartDayTime >= 0) {
                Lapses.onSleptThrough(player, state, state.sleepStartDayTime);
            }
            state.sleepStartDayTime = -1L;
        }
        state.wasSleeping = sleeping;
    }

    private static void trackActivity(ServerPlayer player, HauntState state) {
        Vec3 pos = player.position();
        boolean active = state.lastActivityPos == null
                || pos.distanceToSqr(state.lastActivityPos) > 0.0004
                || Math.abs(player.getYRot() - state.lastActivityYRot) > 0.5F
                || Math.abs(player.getXRot() - state.lastActivityXRot) > 0.5F
                || player.swinging
                || player.isSleeping()
                || player.containerMenu != player.inventoryMenu;
        if (active) {
            state.lastActivityPos = pos;
            state.lastActivityYRot = player.getYRot();
            state.lastActivityXRot = player.getXRot();
            if (state.idleLapseOpen) {
                Lapses.onIdleEnd(player, state);
            }
            state.idleTicks = 0;
            return;
        }
        state.idleTicks++;
        if (state.idleLapseOpen) {
            state.idleLapseTicks++;
        } else if (WakeConfig.get().afkEvents && state.idleTicks >= WakeConfig.get().afkSeconds * 20) {
            Lapses.onIdleBegin(player, state);
        }
    }

    private static void trackSteps(ServerPlayer player, HauntState state) {
        if (!player.onGround() || player.isPassenger() || player.isSleeping()) {
            return;
        }
        state.crumbs().add(player.blockPosition());
        if (state.bed() != null && player.level().getGameTime() % 40L == 0L && player.blockPosition().closerThan(state.bed(), 40.0)) {
            state.today().add(player.blockPosition());
        }
    }

    /** The form it shows changed: tell the client, which dresses its images in it. */
    private static void syncTemper(ServerPlayer player, HauntState state) {
        int form = Temper.form(state).ordinal();
        if (form != state.syncedForm || player.level().getGameTime() % 100L == 0L) {
            state.syncedForm = form;
            state.syncDirty = true;
        }
    }

    /** A new in-game day: today's routes become yesterday's. Retracing them teaches it. */
    private static void newDay(ServerPlayer player, HauntState state, ServerLevel level) {
        long day = Haunt.day(level);
        if (state.routeDay() == day) {
            return;
        }
        boolean first = state.routeDay() < 0;
        if (!first) {
            Temper.newDay(player, state, level.getGameTime() - state.reputation().lastChaseTick() > 24000L
                    && level.getGameTime() - state.reputation().lastHurtTick() > 24000L);
        }
        float overlap = state.rollRoutes(day);
        if (!first && overlap >= 0.5F) {
            Haunt.gain(player, state, 0.008F);
        }
    }

    /** After an ending: the one thing that follows you. */
    private static void afterward(ServerPlayer player, HauntState state, ServerLevel level, long gameTime) {
        if (gameTime % 40L != 0L) {
            return;
        }
        if (state.endingKept()) {
            StoppedManager.keepBedVisible(player, state, level);
            return;
        }
        BlockPos old = state.oldBed();
        if (old == null || !old.closerThan(player.blockPosition(), 32.0) || old.closerThan(player.blockPosition(), 8.0)) {
            return;
        }
        if (!state.ready("epilogue", gameTime) || Phantoms.nearest(player, 64.0, p -> true) != null) {
            return;
        }
        state.cooldown("epilogue", gameTime, 24000L);
        BlockPos side = Spots.groundNear(level, old.getX(), old.getY(), old.getZ(), 2);
        if (side != null) {
            Vec3 at = Vec3.atBottomCenterOf(side);
            Phantoms.create(player, at, Spots.yawTowards(at, player.position()), Phantom.Mode.STILL, CreaturePose.STAND)
                    .setTouch(1.2, false).setLapseGeneration(-1);
        }
    }
}
