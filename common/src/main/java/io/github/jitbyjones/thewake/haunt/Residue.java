package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.PhantomBehavior;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;

/**
 * What you see in the first seconds after waking: a long exposure of the night. Faint smears where
 * it walked; something much more solid beside the bed, where it stood longest.
 */
final class Residue {
    private Residue() {}

    static void night(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, Direction facing, int nightTicks,
            boolean withWalk) {
        Stage peak = state.peakStage();
        RandomSource random = player.getRandom();
        Phantoms.removeIf(player, p -> p.pos().distanceToSqr(Vec3.atCenterOf(head)) < 48.0 * 48.0);

        double walked = 0.0;
        if (withWalk) {
            List<BlockPos> path = peak.atLeast(Stage.ROUTINE) ? yesterdaysWalk(state, level, head) : choresWalk(level, head, random);
            BlockPos previous = null;
            int shown = 0;
            int maxShown = peak.atLeast(Stage.ROUTINE) ? 9 : 5;
            for (int i = 0; i < path.size() && shown < maxShown; i++) {
                BlockPos p = path.get(i);
                if (previous != null) {
                    walked += Math.sqrt(previous.distSqr(p));
                }
                previous = p;
                if (p.closerThan(player.blockPosition(), 2.5) || !Spots.standable(level, p)) {
                    continue;
                }
                BlockPos next = i + 1 < path.size() ? path.get(i + 1) : head;
                float yaw = Spots.yawTowards(Vec3.atBottomCenterOf(p), Vec3.atBottomCenterOf(next));
                Exposures.passing(player, state, p, yaw).setBehavior(Exposures.afterimage(55 + shown * 8, 0.035F));
                shown++;
            }
        }

        // The bed. Opacity is how long it stood there.
        float stood = peak.atLeast(Stage.THEFT) ? 1.0F : peak.atLeast(Stage.ROUTINE) ? 0.7F : 0.4F;
        BlockPos side = Spots.bedside(level, head, facing, player.position());
        if (side != null && !side.closerThan(player.blockPosition(), 1.2)) {
            Vec3 at = Vec3.atBottomCenterOf(side);
            Exposures.still(player, state, side, Spots.yawTowards(at, Vec3.atCenterOf(head)), Phantom.Stance.LOOK_DOWN)
                    .setOpacity(0.25F + 0.7F * stood)
                    .setBehavior(Exposures.afterimage(peak.atLeast(Stage.ROUTINE) ? 110 : 80, 0.03F));
        }

        // Distance Walked rises while you sleep.
        if (walked > 0.0) {
            player.awardStat(Stats.WALK_ONE_CM, (int) Math.min(Integer.MAX_VALUE, walked * 100.0));
        }
    }

    /**
     * Rule 2 breaks exactly once. The solid one, in focus, which stood still all night, turns its
     * head: only the head smears into a double image as it turns, and it keeps turning past where a
     * neck stops.
     */
    static void headTurn(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, Direction facing) {
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
        Exposures.still(player, state, side, bodyYaw, Phantom.Stance.LOOK_DOWN)
                .setOpacity(1.0F)
                .setSmear(0.0F)
                .setFocus(1.0F)
                .setTouch(0.9, true)
                .setBehavior(new PhantomBehavior() {
                    private int age;
                    private float target = Float.NaN;

                    @Override
                    public void tick(Phantom p, ServerPlayer owner) {
                        age++;
                        if (Float.isNaN(target) && age >= 60) {
                            // It ends looking straight at you. If you are not already behind it, it
                            // goes the long way round, over its own back, to get there.
                            float delta = Mth.wrapDegrees(Spots.yawTowards(p.pos(), owner.position()) - bodyYaw);
                            target = Math.abs(delta) < 150.0F ? delta - Math.signum(delta == 0.0F ? 1.0F : delta) * 360.0F : delta;
                        }
                        if (age > 60 && age <= 110) {
                            float t = (age - 60) / 50.0F;
                            float eased = t * t * (3.0F - 2.0F * t);
                            p.setHead(target * eased, Mth.lerp(eased, 40.0F, -5.0F), 0.85F * (1.0F - eased * 0.4F));
                            // Past the neck's limit, the face it turns to you is yours.
                            p.setFace(Mth.clamp((eased - 0.6F) / 0.4F, 0.0F, 1.0F) * 0.9F);
                        }
                        if (age == 111) {
                            p.setHead(target, -5.0F, 0.0F);
                        }
                        if (age == 160) {
                            Phantoms.dissolve(owner, p, false);
                        }
                    }
                });
    }

    private static List<BlockPos> yesterdaysWalk(HauntState state, ServerLevel level, BlockPos head) {
        List<BlockPos> route = new ArrayList<>();
        for (BlockPos p : state.yesterday().points()) {
            if (p.closerThan(head, 28.0) && (route.isEmpty() || route.get(route.size() - 1).distSqr(p) >= 9.0)) {
                route.add(p);
            }
        }
        if (route.size() < 3) {
            return choresWalk(level, head, level.random);
        }
        int from = Math.max(0, route.size() - 12);
        return new ArrayList<>(route.subList(from, route.size()));
    }

    /** A loop through the things it tended: containers and doors near the bed. */
    private static List<BlockPos> choresWalk(ServerLevel level, BlockPos head, RandomSource random) {
        List<BlockPos> stops = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(head.offset(-10, -2, -10), head.offset(10, 2, 10))) {
            if (level.getBlockEntity(pos) instanceof Container || level.getBlockState(pos).getBlock() instanceof DoorBlock) {
                BlockPos stand = nearestStandable(level, pos.immutable());
                if (stand != null) {
                    stops.add(stand);
                }
            }
            if (stops.size() > 12) {
                break;
            }
        }
        if (stops.isEmpty()) {
            for (int i = 0; i < 4; i++) {
                BlockPos p = Spots.standableNear(level, head, 3, 9, random, b -> true, 12);
                if (p != null) {
                    stops.add(p);
                }
            }
        }
        Collections.shuffle(stops, new Random(random.nextLong()));
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos stop : stops) {
            if (path.size() >= 4) {
                break;
            }
            if (path.stream().noneMatch(q -> q.distSqr(stop) < 9.0)) {
                path.add(stop);
            }
        }
        return path;
    }

    @Nullable
    private static BlockPos nearestStandable(ServerLevel level, BlockPos target) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = target.relative(d);
            BlockPos spot = Spots.groundNear(level, p.getX(), p.getY(), p.getZ(), 1);
            if (spot != null) {
                return spot;
            }
        }
        return null;
    }
}
