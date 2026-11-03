package io.github.jitbyjones.thewake.haunt;

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
        }
    }

    // ---- going idle -----------------------------------------------------------------------

    static void onIdleBegin(ServerPlayer player, HauntState state) {
        state.idleLapseOpen = true;
        state.idleLapseTicks = 0;
        if (!Haunt.awake(state) || !state.peakStage().atLeast(Stage.SIGHTINGS) || Beats.hush(state, player.serverLevel())) {
            return;
        }
        long gameTime = player.level().getGameTime();
        if (!state.ready("idle_sighting", gameTime) || Beats.silent(player)) {
            return;
        }
        Phantom phantom = Exposures.sighting(player, state, 28.0F);
        if (phantom == null) {
            return;
        }
        state.cooldown("idle_sighting", gameTime, 8000L);
        // Placed during the lapse, so it is still there when the player comes back; it develops
        // while nobody is watching, and is fully sharp by then.
        phantom.setLapseGeneration(state.lapseCount() + 1).setOpacity(0.0F).setFadeSpeed(0.006F);
        phantom.setBehavior(new PhantomBehavior() {
            private boolean developed;

            @Override
            public void tick(Phantom p, ServerPlayer owner) {
                if (!developed) {
                    developed = true;
                    p.setOpacity(0.9F);
                }
                if (!state.idleLapseOpen) {
                    p.setFadeSpeed(1.0F);
                }
            }
        });
    }

    static void onIdleEnd(ServerPlayer player, HauntState state) {
        state.idleLapseOpen = false;
        int minutes = state.idleLapseTicks / 1200;
        state.idleLapseTicks = 0;
        state.lapse();
        Haunt.gain(player, state, Math.min(0.015F, 0.004F + minutes * 0.002F));
    }

    // ---- the pause menu (singleplayer) ----------------------------------------------------

    public static void onPauseReport(ServerPlayer player, PausePayload pause) {
        if (!WakeConfig.get().pauseEvents || !player.server.isSingleplayerOwner(player.getGameProfile()) || player.server.isPublished()) {
            return;
        }
        HauntState state = Haunt.state(player);
        int millis = Mth.clamp(pause.millis(), 0, 3_600_000);
        if (StoppedManager.isIn(player)) {
            // Pausing in the Stopped moves it forward by the real time that passed.
            StoppedManager.onPaused(player, millis);
            return;
        }
        if (pause.figureSeen() && state.peakStage().atLeast(Stage.ROUTINE)) {
            state.setFlag("pause_figure");
        }
        // Alt-tabbing to look something up is not a lapse. Walking away from the game is.
        if (millis < 120_000 || !Haunt.eligible(player)) {
            return;
        }
        state.lapse();
        Haunt.gain(player, state, Math.min(0.01F, 0.004F + millis / 60_000.0F * 0.001F));
        long gameTime = player.level().getGameTime();
        if (Haunt.awake(state) && state.peakStage().atLeast(Stage.SIGHTINGS) && !pause.figureSeen()
                && !Beats.hush(state, player.serverLevel()) && state.ready("pause_sighting", gameTime) && !Beats.silent(player)) {
            if (Exposures.sighting(player, state, 22.0F) != null) {
                state.cooldown("pause_sighting", gameTime, 9000L);
            }
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
        long gameTime = player.level().getGameTime();
        if (state.peakStage().atLeast(Stage.SIGHTINGS) && !Beats.hush(state, player.serverLevel())
                && state.ready("hitch_sighting", gameTime) && player.getRandom().nextFloat() < 0.5F) {
            if (Exposures.sighting(player, state, 20.0F) != null) {
                state.cooldown("hitch_sighting", gameTime, 12000L);
            }
        }
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
            Direction facing = Spots.bedFacing(level, bed);
            BlockPos side = facing == null ? null : Spots.bedside(level, bed, facing, player.position());
            if (side != null) {
                Vec3 at = Vec3.atBottomCenterOf(side);
                Exposures.still(player, state, side, Spots.yawTowards(at, Vec3.atCenterOf(bed)), Phantom.Stance.LOOK_DOWN);
            }
        });
    }

    public static void onLogout(ServerPlayer player) {
        HauntState state = Haunt.state(player);
        state.setLastLogoutMillis(System.currentTimeMillis());
        state.idleLapseOpen = false;
        StoppedManager.onLogout(player);
        Phantoms.forget(player.getUUID());
    }

    // ---- keeping the night ----------------------------------------------------------------

    static void onNightKept(ServerPlayer player, HauntState state) {
        int run = state.consecutiveKept();
        state.recordKeptNight();
        // Each consecutive kept night pushes it back less. Every one of them is owed.
        Haunt.gain(player, state, (float) (-0.06 * Math.pow(0.7, run)));
        Advancements.award(player, "night_owl");
    }

    // ---- walking into it ------------------------------------------------------------------

    /** Touching an image costs time: the view fills with night; an hour later it is gone. */
    static void onTouched(ServerPlayer player, Phantom phantom) {
        HauntState state = Haunt.state(player);
        WakeNetwork.send(player, FxPayload.of(FxPayload.BLACKOUT, 44));
        Household.send(player, io.github.jitbyjones.thewake.registry.WakeRegistry.BREATH.get(), net.minecraft.sounds.SoundSource.AMBIENT,
                player.getEyePosition(), 0.6F * WakeConfig.get().soundVolume, 0.95F);
        state.lapse();
        player.getFoodData().addExhaustion(5.0F);
        ServerLevel level = player.serverLevel();
        if (player.server.isSingleplayer() && level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            level.setDayTime(level.getDayTime() + 1000L);
        }
    }
}
