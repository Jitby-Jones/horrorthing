package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.PausePayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.PhantomBehavior;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import io.github.jitbyjones.thewake.stopped.StoppedManager;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * Every way the player loses time. Each one bumps the lapse counter (old images vanish: that is
 * when it moves), may feed clarity, and may leave one new image behind.
 */
public final class Lapses {
    private Lapses() {}

    public static void bootstrap() {
        Phantoms.setTouchHandler(Lapses::onTouched);
    }

    // ---- sleeping through the night -------------------------------------------------------

    /** Vanilla skip: called from the mixin at the start of ServerLevel#wakeUpAllPlayers, sleepers still in bed. */
    public static void onNightSkipped(ServerLevel level, long dayTimeBefore) {
        if (!WakeConfig.get().allowsDimension(level.dimension().location().toString())
                || !level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            return;
        }
        List<ServerPlayer> sleepers = level.players().stream().filter(LivingEntity::isSleeping).collect(Collectors.toList());
        for (ServerPlayer player : sleepers) {
            HauntState state = Haunt.state(player);
            state.sleepProcessed = true;
            try {
                if (StoppedManager.isIn(player)) {
                    StoppedManager.onVanillaSkip(player);
                    continue;
                }
                NightShift.process(player, state, level, dayTimeBefore);
                // It had the night. For now, that is enough.
                Temper.provoke(player, state, Temper.Provocation.SLEPT);
            } catch (RuntimeException e) {
                TheWake.LOG.error("Night shift failed for {}", player.getGameProfile().getName(), e);
            }
        }
    }

    /**
     * Sleep mods that fast-forward instead of skipping: the player was asleep while the night
     * passed. Called when they stop sleeping without the vanilla skip having fired.
     */
    static void onSleptThrough(ServerPlayer player, HauntState state, long startDayTime) {
        ServerLevel level = player.serverLevel();
        if (StoppedManager.isIn(player) || !Haunt.eligible(player)) {
            return;
        }
        if (level.getDayTime() - startDayTime >= 3000L && !Haunt.isNight(level)) {
            NightShift.process(player, state, level, startDayTime);
            Temper.provoke(player, state, Temper.Provocation.SLEPT);
        }
    }

    // ---- going idle -----------------------------------------------------------------------

    /** Standing idle is a lapse: the hunter moves while it lasts (see {@link Hunter}). */
    static void onIdleBegin(ServerPlayer player, HauntState state) {
        state.idleLapseOpen = true;
        state.idleLapseTicks = 0;
    }

    static void onIdleEnd(ServerPlayer player, HauntState state) {
        state.idleLapseOpen = false;
        int minutes = state.idleLapseTicks / 1200;
        state.idleLapseTicks = 0;
        state.lapse();
        Haunt.gain(player, state, Math.min(0.015F, 0.004F + minutes * 0.002F));
        if (Haunt.awake(state) && state.stage() == Stage.WATCHING) {
            Hunter.maybeGiant(player, state);
        }
    }

    // ---- the pause menu (singleplayer) ----------------------------------------------------

    /**
     * Singleplayer, on resume. The pause itself was a lapse like any menu (see {@link Hunter});
     * this report only matters in the Stopped, which moves on by the real time that passed.
     */
    public static void onPauseReport(ServerPlayer player, PausePayload pause) {
        if (!WakeConfig.get().pauseEvents || !player.server.isSingleplayerOwner(player.getGameProfile()) || player.server.isPublished()) {
            return;
        }
        if (StoppedManager.isIn(player)) {
            StoppedManager.onPaused(player, Mth.clamp(pause.millis(), 0, 3_600_000));
        }
    }

    // ---- real hitches (per client) --------------------------------------------------------

    public static void onHitch(ServerPlayer player, int millis) {
        if (!WakeConfig.get().hitchEvents || millis < 1000 || millis > 30_000 || !Haunt.eligible(player) || StoppedManager.isIn(player)) {
            return;
        }
        HauntState state = Haunt.state(player);
        int now = player.server.getTickCount();
        if (now - state.lastHitchTick < 2400 || !Haunt.awake(state)) {
            return;
        }
        if (!state.takeHitch(Haunt.day(player.serverLevel()), 3)) {
            return;
        }
        state.lastHitchTick = now;
        state.lapse();
        Haunt.gain(player, state, 0.003F);
        // The frames you did not see: it took its steps in them.
        Hunter.jump(player, state, millis);
    }

