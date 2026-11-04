package io.github.jitbyjones.thewake.world;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.block.PassageDoorBlock;
import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.haunt.Pursuit;
import io.github.jitbyjones.thewake.haunt.Temper;
import io.github.jitbyjones.thewake.haunt.Tension;
import io.github.jitbyjones.thewake.haunt.WakeDimensions;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Doors that open onto somewhere else. */
public final class Passages {
    private Passages() {}

    public static void through(ServerPlayer player, PassageDoorBlock.Leads leads, BlockPos door) {
        ServerLevel here = player.serverLevel();
        here.playSound(null, door, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 0.6F);
        WakeNetwork.send(player, FxPayload.of(FxPayload.BLACKOUT, 30));
        switch (leads) {
            case ARCHIVE -> io.github.jitbyjones.thewake.haunt.Director.schedule(14, () -> intoArchive(player));
            case HOME -> io.github.jitbyjones.thewake.haunt.Director.schedule(14, () -> {
                if (!player.hasDisconnected()) {
                    Pursuit.onChangedDimension(player);
                    WakeDimensions.home(player);
                }
            });
        }
    }

    /** Somewhere in the Archive: far enough in that you will not find the way you came. */
    public static void intoArchive(ServerPlayer player) {
        if (player.hasDisconnected()) {
            return;
        }
        ServerLevel archive = WakeDimensions.archive(player.server);
        if (archive == null) {
            TheWake.LOG.warn("The Archive dimension is missing; is the data pack disabled?");
            return;
        }
        HauntState state = Haunt.state(player);
        int x = player.getRandom().nextInt(4000) - 2000;
        int z = player.getRandom().nextInt(4000) - 2000;
        BlockPos at = ArchiveGenerator.entry(x, z);
        Pursuit.onChangedDimension(player);
        WakeDimensions.send(player, archive, Vec3.atBottomCenterOf(at), player.getRandom().nextFloat() * 360.0F);
        Temper.provoke(player, state, Temper.Provocation.TRESPASS);
        Tension.spike(state, 0.2F);
        archive.playSound(null, at, WakeRegistry.LIMINAL_DRONE.get(), SoundSource.AMBIENT, 0.5F, 1.0F);
    }
}
