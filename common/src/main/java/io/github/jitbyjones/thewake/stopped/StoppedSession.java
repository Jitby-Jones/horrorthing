package io.github.jitbyjones.thewake.stopped;

import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.haunt.Breadcrumbs;
import io.github.jitbyjones.thewake.haunt.Household;
import io.github.jitbyjones.thewake.haunt.Spots;
import io.github.jitbyjones.thewake.phantom.Phantom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One player's night inside the Stopped. It lies in their bed, gets up, and walks the ribbons of
 * night their own footsteps left: every place they ever walked, plus the faint new trail they lay
 * behind them now. It moves like they do. It does not stop.
 */
final class StoppedSession {
    enum Phase { IN_BED, WALKING, DONE }

    final ServerPlayer player;
    final ServerLevel level;
    final BlockPos oldBed;
    final Direction facing;
    final long frozenDayTime;
    final boolean singleplayer;
    final boolean wasFrozen;
    final boolean wasInvulnerable;
    final Breadcrumbs crumbs;
    final Breadcrumbs fresh = new Breadcrumbs();
    final int getUpAt;

    Phantom wake;
    Phase phase = Phase.IN_BED;
    int age;
    boolean caught;

    private final ArrayDeque<BlockPos> path = new ArrayDeque<>();
    private int repath;
    private double stepAccumulator;
    @Nullable
    private BlockPos alerted;
    @Nullable
    private BlockPos lastFresh;
    /** Ticks spent standing at the end of its paths with you out of reach. */
    private int waited;
    /** Where it really is. The picture only catches up a few times a second: stop-motion. */
    @Nullable
    private Vec3 truePos;
    private float heading;
    private int frameClock;
    private boolean frameB;

    // escape
    @Nullable
    BlockPos escapeBed;
    int escapeTicks;

    StoppedSession(ServerPlayer player, ServerLevel level, BlockPos oldBed, Direction facing, long frozenDayTime, boolean singleplayer,
            boolean wasFrozen, Breadcrumbs crumbs, int caughtBefore) {
        this.player = player;
        this.level = level;
        this.oldBed = oldBed;
        this.facing = facing;
        this.frozenDayTime = frozenDayTime;
        this.singleplayer = singleplayer;
        this.wasFrozen = wasFrozen;
        this.wasInvulnerable = player.isInvulnerable();
        this.crumbs = crumbs;
        // Each time it has caught you, it gets up sooner.
        this.getUpAt = caughtBefore <= 0 ? 170 : caughtBefore == 1 ? 90 : 35;
    }

    /** @return true when the player has been caught this tick. */
    boolean tick() {
        age++;
        layTrail();
        if (wake == null || wake.isRemoved()) {
            return false;
        }
        if (phase == Phase.IN_BED) {
            if (age >= getUpAt) {
                getUp();
            }
            return false;
        }
        if (phase == Phase.WALKING) {
            return pursue(true);
        }
        return false;
    }

    /** Your steps here lay a faint new ribbon. It follows that too. */
    private void layTrail() {
        if (!player.onGround() || player.isSleeping()) {
            return;
        }
        BlockPos feet = player.blockPosition();
        if (!feet.equals(lastFresh)) {
            lastFresh = feet;
            if (!crumbs.contains(feet)) {
                fresh.add(feet);
            }
        }
    }

    Vec3 here() {
        return truePos != null ? truePos : wake.pos();
    }

    /**
     * The one time it moves while you watch, and it does not move the way things move: the picture
     * cuts from one frame of its walk to the next, a whole step at a time.
     */
    private void publish(boolean now) {
        if (!now && ++frameClock < 6) {
            return;
        }
        frameClock = 0;
        boolean walking = here().distanceTo(wake.pos()) > 0.05;
        frameB = !frameB;
        wake.moveTo(here(), heading)
                .setPose(walking ? (frameB ? CreaturePose.WALK_B : CreaturePose.WALK_A) : CreaturePose.STAND)
                .setHead(Mth.clamp(Mth.wrapDegrees(Spots.yawTowards(here(), player.position()) - heading), -110.0F, 110.0F), 0.0F);
    }

    void getUp() {
        if (phase != Phase.IN_BED) {
            return;
        }
        phase = Phase.WALKING;
        // There is no getting up. It was lying down; now it is standing beside the bed.
        BlockPos side = Spots.bedside(level, oldBed, facing, player.position());
        Vec3 at = side != null ? Vec3.atBottomCenterOf(side) : Vec3.atBottomCenterOf(oldBed).add(0, 0.6, 0);
        heading = Spots.yawTowards(at, player.position());
        wake.setMode(Phantom.Mode.WALKER).setPose(CreaturePose.STAND).setBedDirection(-1).moveTo(at, heading);
        truePos = at;
        repath = 0;
    }

    /** It heard you try to sleep on its paths. */
    void alert(BlockPos where) {
        alerted = where.immutable();
        repath = 0;
    }

    /** Pausing moves it forward by the real time that passed. */
    void advance(int ticks) {
        if (phase != Phase.WALKING) {
            return;
        }
        for (int i = 0; i < Math.min(ticks, 600); i++) {
            if (pursue(false)) {
                caught = true;
                return;
            }
        }
        publish(true);
    }

