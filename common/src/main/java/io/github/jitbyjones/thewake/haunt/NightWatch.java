package io.github.jitbyjones.thewake.haunt;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeping the night: being present in the overworld, awake, from dusk until a dawn that arrives on
 * its own. The only thing that pushes the Wake back. Vanilla already charges for it in phantoms.
 */
final class NightWatch {
    static final int NIGHT_LENGTH = 23460 - 12542;

    private NightWatch() {}

    static void tick(ServerPlayer player, HauntState state, ServerLevel level) {
        long day = Haunt.day(level);
        if (Haunt.isNight(level)) {
            if (state.trackedNight() != day) {
                state.beginNight(day);
            }
            if (!player.isSleeping()) {
                state.addNightPresence(20, state.idleTicks > 20 * 90);
            }
            return;
        }
        long night = state.trackedNight();
        if (night < 0) {
            return;
        }
        boolean skipped = state.lastNightSkipDay() == night;
        boolean present = state.nightPresence() >= NIGHT_LENGTH * 0.8;
        boolean attentive = state.nightIdle() < state.nightPresence() * 0.35;
        state.beginNight(-1L);
        if (!skipped && present && attentive) {
            Lapses.onNightKept(player, state);
        }
    }
}
