package io.github.jitbyjones.thewake.haunt;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Scheduled silence. At most one beat per lapse, at least a third of lapses produce nothing,
 * each act retires the previous act's beats, and three silent days come before the head turn.
 */
final class Beats {
    static final float SILENCE = 0.34F;

    private Beats() {}

    /** True if this lapse should pass with nothing at all. */
    static boolean silent(ServerPlayer player) {
        return player.getRandom().nextFloat() < SILENCE;
    }

    /** Beat window by the highest act ever reached: {@code from} inclusive, {@code until} exclusive (null = open). */
    static boolean window(HauntState state, Stage from, Stage until) {
        Stage peak = state.peakStage();
        return peak.atLeast(from) && (until == null || !peak.atLeast(until));
    }

    /** The three silent days after reaching Act V. */
    static boolean hush(HauntState state, ServerLevel level) {
        return state.peakStage().atLeast(Stage.HUSH) && !state.hasFlag("head_turn")
                && state.hushDay() >= 0 && Haunt.day(level) < state.hushDay() + 1;
    }

    static boolean headTurnDue(HauntState state, ServerLevel level) {
        return state.peakStage().atLeast(Stage.HUSH) && !state.hasFlag("head_turn")
                && state.hushDay() >= 0 && Haunt.day(level) >= state.hushDay() + 1;
    }
}
