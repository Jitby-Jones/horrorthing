package io.github.jitbyjones.thewake;

import io.github.jitbyjones.thewake.haunt.Director;
import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.haunt.Lapses;
import io.github.jitbyjones.thewake.haunt.Stage;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import io.github.jitbyjones.thewake.stopped.StoppedManager;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/** Everything the loaders call into. Each loader's event glue is a thin forward to these. */
public final class WakeHooks {
    private WakeHooks() {}

    public static void onServerTick(MinecraftServer server) {
        Director.tick(server);
    }

    /** Singleplayer, paused: only the creature moves (see {@code IntegratedServerMixin}). */
    public static void onPausedTick(MinecraftServer server) {
        try {
            io.github.jitbyjones.thewake.haunt.Hunter.tickPaused(server);
        } catch (RuntimeException e) {
            TheWake.LOG.error("Paused tick failed", e);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        StoppedManager.onServerStopping(server);
    }

    public static void onServerStopped() {
        Director.onServerStopped();
    }

    public static void onJoin(ServerPlayer player) {
        Lapses.onLogin(player);
        io.github.jitbyjones.thewake.haunt.DemoScript.onJoin(player);
        io.github.jitbyjones.thewake.haunt.DemoExpansion.onJoin(player);
    }

    public static void onLeave(ServerPlayer player) {
        Lapses.onLogout(player);
        io.github.jitbyjones.thewake.haunt.LastNight.onLogout(player);
    }

    public static void onDeath(ServerPlayer player) {
        StoppedManager.onDeath(player);
    }

    /**
     * Sleep refusals. The bed knows: "You may not rest now; there are monsters nearby", with nothing
     * there to see. Returns null to let vanilla decide.
     */
    @Nullable
    public static Player.BedSleepingProblem sleepProblem(ServerPlayer player, BlockPos pos) {
        if (StoppedManager.isIn(player)) {
            return Player.BedSleepingProblem.OTHER_PROBLEM;
        }
        BlockState state = player.level().getBlockState(pos);
        BlockPos head = pos;
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            head = pos.relative(state.getValue(BedBlock.FACING));
        }
        // The bed in the woods. You lie down, and you are somewhere else.
        if (io.github.jitbyjones.thewake.world.LiminalBeds.isStrangeBed(player.serverLevel(), head)) {
            HauntState haunt = Haunt.state(player);
            if (!io.github.jitbyjones.thewake.haunt.Temper.removed(haunt) && Haunt.awake(haunt)
                    && io.github.jitbyjones.thewake.world.TheSet.enter(player)) {
                return Player.BedSleepingProblem.OTHER_PROBLEM;
            }
        }
        if (StoppedManager.isKeptBed(player, head)) {
            player.displayClientMessage(Component.translatable("block.minecraft.bed.occupied"), true);
            return Player.BedSleepingProblem.OTHER_PROBLEM;
        }
        HauntState haunt = Haunt.state(player);
        // Only refuse when vanilla would have let you sleep: at night, in the overworld.
        if (player.level().isDay() || !Haunt.awake(haunt) || !Haunt.eligible(player) || !haunt.peakStage().atLeast(Stage.SIGHTINGS)
                || player.isCreative()) {
            return null;
        }
        Vec3 bed = Vec3.atCenterOf(head);
        // It is standing right there. Vanilla has the words for it.
        Phantom near = Phantoms.nearest(player, 96.0, p -> p.mode() == Phantom.Mode.STILL && p.scale() == 1.0F
                && p.pos().distanceToSqr(bed) < 25.0);
        if (near != null) {
            return Player.BedSleepingProblem.NOT_SAFE;
        }
        long gameTime = player.level().getGameTime();
        if (haunt.ready("bed_refusal", gameTime) && player.getRandom().nextFloat() < 0.12F) {
            haunt.cooldown("bed_refusal", gameTime, 24000L * 2);
            return Player.BedSleepingProblem.NOT_SAFE;
        }
        return null;
    }

    /** Right-click on a block. Returns true if the interaction was consumed. */
    public static boolean onUseBlock(ServerPlayer player, BlockPos pos) {
        if (io.github.jitbyjones.thewake.haunt.WakeDimensions.isOurs(player.level())
                && player.level().getBlockState(pos).getBlock() instanceof BedBlock) {
            // A prop. You cannot sleep on a set.
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("block.thewake.prop_bed"), true);
            return true;
        }
        return StoppedManager.onUseBlock(player, pos);
    }

    /** Block about to break. Returns true to cancel vanilla breaking. */
    public static boolean onBreakBlock(ServerPlayer player, BlockPos pos, BlockState state) {
        return StoppedManager.onBreakBlock(player, pos, state);
    }

    /** Attack about to happen. Returns true to cancel. */
    public static boolean onAttack(ServerPlayer player) {
        return StoppedManager.onAttack(player);
    }
}
