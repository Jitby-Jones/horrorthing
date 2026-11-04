package io.github.jitbyjones.thewake.world;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.entity.WakeBrain;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.haunt.Pursuit;
import io.github.jitbyjones.thewake.haunt.Spots;
import io.github.jitbyjones.thewake.haunt.Temper;
import io.github.jitbyjones.thewake.haunt.Tension;
import io.github.jitbyjones.thewake.haunt.WakeDimensions;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.registry.WakeContent;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * The Set. You lay down in a bed in the woods, and you are standing on a dark soundstage, at the
 * edge of the lights. In the middle of it is your house: the rooms you live in, the walls you
 * built, without a roof, without the hill it stands on, without the trees. Stage lights on stands.
 * A camera on legs, pointed at your bed. And in your bed, lying on its back, it is asleep.
 *
 * <p>It sleeps while you are quiet. Its pins are in reach. Sprint, jump, hit something near it and
 * it wakes. The exit is a door at the edge of the stage; it lets you out in the morning, where you
 * lay down.
 */
public final class TheSet {
    private static final int RADIUS = 9;
    private static final int BELOW = 2;
    private static final int ABOVE = 6;
    private static final int STAGE = RADIUS + 5;

    private TheSet() {}

    /** Each player's stage, far from everyone else's. */
    private static BlockPos origin(ServerPlayer player) {
        int slot = Math.floorMod(player.getUUID().hashCode(), 256);
        return new BlockPos(slot * 512, WakeDimensions.SET_FLOOR, 0);
    }

    /** From the bed in the woods: onto the stage. */
    public static boolean enter(ServerPlayer player) {
        ServerLevel set = WakeDimensions.set(player.server);
        if (set == null) {
            TheWake.LOG.warn("The Set dimension is missing; is the data pack disabled?");
            return false;
        }
        HauntState state = Haunt.state(player);
        ServerLevel overworld = player.server.overworld();
        BlockPos home = state.bed() != null ? state.bed() : player.blockPosition();
        BlockPos origin = origin(player);
        BlockPos bedOnSet = build(overworld, home, set, origin);
        WakeNetwork.send(player, FxPayload.of(FxPayload.BLACKOUT, 50));
        Vec3 stand = Vec3.atBottomCenterOf(origin.offset(0, 0, -STAGE + 1));
        float yaw = Spots.yawTowards(stand, Vec3.atCenterOf(origin));
        Pursuit.onChangedDimension(player);
        WakeDimensions.send(player, set, stand, yaw);
        Tension.spike(state, 0.25F);
        Temper.provoke(player, state, Temper.Provocation.TRESPASS);
        if (bedOnSet != null && state.reputation().pinsWhileAsleep() < 2 && Temper.pins(state) < 4) {
            // It is asleep in your bed.
            io.github.jitbyjones.thewake.haunt.Director.schedule(30, () -> asleep(player, set, bedOnSet));
        }
        return true;
    }

    private static void asleep(ServerPlayer player, ServerLevel set, BlockPos bed) {
        if (player.hasDisconnected() || player.level() != set) {
            return;
        }
        BlockState state = set.getBlockState(bed);
        Direction facing = state.getBlock() instanceof BedBlock ? state.getValue(BedBlock.FACING) : Direction.NORTH;
        // Lying on its back, the head on the pillow.
        Vec3 at = Vec3.atBottomCenterOf(bed).add(0.0, 0.6875, 0.0);
        WakeEntity body = Pursuit.spawn(player, at, WakeBrain.Purpose.ASLEEP);
        if (body != null) {
            float yaw = facing.getOpposite().toYRot();
            body.setYRot(yaw);
            body.yBodyRot = yaw;
            body.yHeadRot = yaw;
        }
    }

