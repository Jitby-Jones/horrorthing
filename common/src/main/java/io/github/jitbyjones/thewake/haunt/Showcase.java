package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.stopped.StoppedManager;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Operator command support: run one beat now, out of order, to see or test it. */
public final class Showcase {
    public static final List<String> BEATS = List.of("sighting", "giant", "touch", "morning", "hunt", "attack", "headturn", "ledger",
            "searched", "kindness", "stolen", "blink", "hurt", "stopped");

    private Showcase() {}

    public static void wakeUp(HauntState state) {
        state.ensureNightsSkipped(WakeConfig.get().quietNights + 1);
    }

    public static String trigger(ServerPlayer player, String beat) {
        HauntState state = Haunt.state(player);
        wakeUp(state);
        ServerLevel level = player.serverLevel();
        BlockPos bed = state.bed() != null ? Spots.bedHead(level, state.bed()) : null;
        Direction facing = bed != null ? Spots.bedFacing(level, bed) : null;
        switch (beat) {
            case "sighting" -> {
                Phantom p = Appearances.sighting(player, state);
                if (p == null) {
                    return "No spot found at the edge of your view.";
                }
                Hunter.adopt(player, p);
                return "At the edge of your view. Do not look straight at it.";
            }
            case "giant" -> {
                return Appearances.giant(player) != null ? "Far off. Look around." : "No horizon in range.";
            }
            case "touch" -> {
                BlockPos spot = Spots.inView(player, 2.0, 3.0, 10.0F, 20);
                if (spot == null) {
                    return "No spot in front of you.";
                }
                Appearances.still(player, spot, Spots.yawTowards(Vec3.atBottomCenterOf(spot), player.position()), CreaturePose.STAND);
                return "Walk into it.";
            }
            case "morning" -> {
                if (bed == null || facing == null) {
                    return "Sleep in a bed first.";
                }
                Mornings.after(player, state, level, bed, facing);
                WakeNetwork.send(player, FxPayload.of(FxPayload.WAKE_TINT, 70));
                return "Where it spent the night (by act).";
            }
            case "hunt" -> {
                if (!state.stage().atLeast(Stage.SIGHTINGS)) {
                    Haunt.force(player, state, Stage.ROUTINE.threshold);
                }
                return "Open your inventory, or pause, and wait. Watch the edges of the screen.";
            }
            case "attack" -> {
                if (!state.stage().atLeast(Stage.HUNT) || state.stage().atLeast(Stage.HUSH)) {
                    Haunt.force(player, state, Stage.HUNT.threshold);
                }
                state.setLastAttackDay(-1L);
                BlockPos spot = Spots.behind(player, 2.0, 3.0, 20);
                if (spot == null) {
                    return "No room behind you.";
                }
                Phantom p = Appearances.still(player, spot, Spots.yawTowards(Vec3.atBottomCenterOf(spot), player.position()), CreaturePose.STAND);
                Director.schedule(2, () -> Attack.begin(player, state, p));
                return "Behind you.";
            }
            case "headturn" -> {
                if (bed == null || facing == null) {
                    return "Sleep in a bed first.";
                }
                Mornings.headTurn(player, state, level, bed, facing);
                return "Beside your bed.";
            }
            case "ledger", "searched" -> {
                BlockPos where = bed != null ? bed : player.blockPosition();
                if (beat.equals("ledger")) {
                    Ledger.leave(player, state, level, where, 10000);
                } else {
                    Ledger.searched(player, state, level, where, 10000);
                }
                return "A ledger on your bed.";
            }
            case "kindness" -> {
                if (bed == null || facing == null) {
                    return "Sleep in a bed first.";
                }
                Kindness.run(player, state, level, bed, facing);
                return "Chores done near your bed.";
            }
            case "stolen" -> {
                BlockPos where = Theft.stolenMorningSpot(player, state, level, bed != null ? bed : player.blockPosition());
                if (where == null) {
                    return "No dark, safe place on your routes yet.";
                }
                WakeNetwork.send(player, FxPayload.of(FxPayload.BLACKOUT, 30));
                Director.schedule(2, () -> Theft.wakeAt(player, level, bed != null ? bed : player.blockPosition(), where));
                return "Where it went.";
            }
            case "blink" -> {
                state.forcePeak(Stage.HUNT);
                state.addDebt();
                state.cooldown("blink", 0L, 0L);
                return "A kept night is owed. It will be collected in daylight soon.";
            }
            case "hurt" -> {
                BlockPos source = Spots.standableNear(level, player.blockPosition(), 7, 13, player.getRandom(),
                        p -> !Spots.visibleFrom(player, p), 40);
                Vec3 at = source != null ? Vec3.atCenterOf(source) : player.position().add(8, 0, 0);
                Household.send(player, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, at, 0.55F, 1.0F);
                return "From the next room.";
            }
            case "stopped" -> {
                if (bed == null || facing == null || !level.isLoaded(bed)) {
                    return "Sleep in a bed first.";
                }
                state.setClarity(1.0F);
                state.setFlag("head_turn");
                StoppedManager.begin(player, state, level, bed, facing, (level.getDayTime() / 24000L) * 24000L + 18000L);
                return "The Stopped.";
            }
            default -> {
                return "Unknown beat. Try: " + String.join(", ", BEATS);
            }
        }
    }
}
