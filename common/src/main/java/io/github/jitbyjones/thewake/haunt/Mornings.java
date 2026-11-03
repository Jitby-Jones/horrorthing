package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.PhantomBehavior;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * A skipped night is the longest lapse there is. Where it spent it is where you find it in the
 * morning: a little closer every act. Act II: on the horizon. Act III: in the doorway. Then at the
 * foot of the bed. Then bent over you, and some mornings it does not wait for you to get up.
 */
public final class Mornings {
    private Mornings() {}

    public static void after(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, Direction facing) {
        Hunter.release(player, true);
        Phantoms.removeIf(player, p -> p.mode() == Phantom.Mode.STILL);
        Stage stage = state.stage();
        RandomSource random = player.getRandom();
        walkedYourRoutes(player, state, head);
        Phantom placed = null;
        if (stage == Stage.WATCHING) {
            Hunter.maybeGiant(player, state);
            return;
        }
        if (stage == Stage.SIGHTINGS) {
            placed = doorway(player, level, head);
            if (placed == null) {
                placed = Appearances.sighting(player, state);
            }
        } else if (stage == Stage.ROUTINE) {
            BlockPos foot = Spots.bedFoot(level, head, facing);
            if (foot != null) {
                Vec3 at = Vec3.atBottomCenterOf(foot);
                placed = Appearances.still(player, foot, Spots.yawTowards(at, Vec3.atCenterOf(head)), CreaturePose.STAND);
            }
        } else if (stage == Stage.HUNT) {
            BlockPos side = Spots.bedside(level, head, facing, null);
            if (side != null) {
                Vec3 at = Vec3.atBottomCenterOf(side);
                placed = Appearances.still(player, side, Spots.yawTowards(at, Vec3.atCenterOf(head)), CreaturePose.BEDSIDE);
                if (Attack.allowed(player, state) && random.nextFloat() < 0.3F) {
                    Phantom creature = placed;
                    Director.schedule(18, () -> {
                        if (!player.hasDisconnected() && !creature.isRemoved() && Attack.allowed(player, state)) {
                            Attack.begin(player, state, creature);
                        }
                    });
                }
            }
        }
        if (placed != null) {
            // Walk into it and it takes an hour from you; but then it is gone.
            placed.setTouch(0.9, true);
            Hunter.adopt(player, placed);
        }
    }

    /** Distance Walked went up while you slept: it walked your routes for you. */
    private static void walkedYourRoutes(ServerPlayer player, HauntState state, BlockPos head) {
        double walked = 0.0;
        BlockPos previous = null;
        for (BlockPos p : state.yesterday().points()) {
            if (previous != null && p.closerThan(head, 48.0)) {
                walked += Math.sqrt(previous.distSqr(p));
            }
            previous = p;
        }
        if (walked > 0.0) {
            player.awardStat(Stats.WALK_ONE_CM, (int) Math.min(Integer.MAX_VALUE, walked * 100.0));
        }
    }

    /** Standing in the nearest doorway, facing the bed. It left the door open. */
    @Nullable
    private static Phantom doorway(ServerPlayer player, ServerLevel level, BlockPos head) {
        BlockPos best = null;
        double bestSq = 14.0 * 14.0;
        for (BlockPos pos : BlockPos.betweenClosed(head.offset(-12, -2, -12), head.offset(12, 2, 12))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                double sq = pos.distSqr(head);
                if (sq < bestSq && sq > 4.0) {
                    bestSq = sq;
                    best = pos.immutable();
                }
            }
        }
        if (best == null) {
            return null;
        }
        BlockState door = level.getBlockState(best);
        if (WakeConfig.get().worldEdits && !door.getValue(DoorBlock.OPEN)) {
            level.setBlock(best, door.setValue(DoorBlock.OPEN, true), Block.UPDATE_ALL);
        }
        Vec3 at = Vec3.atBottomCenterOf(best);
        Phantom placed = Appearances.still(player, best, Spots.yawTowards(at, Vec3.atCenterOf(head)), CreaturePose.STAND);
        if (!level.getBlockState(best).getValue(DoorBlock.OPEN)) {
            // Shut: then it is just outside, on the far side of the door from the bed.
            Direction a = level.getBlockState(best).getValue(DoorBlock.FACING);
            Direction outward = Vec3.atCenterOf(best.relative(a)).distanceToSqr(Vec3.atCenterOf(head))
                    > Vec3.atCenterOf(best.relative(a.getOpposite())).distanceToSqr(Vec3.atCenterOf(head)) ? a : a.getOpposite();
            BlockPos outside = best.relative(outward);
            if (Field.walkable(level, outside)) {
                placed.moveTo(Vec3.atBottomCenterOf(outside), placed.yaw());
            }
        }
        return placed;
    }

    /**
     * Act V, after three silent days, exactly once. Close, beside the bed, lit as if by a flash.
     * It has been looking down at the pillow all night. Then it turns its head to you, the long way
     * round, over its own back, far past where any neck stops.
     */
    public static void headTurn(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, Direction facing) {
        Hunter.release(player, true);
        Phantoms.removeIf(player, p -> true);
        BlockPos side = Spots.bedside(level, head, facing, player.position());
        if (side == null) {
            side = Spots.bedFoot(level, head, facing);
        }
        if (side == null) {
            return;
        }
        Vec3 at = Vec3.atBottomCenterOf(side);
        float bodyYaw = Spots.yawTowards(at, Vec3.atCenterOf(head));
        Phantoms.create(player, at, bodyYaw, Phantom.Mode.STILL, CreaturePose.BEDSIDE)
                .setFlags(Phantom.FLAG_LIT | Phantom.FLAG_SCRIPTED_HEAD)
                .setTouch(0.0, false)
                .setBehavior(new PhantomBehavior() {
                    private int age;
                    private float target = Float.NaN;

                    @Override
                    public void tick(Phantom p, ServerPlayer owner) {
                        age++;
                        if (Float.isNaN(target) && age >= 60) {
                            // It ends looking straight at you. If you are not already behind it, it
                            // goes the long way round to get there.
                            float delta = Mth.wrapDegrees(Spots.yawTowards(p.pos(), owner.position()) - bodyYaw);
                            target = Math.abs(delta) < 150.0F ? delta - Math.signum(delta == 0.0F ? 1.0F : delta) * 360.0F : delta;
                            Household.send(owner, WakeRegistry.CRACK.get(), SoundSource.HOSTILE, p.pos().add(0.0, 2.0, 0.0),
                                    0.9F * WakeConfig.get().soundVolume, 0.7F);
                        }
                        if (age > 60 && age <= 120) {
                            float t = (age - 60) / 60.0F;
                            float eased = t * t * (3.0F - 2.0F * t);
                            p.setHead(target * eased, Mth.lerp(eased, 50.0F, -10.0F));
                            if (age % 9 == 0) {
                                Household.send(owner, WakeRegistry.CRACK.get(), SoundSource.HOSTILE, p.pos().add(0.0, 2.0, 0.0),
                                        0.6F * WakeConfig.get().soundVolume, 0.6F + owner.getRandom().nextFloat() * 0.3F);
                            }
                        }
                        if (age == 121) {
                            p.setHead(target, -10.0F).setJaw(14.0F);
                        }
                        if (age == 200) {
                            Phantoms.dissolve(owner, p, false);
                        }
                    }
                });
    }
}
