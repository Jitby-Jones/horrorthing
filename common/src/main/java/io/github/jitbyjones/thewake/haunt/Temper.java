package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Anger and what it learns. Everything that changes what it thinks of you goes through here, so
 * the numbers live in one place.
 *
 * <p>The founding dilemma, extended: sleep through the night and it learns you (clarity); stay up
 * and you keep the night from it, and it resents you for it (anger). There is no safe default.
 */
public final class Temper {
    /** Something you did, and how much it minds. */
    public enum Provocation {
        /** You hit it. */
        HIT(0.05F),
        /** You blinded it with a flash. */
        FLASHED(0.04F),
        /** You stayed up all night: the night was not given to it. */
        KEPT_NIGHT(0.07F),
        /** You slept, and it had the night. */
        SLEPT(-0.035F),
        /** You put a light down where it stood. */
        LIGHT(0.015F),
        /** It chased you and you got away. */
        ESCAPED(0.06F),
        /** You made it climb, dig, swim or rip a door out to get to you. */
        CHEESE(0.04F),
        /** Creative, spectator, commands. */
        CHEAT(0.15F),
        /** You pulled one of its pins. */
        PIN(0.2F),
        /** It got you: for now, that is enough. */
        CAUGHT(-0.22F),
        /** A day with nothing between you. */
        QUIET_DAY(-0.02F),
        /** You went into one of its places, or took something from one. */
        TRESPASS(0.03F),
        /** You stared it down. */
        STARED(0.01F);

        public final float amount;

        Provocation(float amount) {
            this.amount = amount;
        }
    }

    private Temper() {}

    public static Reputation.Form form(HauntState state) {
        return state.reputation().form();
    }

    public static float anger(HauntState state) {
        return state.reputation().anger();
    }

    /** Applies a provocation, scaled by the config, and returns the new form. */
    public static Reputation.Form provoke(ServerPlayer player, HauntState state, Provocation what) {
        return provoke(player, state, what, 1.0F);
    }

    public static Reputation.Form provoke(ServerPlayer player, HauntState state, Provocation what, float times) {
        Reputation r = state.reputation();
        if (r.removed || !Haunt.awake(state)) {
            return r.form();
        }
        Reputation.Form before = r.form();
        float amount = what.amount * times;
        if (amount > 0.0F) {
            amount *= (float) WakeConfig.get().angerRate;
        }
        r.setAnger(r.anger + amount);
        Reputation.Form after = r.form();
        if (after != before) {
            state.syncDirty = true;
            TheWake.LOG.debug("[{}] {} -> {} ({})", player.getGameProfile().getName(), before, after, what);
        }
        return after;
    }

    /** It finds you interesting: you look back at it, you keep to your routines. */
    public static void interest(HauntState state, float delta) {
        Reputation r = state.reputation();
        r.setInterest(r.interest + delta);
    }

    /** A way of getting away, counted. */
    public static void learn(ServerPlayer player, HauntState state, Reputation.Tactic tactic) {
        state.reputation().count(tactic);
        TheWake.LOG.debug("[{}] learned {} ({})", player.getGameProfile().getName(), tactic, state.reputation().tactic(tactic));
    }

    public static boolean knows(HauntState state, Reputation.Tactic tactic, int times) {
        return state.reputation().tactic(tactic) >= times;
    }

    public static void rememberHide(HauntState state, BlockPos pos) {
        Reputation.remember(state.reputation().hides, pos, 4.0);
    }

    public static void rememberLookout(HauntState state, BlockPos pos) {
        Reputation.remember(state.reputation().lookouts, pos, 6.0);
    }

    public static BlockPos knownHide(HauntState state, BlockPos near, double within) {
        return Reputation.nearest(state.reputation().hides, near, within);
    }

    public static BlockPos knownLookout(HauntState state, BlockPos near, double within) {
        return Reputation.nearest(state.reputation().lookouts, near, within);
    }

    public static void chaseBegan(HauntState state, long gameTime) {
        Reputation r = state.reputation();
        r.chases++;
        r.lastChaseTick = gameTime;
        r.chasePending = false;
    }

    public static void escaped(HauntState state) {
        state.reputation().escapes++;
    }

    public static void grabbed(HauntState state) {
        state.reputation().grabs++;
    }

    /** It had you: remembered for three days. The more it has had you lately, the harder it bites. */
    public static void caught(HauntState state, long gameTime) {
        long window = 24000L * 3;
        boolean a = !state.ready("catch_a", gameTime);
        boolean b = !state.ready("catch_b", gameTime);
        if (!a) {
            state.cooldown("catch_a", gameTime, window);
        } else if (!b) {
            state.cooldown("catch_b", gameTime, window);
        } else {
            // both remembered: the older one is replaced
            String older = state.ready("catch_a", gameTime + window / 2) ? "catch_a" : "catch_b";
            state.cooldown(older, gameTime, window);
        }
    }

    public static int recentCatches(HauntState state, long gameTime) {
        return (state.ready("catch_a", gameTime) ? 0 : 1) + (state.ready("catch_b", gameTime) ? 0 : 1);
    }

    public static void possessed(HauntState state) {
        state.reputation().possessions++;
    }

    public static void hurtAt(HauntState state, long gameTime) {
        state.reputation().lastHurtTick = gameTime;
    }

    public static void setChasePending(HauntState state, boolean pending) {
        state.reputation().chasePending = pending;
    }

    public static int pins(HauntState state) {
        return state.reputation().pins;
    }

    /** One pin out. Returns how many are out now. */
    public static int pullPin(HauntState state, boolean asleep) {
        Reputation r = state.reputation();
        r.pins = Math.min(4, r.pins + 1);
        if (asleep) {
            r.pinsWhileAsleep++;
        }
        state.syncDirty = true;
        return r.pins;
    }

    public static void setTapeTaken(HauntState state) {
        state.reputation().tapeTaken = true;
    }

    public static boolean removed(HauntState state) {
        return state.reputation().removed;
    }

    public static void markRemoved(HauntState state) {
        Reputation r = state.reputation();
        r.removed = true;
        r.anger = 0.0F;
        state.syncDirty = true;
    }

    /** A new day: anger cools a little when nothing happened between you. */
    public static void newDay(ServerPlayer player, HauntState state, boolean quiet) {
        if (quiet) {
            provoke(player, state, Provocation.QUIET_DAY);
        }
        interest(state, -0.02F);
    }
}
