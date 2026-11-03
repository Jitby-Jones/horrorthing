package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.net.SyncPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.GameType;

/** Shared helpers: who can be haunted, how clarity moves, how the client is told. */
public final class Haunt {
    /** Total clarity gained per in-game day can never exceed this (times pace). */
    public static final float DAILY_CAP = 0.07F;

    private Haunt() {}

    public static HauntState state(ServerPlayer player) {
        return HauntData.get(player.server).state(player.getUUID());
    }

    /** Players in a haunted dimension, alive, not spectating. */
    public static boolean eligible(ServerPlayer player) {
        if (player.isSpectator() || player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR || !player.isAlive()) {
            return false;
        }
        return WakeConfig.get().allowsDimension(player.level().dimension().location().toString());
    }

    /** Before the quiet nights are over nothing accumulates and nothing happens. */
    public static boolean awake(HauntState state) {
        return !state.ended() && state.nightsSkipped() > WakeConfig.get().quietNights;
    }

    /** Servers without the opt-in plateau just below the Stopped. */
    public static boolean finaleAllowed(ServerPlayer player) {
        WakeConfig config = WakeConfig.get();
        if (!config.finale) {
            return false;
        }
        boolean singleplayer = player.server.isSingleplayer() && !player.server.isPublished();
        return singleplayer || config.finaleOnServers;
    }

    public static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    public static long timeOfDay(ServerLevel level) {
        return level.getDayTime() % 24000L;
    }

    public static boolean isNight(ServerLevel level) {
        long t = timeOfDay(level);
        return t >= 12542L && t < 23460L;
    }

    /** Applies a clarity change scaled by pace and limited by the daily cap. */
    public static void gain(ServerPlayer player, HauntState state, float amount) {
        if (!awake(state)) {
            return;
        }
        double pace = WakeConfig.get().pace;
        float scaled = (float) (amount >= 0 ? amount * pace : amount * Math.sqrt(pace));
        float ceiling = finaleAllowed(player) ? 1.0F : 0.94F;
        if (scaled > 0 && state.clarity() + scaled > ceiling) {
            scaled = Math.max(0.0F, ceiling - state.clarity());
        }
        state.applyGain(scaled, day(player.serverLevel()), (float) (DAILY_CAP * pace));
        Stage peak = state.peakStage();
        if (peak.atLeast(Stage.HUSH) && state.hushDay() < 0) {
            state.setHushDay(day(player.serverLevel()));
        }
    }

    /** Sets clarity directly (commands), keeping the one-way stage bookkeeping consistent. */
    public static void force(ServerPlayer player, HauntState state, float clarity) {
        state.setClarity(clarity);
        if (state.peakStage().atLeast(Stage.HUSH) && state.hushDay() < 0) {
            state.setHushDay(day(player.serverLevel()) - 3);
        }
    }

    public static void flushSync(ServerPlayer player, HauntState state) {
        if (!state.syncDirty) {
            return;
        }
        state.syncDirty = false;
        WakeNetwork.send(player, new SyncPayload(state.clarity(), state.stage().ordinal(), Hunter.hunting(player), state.ended(),
                state.lastLayDownDayTime()));
    }

    public static int stat(ServerPlayer player, net.minecraft.resources.ResourceLocation custom) {
        return player.getStats().getValue(Stats.CUSTOM.get(custom));
    }
}
