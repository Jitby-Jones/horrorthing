package io.github.jitbyjones.thewake.entity;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.haunt.Reputation;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What stands between it and you, and what it does about it: doors are opened or torn out and
 * thrown, walls are dug through, lights are put out. Every block it breaks drops, so nothing is ever
 * lost, only moved, broken open, left lying in the grass.
 */
public final class Obstacles {
    private Obstacles() {}

    static boolean isIronDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock && !DoorBlock.isWoodenDoor(state) && !state.getValue(DoorBlock.OPEN);
    }

    static boolean isWoodenDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(state) && !state.getValue(DoorBlock.OPEN);
    }

    static boolean isIronTrapdoor(BlockState state) {
        return state.is(Blocks.IRON_TRAPDOOR) && !state.getValue(TrapDoorBlock.OPEN);
    }

    static boolean isClosedGate(BlockState state) {
        return state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN);
    }

    /** Something it tears out and throws, rather than breaks. */
    static boolean rippable(BlockState state) {
        return isIronDoor(state) || isIronTrapdoor(state);
    }

    /** A block it can break. Not air, not water, not the unbreakable things. */
    static boolean breakable(Level level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F || state.is(BlockTags.WITHER_IMMUNE) && hardness > 30.0F && !state.is(Blocks.OBSIDIAN)
                && !state.is(Blocks.CRYING_OBSIDIAN)) {
            return false;
        }
        return !state.getCollisionShape(level, pos).isEmpty() || rippable(state);
    }

    /** Ticks it takes to break through: hardness matters less to it than it should. */
    static int breakTicks(Level level, BlockPos pos, BlockState state, Reputation.Form form) {
        float hardness = Math.max(0.0F, state.getDestroySpeed(level, pos));
        int ticks = Mth.clamp(Math.round(14.0F + hardness * 4.5F), 10, 90);
        if (form == Reputation.Form.FURIOUS) {
            ticks = ticks * 2 / 3;
        }
        return ticks;
    }

    /** The next closed door on its path, within a couple of blocks. */
    @Nullable
    static BlockPos doorAhead(WakeEntity body) {
        Level level = body.level();
        Path path = body.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            int from = path.getNextNodeIndex();
            for (int i = Math.max(0, from - 1); i < Math.min(path.getNodeCount(), from + 3); i++) {
                BlockPos p = path.getNodePos(i);
                for (BlockPos q : new BlockPos[] {p, p.above()}) {
                    BlockState s = level.getBlockState(q);
                    if ((isIronDoor(s) || isWoodenDoor(s) || isIronTrapdoor(s) || isClosedGate(s)) && q.closerToCenterThan(body.position(), 2.6)) {
                        return q;
                    }
                }
            }
        }
        // Or right in front of it.
        Vec3 look = Vec3.directionFromRotation(0.0F, body.getYRot());
        for (double reach : new double[] {0.6, 1.2}) {
            BlockPos p = BlockPos.containing(body.position().add(look.scale(reach)));
            for (BlockPos q : new BlockPos[] {p, p.above()}) {
                BlockState s = level.getBlockState(q);
                if (isIronDoor(s) || isWoodenDoor(s) || isIronTrapdoor(s) || isClosedGate(s)) {
                    return q;
                }
            }
        }
        return null;
    }

    /**
     * The block it should deal with next to get to {@code target}: whatever is over its head while it
     * climbs toward you, else the first thing on a line from its chest to you, else what is right in
     * front of it.
     */
    @Nullable
    static BlockPos blocking(WakeEntity body, ServerPlayer target) {
        Level level = body.level();
        BlockPos feet = body.blockPosition();
        double dy = target.getY() - body.getY();
        if (dy > 1.2 && (body.onClimbable() || body.horizontalCollision)) {
            for (int up = 2; up <= 3; up++) {
                BlockPos head = feet.above(up);
                BlockState s = level.getBlockState(head);
                if (!s.getCollisionShape(level, head).isEmpty()) {
                    return head;
                }
            }
        }
        Vec3 chest = body.position().add(0.0, 1.0, 0.0);
        Vec3 to = target.position().add(0.0, Math.min(1.0, target.getBbHeight() * 0.6), 0.0);
        BlockHitResult hit = level.clip(new ClipContext(chest, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceToSqr(chest) < 3.2 * 3.2) {
            return hit.getBlockPos();
        }
        Vec3 dir = new Vec3(target.getX() - body.getX(), 0.0, target.getZ() - body.getZ());
        if (dir.lengthSqr() < 1.0E-4) {
            return null;
        }
        dir = dir.normalize();
        for (double reach : new double[] {0.7, 1.3}) {
            BlockPos p = BlockPos.containing(body.getX() + dir.x * reach, body.getY() + 0.2, body.getZ() + dir.z * reach);
            for (int up = 0; up <= 1; up++) {
                BlockPos q = p.above(up);
                BlockState s = level.getBlockState(q);
                if (!s.getCollisionShape(level, q).isEmpty()) {
                    return q;
                }
            }
        }
        // You are below it, in a hole.
        if (dy < -1.5) {
            BlockPos under = feet.below();
            if (!level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
                return under;
            }
        }
        return null;
    }

    /**
     * A closed iron door or trapdoor within {@code radius}, nearer you than it is: in the way, more
     * or less. It goes for that before it goes for any wall.
     */
    @Nullable
    static BlockPos rippableToward(WakeEntity body, ServerPlayer target, int radius) {
        Level level = body.level();
        BlockPos c = body.blockPosition();
        double toTarget = body.position().distanceToSqr(target.position());
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-radius, -1, -radius), c.offset(radius, 2, radius))) {
            BlockState s = level.getBlockState(p);
            if (!rippable(s)) {
                continue;
            }
            if (Vec3.atCenterOf(p).distanceToSqr(target.position()) >= toTarget) {
                continue;
            }
            double d = Vec3.atCenterOf(p).distanceToSqr(body.position());
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /** Opens a wooden door or gate the way nothing polite opens a door. */
    static void bang(WakeEntity body, BlockPos pos) {
        Level level = body.level();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(body, level, state, pos, true);
        } else if (state.getBlock() instanceof FenceGateBlock) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, true), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F, 0.7F);
        }
        level.playSound(null, pos, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 0.9F, 0.6F + body.getRandom().nextFloat() * 0.2F);
    }

    /**
     * Tears a door or trapdoor out of its frame and throws it aside. It lands somewhere and lies
     * there, then it is an item in the grass.
     */
    static void rip(WakeEntity body, BlockPos pos, Vec3 throwDirection) {
        ServerLevel level = (ServerLevel) body.level();
        BlockState state = level.getBlockState(pos);
        BlockState lower = state;
        BlockState upper = Blocks.AIR.defaultBlockState();
        BlockPos base = pos;
        if (state.getBlock() instanceof DoorBlock) {
            base = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            lower = level.getBlockState(base).setValue(DoorBlock.OPEN, false);
            upper = level.getBlockState(base.above()).setValue(DoorBlock.OPEN, false);
            level.setBlock(base.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
        }
        level.setBlock(base, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
        level.levelEvent(2001, base, Block.getId(lower));
        FlungBlock flung = new FlungBlock(level, Vec3.atBottomCenterOf(base), lower, upper);
        Vec3 side = throwDirection.normalize();
        flung.setDeltaMovement(side.x * 0.95, 0.42, side.z * 0.95);
        flung.setSpin((body.getRandom().nextBoolean() ? 1 : -1) * (14.0F + body.getRandom().nextFloat() * 10.0F));
        level.addFreshEntity(flung);
        level.playSound(null, base, WakeRegistry.METAL_RIP.get(), SoundSource.BLOCKS, 2.2F * WakeConfig.get().soundVolume, 0.9F);
        level.playSound(null, base, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.BLOCKS, 1.0F, 0.5F);
    }

    /** Lights near you go out, one after another. Torches and lanterns drop; candles and fires are blown out. */
    static int snuffLights(ServerLevel level, BlockPos center, int radius, int max) {
        int done = 0;
        for (BlockPos p : BlockPos.withinManhattan(center, radius, radius / 2 + 1, radius)) {
            if (done >= max) {
                break;
            }
            BlockState s = level.getBlockState(p);
            if (s.getLightEmission() < 8) {
                continue;
            }
            if (s.getBlock() instanceof CandleBlock && s.getValue(BlockStateProperties.LIT)) {
                level.setBlock(p, s.setValue(BlockStateProperties.LIT, false), Block.UPDATE_ALL);
            } else if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) {
                CampfireBlock.dowse(null, level, p, s);
                level.setBlock(p, s.setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL);
            } else if (s.getBlock() instanceof TorchBlock || s.getBlock() instanceof LanternBlock || s.is(Blocks.GLOWSTONE)
                    || s.is(Blocks.SEA_LANTERN) || s.is(Blocks.JACK_O_LANTERN) || s.is(Blocks.SHROOMLIGHT) || s.is(Blocks.END_ROD)
                    || s.is(Blocks.OCHRE_FROGLIGHT) || s.is(Blocks.PEARLESCENT_FROGLIGHT) || s.is(Blocks.VERDANT_FROGLIGHT)) {
                level.destroyBlock(p.immutable(), true);
            } else {
                continue;
            }
            level.playSound(null, p, SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 0.6F);
            done++;
        }
        return done;
    }

    /**
     * True if the player is shut in: a flood fill from where they stand, through the spaces a
     * person fits through, runs out before {@code limit} cells. A sky hole is not shut.
     */
    public static boolean enclosed(ServerLevel level, BlockPos feet, int limit) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(feet.asLong());
        seen.add(feet.asLong());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty()) {
            if (seen.size() > limit) {
                return false;
            }
            BlockPos p = BlockPos.of(queue.dequeueLong());
            if (level.canSeeSky(p.above()) && p.getY() >= level.getSeaLevel() - 2) {
                return false;
            }
            for (Direction d : Direction.values()) {
                m.setWithOffset(p, d);
                long key = m.asLong();
                if (seen.contains(key)) {
                    continue;
                }
                if (open(level, m)) {
                    seen.add(key);
                    queue.enqueue(key);
                }
            }
        }
        return true;
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.OPEN)) {
            return true;
        }
        return s.getCollisionShape(level, pos).isEmpty();
    }

    /** The wall block nearest {@code from} on the shell around the player: where it knocks. */
    @Nullable
    static BlockPos wallToward(ServerLevel level, ServerPlayer player, Vec3 from) {
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, from, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return null;
    }
}
