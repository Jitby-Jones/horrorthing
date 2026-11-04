package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Its places. The Archive: the back room of a video shop that goes on for ever, every shelf full
 * of tapes, every tape a night somebody slept through. The Set: your own house, built again out of
 * plywood on a dark soundstage, with the lights on it and the camera pointed at your bed.
 */
public final class WakeDimensions {
    public static final ResourceKey<Level> ARCHIVE = ResourceKey.create(Registries.DIMENSION, TheWake.id("archive"));
    public static final ResourceKey<Level> SET = ResourceKey.create(Registries.DIMENSION, TheWake.id("set"));
    /** The Archive's floor: rooms are four blocks tall above it. */
    public static final int ARCHIVE_FLOOR = 32;
    /** The Set's stage floor. */
    public static final int SET_FLOOR = 64;

    private WakeDimensions() {}

    public static boolean isOurs(Level level) {
        return level.dimension() == ARCHIVE || level.dimension() == SET;
    }

    @Nullable
    public static ServerLevel archive(MinecraftServer server) {
        return server.getLevel(ARCHIVE);
    }

    @Nullable
    public static ServerLevel set(MinecraftServer server) {
        return server.getLevel(SET);
    }

    /** Moves a player into a level, facing a way, and remembers where they came from. */
    public static void send(ServerPlayer player, ServerLevel to, Vec3 at, float yaw) {
        HauntState state = Haunt.state(player);
        if (!isOurs(player.level())) {
            state.setReturnPoint(player.level().dimension(), player.position(), player.getYRot());
        }
        player.teleportTo(to, at.x, at.y, at.z, yaw, 0.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
    }

    /** Back to wherever they were before they went in, or their bed, or spawn. */
    public static void home(ServerPlayer player) {
        HauntState state = Haunt.state(player);
        MinecraftServer server = player.server;
        ServerLevel level = server.getLevel(state.returnDimension());
        Vec3 at = state.returnPos();
        if (level == null || at == null) {
            level = server.overworld();
            BlockPos spawn = level.getSharedSpawnPos();
            at = Vec3.atBottomCenterOf(level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn));
        }
        player.teleportTo(level, at.x, at.y, at.z, state.returnYaw(), 0.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
    }
}