    private boolean pursue(boolean audible) {
        Vec3 target = player.position();
        if (here().distanceTo(target) < 0.9) {
            publish(true);
            return true;
        }
        if (--repath <= 0) {
            repath = 20;
            BlockPos goal = alerted;
            if (goal == null) {
                goal = nearestAny(target, 3.0);
            }
            if (goal == null) {
                goal = nearestAny(target, 64.0);
            }
            path.clear();
            if (goal != null) {
                path.addAll(route(BlockPos.containing(here()), goal));
            }
        }

        // It moves like you: smoothly, at a walk, a little faster the longer this goes on.
        double speed = 0.135 * (1.0 + Math.min(age, 2400) / 6000.0);
        Vec3 pos = here();
        double budget = speed;
        while (budget > 0.0 && !path.isEmpty()) {
            BlockPos nextCrumb = path.peekFirst();
            // Faint new ribbons are harder going than old worn ones.
            double local = fresh.contains(nextCrumb) ? 0.85 : 1.0;
            Vec3 next = Vec3.atBottomCenterOf(nextCrumb);
            double d = pos.distanceTo(next);
            double move = budget * local;
            if (d <= move) {
                pos = next;
                budget -= d / local;
                path.pollFirst();
            } else {
                pos = pos.add(next.subtract(pos).scale(move / d));
                budget = 0.0;
            }
        }
        if (path.isEmpty() && pos.distanceTo(target) < 2.5) {
            // Last steps, straight at you.
            Vec3 toward = target.subtract(pos);
            double len = toward.length();
            if (len > 0.01) {
                pos = pos.add(toward.scale(Math.min(speed, len) / len));
            }
            waited = 0;
        } else if (path.isEmpty() && escapeBed == null) {
            // At the edge of everything it knows, watching. Wait long enough and it learns to step off.
            if (++waited > 900) {
                Vec3 toward = target.subtract(pos);
                double len = toward.horizontalDistance();
                if (len > 0.01) {
                    Vec3 step = pos.add(toward.x / len * 0.03, 0, toward.z / len * 0.03);
                    BlockPos ground = Spots.groundNear(level, Mth.floor(step.x), Mth.floor(pos.y), Mth.floor(step.z), 2);
                    if (ground != null) {
                        pos = new Vec3(step.x, ground.getY(), step.z);
                    }
                }
            }
        } else {
            waited = 0;
        }
        if (alerted != null && pos.distanceTo(Vec3.atBottomCenterOf(alerted)) < 1.5) {
            alerted = null;
        }
        double moved = pos.distanceTo(here());
        if (moved > 0.001) {
            heading = Mth.approachDegrees(heading, Spots.yawTowards(here(), pos), 30.0F);
        }
        truePos = pos;
        if (audible) {
            publish(false);
        }
        if (audible) {
            stepAccumulator += moved;
            if (stepAccumulator > 0.8) {
                stepAccumulator = 0.0;
                BlockPos below = BlockPos.containing(pos).below();
                SoundEvent step = level.getBlockState(below).getSoundType().getStepSound();
                // Footsteps that sound like yours.
                Household.send(player, step, SoundSource.PLAYERS, pos, 0.32F, 1.0F);
            }
        }
        return pos.distanceTo(target) < 0.9;
    }

    @Nullable
    private BlockPos nearestAny(Vec3 at, double radius) {
        BlockPos a = crumbs.nearest(at, radius);
        BlockPos b = fresh.nearest(at, radius);
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Vec3.atBottomCenterOf(a).distanceToSqr(at) <= Vec3.atBottomCenterOf(b).distanceToSqr(at) ? a : b;
    }

    /**
     * Horizontal distance from a point to the nearest old ribbon, at any height (fresh ones do not
     * count). Height does not help: a bed on a pillar above your paths is still on your paths.
     */
    double distanceToOldRibbon(BlockPos pos, double cap) {
        return crumbs.horizontalDistance(Vec3.atBottomCenterOf(pos), cap);
    }

    /** Breadth-first search over the player's footprints, old and new. */
    private List<BlockPos> route(BlockPos from, BlockPos goal) {
        BlockPos start = nearestAny(Vec3.atBottomCenterOf(from), 3.0);
        if (start == null) {
            start = from;
        }
        if (start.equals(goal)) {
            return List.of(goal);
        }
        Map<BlockPos, BlockPos> came = new HashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(start);
        came.put(start, start);
        int expanded = 0;
        while (!open.isEmpty() && expanded < 6000) {
            BlockPos current = open.poll();
            expanded++;
            if (current.equals(goal)) {
                break;
            }
            for (BlockPos n : crumbs.neighbours(current, 1.9)) {
                if (came.putIfAbsent(n, current) == null) {
                    open.add(n);
                }
            }
            for (BlockPos n : fresh.neighbours(current, 1.9)) {
                if (came.putIfAbsent(n, current) == null) {
                    open.add(n);
                }
            }
        }
        if (!came.containsKey(goal)) {
            BlockPos best = start;
            double bestD = Double.MAX_VALUE;
            for (BlockPos p : came.keySet()) {
                double d = p.distSqr(goal);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            goal = best;
        }
        List<BlockPos> out = new ArrayList<>();
        BlockPos cursor = goal;
        int guard = 0;
        while (cursor != null && !cursor.equals(start) && guard++ < 8000) {
            out.add(cursor);
            cursor = came.get(cursor);
        }
        Collections.reverse(out);
        return out;
    }
}