    /**
     * Copies the house round {@code home} onto the stage: the built things only, as the set
     * department would: no ground, no plants, no roof, no lights, no contents in the chests.
     * Returns where your bed ended up, or null.
     */
    @Nullable
    private static BlockPos build(ServerLevel from, BlockPos home, ServerLevel to, BlockPos origin) {
        // Strike the old set.
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-STAGE - 2, -1, -STAGE - 2), origin.offset(STAGE + 2, ABOVE + BELOW + 6, STAGE + 2))) {
            if (!to.getBlockState(p).isAir()) {
                to.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        // The stage floor.
        for (int x = -STAGE; x <= STAGE; x++) {
            for (int z = -STAGE; z <= STAGE; z++) {
                BlockState floor = (Math.abs(x) == STAGE || Math.abs(z) == STAGE) ? Blocks.YELLOW_CONCRETE.defaultBlockState()
                        : Blocks.BLACK_CONCRETE.defaultBlockState();
                to.setBlock(origin.offset(x, -1, z), floor, Block.UPDATE_CLIENTS);
            }
        }
        BlockPos bedOnSet = null;
        int floorY = home.getY();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -BELOW; dy <= ABOVE; dy++) {
                    BlockPos src = home.offset(dx, dy, dz);
                    BlockState s = from.getBlockState(src);
                    BlockState t = translate(s, dy, floorY);
                    if (t == null) {
                        continue;
                    }
                    // The stage floor is where your floor was.
                    BlockPos dst = origin.offset(dx, dy, dz);
                    if (dst.getY() < origin.getY() - 1) {
                        continue;
                    }
                    to.setBlock(dst, t, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    if (t.getBlock() instanceof BedBlock && t.getValue(BedBlock.PART) == BedPart.HEAD && dx * dx + dz * dz <= 4) {
                        bedOnSet = dst.immutable();
                    }
                }
            }
        }
        if (bedOnSet == null) {
            // You had no bed here, or it was not where we thought. One is provided.
            bedOnSet = origin.offset(0, 0, 0);
            to.setBlock(bedOnSet.south(), Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
                    .setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
            to.setBlock(bedOnSet, Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
                    .setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        }
        dress(to, origin, bedOnSet);
        return bedOnSet;
    }

    /** What survives the trip: what you built. Null for nothing. */
    @Nullable
    private static BlockState translate(BlockState s, int dy, int floorY) {
        if (s.isAir() || !s.getFluidState().isEmpty() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            return null;
        }
        if (dy < -1 || s.is(BlockTags.LEAVES) || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
                || s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.ICE)) {
            // The ground, the hill, the garden: none of it is on the set. Your floor is, if you laid one.
            return null;
        }
        if (s.getLightEmission() > 0 && !(s.getBlock() instanceof BedBlock)) {
            // No lights in the house. The light is the stage's.
            return null;
        }
        if (dy >= 4 && (s.is(BlockTags.SLABS) || s.is(BlockTags.STAIRS) || s.isSolid())) {
            // No roof.
            return null;
        }
        if (s.getBlock() instanceof EntityBlock && !(s.getBlock() instanceof BedBlock) && !(s.getBlock() instanceof net.minecraft.world.level.block.SignBlock)) {
            // Props: the chest is there, but empty; it is only the box.
            return s;
        }
        return s;
    }

    /** Lights on stands, the camera, the exit, and soft light hung over the set from the dark. */
    private static void dress(ServerLevel to, BlockPos origin, BlockPos bed) {
        for (int x = -RADIUS; x <= RADIUS; x += 6) {
            for (int z = -RADIUS; z <= RADIUS; z += 6) {
                BlockPos hang = origin.offset(x, ABOVE + 3, z);
                if (to.getBlockState(hang).isAir()) {
                    to.setBlock(hang, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 11), Block.UPDATE_ALL);
                }
            }
        }
        // Two lamps on tall stands, aimed at the bed.
        for (int side : new int[] {-1, 1}) {
            BlockPos stand = origin.offset(side * (RADIUS + 2), 0, -2);
            for (int y = 0; y < 4; y++) {
                to.setBlock(stand.above(y), Blocks.IRON_BARS.defaultBlockState(), Block.UPDATE_ALL);
            }
            to.setBlock(stand.above(4), Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true),
                    Block.UPDATE_CLIENTS);
            to.setBlock(stand.above(5), Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 13), Block.UPDATE_ALL);
        }
        // The camera, on a tripod, looking at the bed.
        BlockPos tripod = origin.offset(0, 0, -(RADIUS + 3));
        for (int y = 0; y < 2; y++) {
            to.setBlock(tripod.above(y), Blocks.DARK_OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        }
        to.setBlock(tripod.above(2), Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        // The exit, in a flat of plywood at the edge of the stage.
        BlockPos exit = origin.offset(0, 0, STAGE - 1);
        for (int x = -2; x <= 2; x++) {
            for (int y = 0; y <= 3; y++) {
                to.setBlock(exit.offset(x, y, 0), WakeContent.PLYWOOD.get().defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockState door = WakeContent.EXIT_DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        to.setBlock(exit, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        to.setBlock(exit.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        to.setBlock(exit.offset(0, 2, -1), Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 6), Block.UPDATE_ALL);
    }
}