    // ---- logging off ----------------------------------------------------------------------

    public static void onLogin(ServerPlayer player) {
        HauntState state = Haunt.state(player);
        state.syncDirty = true;
        state.lastActivityPos = null;
        if (StoppedManager.shouldBeginOnLogin(player, state)) {
            Director.schedule(60, () -> {
                if (!player.hasDisconnected()) {
                    StoppedManager.beginOnLogin(player, state);
                }
            });
            return;
        }
        long last = state.lastLogoutMillis();
        if (last > 0L) {
            long away = (System.currentTimeMillis() - last) / 1000L;
            Director.schedule(50, () -> {
                if (!player.hasDisconnected()) {
                    Torment.lostTime(player, state, (int) Math.min(Integer.MAX_VALUE, away));
                }
            });
        }
        if (last <= 0L || System.currentTimeMillis() - last < 30 * 60_000L) {
            return;
        }
        state.lapse();
        Haunt.gain(player, state, 0.003F);
        if (!Haunt.awake(state) || !Haunt.eligible(player) || !state.peakStage().atLeast(Stage.SIGHTINGS)) {
            return;
        }
        BlockPos bed = state.bed();
        ServerLevel level = player.serverLevel();
        if (bed == null || !bed.closerThan(player.blockPosition(), 64.0) || Beats.silent(player)) {
            return;
        }
        Director.schedule(40, () -> {
            if (player.hasDisconnected() || !level.isLoaded(bed)) {
                return;
            }
            // While you were gone it stood by your bed the whole time.
            Direction facing = Spots.bedFacing(level, bed);
            BlockPos side = facing == null ? null : Spots.bedside(level, bed, facing, player.position());
            if (side != null && !Spots.visibleFrom(player, side)) {
                Vec3 at = Vec3.atBottomCenterOf(side);
                Hunter.adopt(player, Appearances.still(player, side, Spots.yawTowards(at, Vec3.atCenterOf(bed)), CreaturePose.BEDSIDE)
                        .setTouch(0.0, false));
            }
        });
    }

    public static void onLogout(ServerPlayer player) {
        HauntState state = Haunt.state(player);
        state.setLastLogoutMillis(System.currentTimeMillis());
        state.idleLapseOpen = false;
        StoppedManager.onLogout(player);
        Phantoms.forget(player.getUUID());
        Hunter.forget(player.getUUID());
    }

    // ---- keeping the night ----------------------------------------------------------------

    static void onNightKept(ServerPlayer player, HauntState state) {
        int run = state.consecutiveKept();
        state.recordKeptNight();
        // Each consecutive kept night pushes it back less. Every one of them is owed.
        Haunt.gain(player, state, (float) (-0.08 * Math.pow(0.7, run)));
        // And the morning film in that room goes back a frame.
        if (state.bed() != null) {
            state.advanceFilm(Spots.bedHead(player.serverLevel(), state.bed()), -1);
        }
        Advancements.award(player, "night_owl");
        // You kept the night from it. It minds.
        Temper.provoke(player, state, Temper.Provocation.KEPT_NIGHT);
        Temper.learn(player, state, Reputation.Tactic.SLEEPLESS);
    }

    // ---- walking into it ------------------------------------------------------------------

    /** Touching an image costs time: the view fills with night; an hour later it is gone. */
    static void onTouched(ServerPlayer player, Phantom phantom) {
        HauntState state = Haunt.state(player);
        WakeNetwork.send(player, FxPayload.of(FxPayload.BLACKOUT, 44));
        Household.send(player, io.github.jitbyjones.thewake.registry.WakeRegistry.BREATH.get(), net.minecraft.sounds.SoundSource.AMBIENT,
                player.getEyePosition(), 0.6F * WakeConfig.get().soundVolume, 0.95F);
        state.lapse();
        ServerLevel level = player.serverLevel();
        // An hour the first time today, two the second, three after that.
        int times = Math.min(3, state.touch(Haunt.day(level)));
        player.getFoodData().addExhaustion(5.0F * times);
        if (player.server.isSingleplayer() && level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            level.setDayTime(level.getDayTime() + 1000L * times);
        }
    }
}
