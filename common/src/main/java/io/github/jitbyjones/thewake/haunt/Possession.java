package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.net.PossessPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Creative mode is not a way out, and neither is spectating. In a world that was started in
 * survival, it opens your chat box itself and types into it, slowly, one key at a time: something
 * it wants you to read, then backspaces over it, then {@code /gamemode survival}, and presses enter.
 * It is your own chat, your own command, in your history if you press up.
 *
 * <p>If the command is refused (no permission) or your client has no mod to type with, the server
 * puts you back in survival when the typing would have finished. Worlds first started in creative
 * are left alone: that is somebody building.
 */
public final class Possession {
    public enum Why {
        /** You switched to creative or spectator mid-haunting. */
        SWITCHED,
        /** In the middle of a chase. */
        CHASED,
        /** It has hold of you. */
        HELD,
        /** You used a command on it. */
        COMMAND
    }

    private static final String[] SAYS = {
            "no", "not like that", "you can't watch from there", "stay", "come back down", "i can see you", "that's not how it works",
            "you have to be here for this", "put it back"
    };

    private static final Map<UUID, Long> PENDING = new HashMap<>();

    private Possession() {}

    public static void onServerStopped() {
        PENDING.clear();
    }

    /** The world was first played in creative: someone is building, not hiding. */
    public static boolean exempt(ServerPlayer player) {
        HauntData data = HauntData.get(player.server);
        return data.startedCreative();
    }

    private static boolean escaping(ServerPlayer player) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        return mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
    }

    /** Every second: notices you changing mode, and takes it back when it is ready to. */
    static void tick(ServerPlayer player, HauntState state, long gameTime) {
        UUID id = player.getUUID();
        Long due = PENDING.get(id);
        if (due != null) {
            if (gameTime >= due) {
                PENDING.remove(id);
                if (escaping(player)) {
                    // The command did not go through: it does it itself.
                    player.setGameMode(GameType.SURVIVAL);
                }
            }
            return;
        }
        if (gameTime % 20L != 0L || !escaping(player) || !WakeConfig.get().possession || exempt(player)) {
            return;
        }
        if (!Haunt.awake(state) || Temper.removed(state) || !state.peakStage().atLeast(Stage.SIGHTINGS)
                || !WakeConfig.get().allowsDimension(player.level().dimension().location().toString()) && !WakeDimensions.isOurs(player.serverLevel())) {
            return;
        }
        // Not straight away: when it notices. Sooner at night, sooner when it is angry, always when
        // it is out after you.
        boolean urgent = Pursuit.active(player) || LastNight.active(player);
        float chance = urgent ? 1.0F : (Haunt.isNight(player.serverLevel()) ? 0.05F : 0.012F) * (1.0F + Temper.anger(state) * 3.0F);
        if (state.ready("possession", gameTime) && player.getRandom().nextFloat() < chance) {
            demand(player, state, Why.SWITCHED);
        }
    }

    /** Takes over the chat box now (unless it already is, or this world is exempt). */
    public static void demand(ServerPlayer player, HauntState state, Why why) {
        if (!WakeConfig.get().possession || exempt(player) || !escaping(player) || PENDING.containsKey(player.getUUID())) {
            return;
        }
        long now = player.level().getGameTime();
        state.cooldown("possession", now, 20L * 60 * 4);
        String say = why == Why.HELD ? "" : SAYS[player.getRandom().nextInt(SAYS.length)];
        String command = "/gamemode survival";
        // Roughly how long the client takes to type it, erase it and type the command, plus a little.
        int ticks = 10 + say.length() * 5 + 30 + say.length() * 2 + 16 + command.length() * 4 + 18 + 6;
        if (why == Why.HELD) {
            ticks = 2 + command.length() * 2 + 4 + 6;
        }
        PENDING.put(player.getUUID(), now + ticks);
        WakeNetwork.send(player, new PossessPayload(say, command, why == Why.HELD));
        Temper.possessed(state);
        if (why == Why.SWITCHED || why == Why.COMMAND) {
            Temper.provoke(player, state, Temper.Provocation.CHEAT);
            Temper.learn(player, state, Reputation.Tactic.CHEAT);
        }
        TheWake.LOG.debug("[{}] possession ({})", player.getGameProfile().getName(), why);
    }

    public static boolean pending(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }
}
