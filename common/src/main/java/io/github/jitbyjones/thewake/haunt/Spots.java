package io.github.jitbyjones.thewake.haunt;

import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Where can something the size of a player stand, and can the player see it there. */
public final class Spots {
    private Spots() {}

    public static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    public static boolean standable(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.getWorldBorder().isWithinBounds(feet)) {
            return false;
        }
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        return isPassable(level, feet) && isPassable(level, feet.above())
                && !ground.getCollisionShape(level, below).isEmpty()
                && !(ground.getBlock() instanceof BedBlock);
    }

    /** Scans a short vertical window around {@code yHint} for a place to stand. */
    @Nullable
    public static BlockPos groundNear(ServerLevel level, int x, int yHint, int z, int window) {
        for (int dy = 0; dy <= window; dy++) {
            BlockPos up = new BlockPos(x, yHint + dy, z);
            if (standable(level, up)) {
                return up;
            }
            if (dy > 0) {
                BlockPos down = new BlockPos(x, yHint - dy, z);
                if (standable(level, down)) {
                    return down;
                }
            }
        }
        return null;
    }

    @Nullable
    public static BlockPos standableNear(ServerLevel level, BlockPos center, int minRadius, int maxRadius, RandomSource random,
            Predicate<BlockPos> filter, int attempts) {
        for (int i = 0; i < attempts; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double r = minRadius + random.nextDouble() * Math.max(0, maxRadius - minRadius);
            int x = Mth.floor(center.getX() + 0.5 + Math.cos(angle) * r);
            int z = Mth.floor(center.getZ() + 0.5 + Math.sin(angle) * r);
            BlockPos spot = groundNear(level, x, center.getY(), z, 4);
            if (spot != null && filter.test(spot)) {
                return spot;
            }
        }
        return null;
    }

    /** True if nothing solid is between the player's eyes and the head of a figure at {@code feet}. */
    public static boolean visibleFrom(ServerPlayer player, BlockPos feet) {
        Vec3 eye = player.getEyePosition();
        Vec3 head = Vec3.atBottomCenterOf(feet).add(0.0, 1.5, 0.0);
        BlockHitResult hit = player.level().clip(new ClipContext(eye, head, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(head) < 0.25;
    }

    /**
     * A standable spot inside the player's field of view, between {@code min} and {@code max} blocks
     * away, within {@code halfConeDegrees} of where they are looking, with line of sight.
     */
    @Nullable
    public static BlockPos inView(ServerPlayer player, double min, double max, float halfConeDegrees, int attempts) {
        ServerLevel level = player.serverLevel();
        RandomSource random = player.getRandom();
        float baseYaw = player.getYHeadRot();
        for (int i = 0; i < attempts; i++) {
            float yaw = baseYaw + (random.nextFloat() * 2.0F - 1.0F) * halfConeDegrees;
            double dist = min + random.nextDouble() * (max - min);
            double rad = Math.toRadians(yaw);
            double x = player.getX() - Math.sin(rad) * dist;
            double z = player.getZ() + Math.cos(rad) * dist;
            BlockPos spot = groundNear(level, Mth.floor(x), Mth.floor(player.getY()), Mth.floor(z), 6);
            if (spot != null && visibleFrom(player, spot)) {
                return spot;
            }
        }
        return null;
    }

    /** A spot behind the player, out of view: for things that should be there when they turn round. */
    @Nullable
    public static BlockPos behind(ServerPlayer player, double min, double max, int attempts) {
        ServerLevel level = player.serverLevel();
        RandomSource random = player.getRandom();
        float baseYaw = player.getYHeadRot() + 180.0F;
        for (int i = 0; i < attempts; i++) {
            float yaw = baseYaw + (random.nextFloat() * 2.0F - 1.0F) * 35.0F;
            double dist = min + random.nextDouble() * (max - min);
            double rad = Math.toRadians(yaw);
            double x = player.getX() - Math.sin(rad) * dist;
            double z = player.getZ() + Math.cos(rad) * dist;
            BlockPos spot = groundNear(level, Mth.floor(x), Mth.floor(player.getY()), Mth.floor(z), 3);
            if (spot != null) {
                return spot;
            }
        }
        return null;
    }

    /** Yaw (degrees) for something at {@code from} to face {@code to}. */
    public static float yawTowards(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
    }

    /** The head half of a bed at {@code pos}, or null if it is not a bed. */
    @Nullable
    public static BlockPos bedHead(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return null;
        }
        if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return pos;
        }
        return pos.relative(state.getValue(BedBlock.FACING));
    }

    @Nullable
    public static Direction bedFacing(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof BedBlock ? state.getValue(BedBlock.FACING) : null;
    }

    /** Spots right beside the pillow, as if someone stood there looking down at it. */
    @Nullable
    public static BlockPos bedside(ServerLevel level, BlockPos head, Direction facing, @Nullable Vec3 avoid) {
        Direction[] sides = {facing.getClockWise(), facing.getCounterClockWise()};
        BlockPos best = null;
        double bestScore = -1.0;
        for (Direction side : sides) {
            for (BlockPos candidate : new BlockPos[] {head.relative(side), head.relative(side).relative(facing.getOpposite())}) {
                BlockPos spot = groundNear(level, candidate.getX(), head.getY(), candidate.getZ(), 1);
                if (spot == null) {
                    continue;
                }
                double score = avoid == null ? 1.0 : Vec3.atBottomCenterOf(spot).distanceToSqr(avoid);
                if (score > bestScore) {
                    bestScore = score;
                    best = spot;
                }
            }
        }
        return best;
    }

    /** The spot at the foot of the bed, facing up the bed: where the sleeping camera looks. */
    @Nullable
    public static BlockPos bedFoot(ServerLevel level, BlockPos head, Direction facing) {
        BlockPos foot = head.relative(facing.getOpposite());
        BlockPos beyond = foot.relative(facing.getOpposite());
        BlockPos spot = groundNear(level, beyond.getX(), head.getY(), beyond.getZ(), 1);
        if (spot != null) {
            return spot;
        }
        for (Direction side : new Direction[] {facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockPos s = groundNear(level, foot.relative(side).getX(), head.getY(), foot.relative(side).getZ(), 1);
            if (s != null) {
                return s;
            }
        }
        return null;
    }
}
