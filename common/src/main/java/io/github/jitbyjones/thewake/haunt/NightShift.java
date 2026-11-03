package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.stopped.StoppedManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/**
 * The night that was skipped, as the Wake spent it. Runs when the sleep skip happens, while the
 * sleeper is still in bed; the visible part runs a tick later, after they stand up.
 */
final class NightShift {
    private NightShift() {}

    static void process(ServerPlayer player, HauntState state, ServerLevel level, long dayTimeBefore) {
        BlockPos sleepPos = player.getSleepingPos().orElse(null);
        long timeOfDay = dayTimeBefore % 24000L;
        int nightTicks = (int) Math.max(0L, 24000L - timeOfDay);

        state.lapse();
        state.setLastNightSkipDay(dayTimeBefore / 24000L);
        state.incrementNightsSkipped();
        state.setLastLayDownDayTime(state.sleepStartDayTime >= 0 ? state.sleepStartDayTime : dayTimeBefore);

        BlockPos head = sleepPos == null ? null : Spots.bedHead(level, sleepPos);
        Direction facing = head == null ? null : Spots.bedFacing(level, head);
        if (head == null || facing == null) {
            Ledger.snapshot(player, state);
            return;
        }

        int nightsNear = state.nightsNear(head, 32.0);
        boolean newPlace = nightsNear == 0 && state.nightsSkipped() > 1;
        // Routine teaches it fastest. Somewhere new is somewhere it has to find you first.
        float familiarity = nightsNear >= 3 ? 1.0F : 0.5F + 0.17F * nightsNear;
        state.recordNightAt(head);
        state.setBed(head, facing.get2DDataValue());

        if (state.ended()) {
            if (!state.endingKept() && state.oldBed() != null && head.closerThan(state.oldBed(), 4.0)) {
                StoppedManager.reawaken(player, state);
            }
            Ledger.snapshot(player, state);
            return;
        }
        if (!Haunt.awake(state)) {
            Ledger.snapshot(player, state);
            return;
        }

        Haunt.gain(player, state, 0.038F * familiarity);

        if (StoppedManager.ready(player, state)) {
            StoppedManager.begin(player, state, level, head, facing, dayTimeBefore);
            return;
        }

        // Act II onward: you did not rest. Time Since Last Rest keeps counting; phantoms come anyway.
        if (state.peakStage().atLeast(Stage.SIGHTINGS)) {
            int rest = state.restTicks() + nightTicks;
            Director.schedule(1, () -> {
                if (!player.hasDisconnected()) {
                    player.getStats().setValue(player, Stats.CUSTOM.get(Stats.TIME_SINCE_REST), rest);
                }
            });
        }

        Runnable beat = choose(player, state, level, head, facing, nightTicks, familiarity, newPlace);
        Director.schedule(1, () -> {
            if (player.hasDisconnected() || player.level() != level) {
                return;
            }
            try {
                if (beat != null) {
                    beat.run();
                }
            } finally {
                Ledger.snapshot(player, state);
            }
        });
    }

    /**
     * One beat at most, often none. Decided while the player is still asleep (so a stolen morning
     * can keep the screen dark); performed a tick later, once they are up.
     */
    private static Runnable choose(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, Direction facing,
            int nightTicks, float familiarity, boolean newPlace) {
        if (Beats.headTurnDue(state, level)) {
            state.setFlag("head_turn");
            return () -> Residue.headTurn(player, state, level, head, facing);
        }
        if (Beats.hush(state, level)) {
            return null;
        }
        Stage peak = state.peakStage();
        // It searched for you all night and could not find you. The ledger says so.
        if (newPlace && peak.atLeast(Stage.EXPOSURE) && !state.hasFlag("ledger_searched") && state.ledgerCount() < 5) {
            state.setFlag("ledger_searched");
            return () -> Ledger.searched(player, state, level, head, nightTicks);
        }
        String milestone = Ledger.milestoneDue(state);
        if (milestone != null && state.ledgerCount() < 5) {
            state.setFlag(milestone);
            return () -> Ledger.leave(player, state, level, head, nightTicks);
        }
        if (Beats.silent(player)) {
            return null;
        }
        WakeConfig config = WakeConfig.get();
        if (Beats.window(state, Stage.THEFT, Stage.LIKENESS) && config.stolenMornings && !level.getLevelData().isHardcore()
                && state.ready("stolen_morning", level.getGameTime()) && player.getRandom().nextFloat() < 0.4F) {
            BlockPos where = Theft.stolenMorningSpot(player, state, level, head);
            if (where != null) {
                state.cooldown("stolen_morning", level.getGameTime(), 24000L * 3);
                // The screen is still dark from sleep. Keep it dark until it has put you there.
                WakeNetwork.send(player, FxPayload.of(FxPayload.BLACKOUT, 30));
                return () -> Theft.wakeAt(player, level, head, where);
            }
        }
        if (Beats.window(state, Stage.EXPOSURE, Stage.LIKENESS)) {
            return () -> {
                Residue.night(player, state, level, head, facing, nightTicks, true);
                WakeNetwork.send(player, FxPayload.of(FxPayload.WAKE_TINT, 70));
            };
        }
        // Act I: the chores. Only after nights in a familiar bed: a bribe for the routine.
        if (Beats.window(state, Stage.NIGHT_SHIFT, Stage.SIGHTINGS) && config.worldEdits && familiarity >= 0.83F) {
            return () -> Kindness.run(player, state, level, head, facing);
        }
        return null;
    }
}
