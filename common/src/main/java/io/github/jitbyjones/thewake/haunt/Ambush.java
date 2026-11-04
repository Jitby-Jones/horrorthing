package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.entity.WakeBrain;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * It does not always run at you. Sometimes it is already where you are going: crouched round the
 * next corner, standing on the other side of the door you are about to open. You get a moment:
 * everything goes quiet, your heart stops in your ears, and then it comes. Uneasy, it only touches
 * you. Angry or worse, it jumps.
 */
public final class Ambush {
    public enum Kind {
        /** Round a corner ahead of you, low, in the dark. */
        CORNER,
        /** Right behind a door you are walking toward. */
        DOOR,
        /** On one of its marks (see {@link Marks}). */
        MARK,
        /** On the ceiling, at the top of a string (see {@link Strings}). */
        CEILING
    }

    private Ambush() {}

    /** Every second, from {@link Pursuit#tick}: the chance to be waiting for you. */
    static boolean tick(ServerPlayer player, HauntState state, long gameTime, Reputation.Form form, long sinceScare) {
        if (!WakeConfig.get().ambushes || !form.atLeast(Reputation.Form.UNEASY) || state.tension < 0.45F
                || sinceScare < (form == Reputation.Form.FURIOUS ? 20L * 40 : 20L * 75)
                || !state.ready("ambush", gameTime)) {
            return false;
        }
        RandomSource random = player.getRandom();
        float chance = 0.012F + state.tension * 0.02F + 0.006F * (form.ordinal() - 1);
        if (random.nextFloat() >= chance) {
            return false;
        }
        Kind kind = Kind.DOOR;
        BlockPos door = doorAhead(player);
        Vec3 at = door == null ? null : behindDoor(player, door);
        if (at == null) {
            kind = Kind.CORNER;
            at = cornerAhead(player);
        }
        if (at == null) {
            return false;
        }
        return start(player, state, at, kind, door);
    }

    /** Puts it in place to wait, for {@code kind}. */
    public static boolean start(ServerPlayer player, HauntState state, Vec3 at, Kind kind, @Nullable BlockPos watch) {
        WakeEntity body = Pursuit.spawn(player, at, WakeBrain.Purpose.AMBUSH);
        if (body == null) {
            return false;
        }
        body.brain().ambush(kind, watch);
        long now = player.level().getGameTime();
        Reputation.Form form = Temper.form(state);
        state.cooldown("ambush", now, switch (form) {
            case FURIOUS -> 20L * 60;
            case ANGRY -> 20L * 150;
            default -> 20L * 240;
        });
        state.lastScareTick = now;
        TheWake.LOG.debug("[{}] ambush ({}) at {}", player.getGameProfile().getName(), kind, at);
        return true;
    }

    /** Where you are heading: your movement if you are moving, else where you are looking. */
    static Vec3 heading(ServerPlayer player) {
        Vec3 motion = player.getDeltaMovement().multiply(1.0, 0.0, 1.0);
        if (motion.lengthSqr() > 0.002) {
            return motion.normalize();
        }
        Vec3 look = player.getViewVector(1.0F).multiply(1.0, 0.0, 1.0);
        return look.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : look.normalize();
    }

    private static boolean ahead(ServerPlayer player, Vec3 dir, BlockPos p, double degrees) {
        Vec3 to = Vec3.atCenterOf(p).subtract(player.position()).multiply(1.0, 0.0, 1.0);
        double len = to.length();
        return len > 1.0E-3 && dir.dot(to) / len >= Math.cos(Math.toRadians(degrees));
    }

    /** A closed door 3 to 9 blocks ahead of you. */
    @Nullable
    static BlockPos doorAhead(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 dir = heading(player);
        BlockPos at = player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-9, -2, -9), at.offset(9, 2, 9))) {
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof DoorBlock) || s.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || s.getValue(DoorBlock.OPEN)) {
                continue;
            }
            double d = Vec3.atCenterOf(p).distanceTo(player.position());
            if (d < 3.0 || d > 9.0 || !ahead(player, dir, p, 60.0)) {
                continue;
            }
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /** The far side of a door from you, close behind it, where it can stand. */
    @Nullable
    static Vec3 behindDoor(ServerPlayer player, BlockPos door) {
        ServerLevel level = player.serverLevel();
        Direction facing = level.getBlockState(door).getValue(DoorBlock.FACING);
        for (Direction d : new Direction[] {facing, facing.getOpposite()}) {
            BlockPos side = door.relative(d);
            Vec3 c = Vec3.atBottomCenterOf(side);
            // the side away from you
            if (c.distanceToSqr(player.position()) < Vec3.atBottomCenterOf(door).distanceToSqr(player.position())) {
                continue;
            }
            for (int k = 1; k <= 2; k++) {
                BlockPos spot = door.relative(d, k);
                if (Spots.standable(level, spot) && !Spots.visibleFrom(player, spot)) {
                    return Vec3.atBottomCenterOf(spot);
                }
            }
        }
        return null;
    }

    /** A dark place out of sight 5 to 10 blocks ahead of you: round a corner, behind a tree. */
    @Nullable
    static Vec3 cornerAhead(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 dir = heading(player);
        BlockPos spot = Spots.standableNear(level, player.blockPosition(), 5, 10, player.getRandom(),
                p -> ahead(player, dir, p, 55.0) && !Spots.visibleFrom(player, p) && Hunter.dark(level, p)
                        && Math.abs(p.getY() - player.getBlockY()) <= 2, 50);
        return spot == null ? null : Vec3.atBottomCenterOf(spot);
    }

    /** The point on the line from you to it where it would land if it jumped now. */
    public static Vec3 landing(ServerPlayer player) {
        return player.position();
    }

    static int tell(Reputation.Form form) {
        return switch (form) {
            case FURIOUS -> 6;
            case ANGRY -> 10;
            default -> 14;
        };
    }

    static double reach(Reputation.Form form) {
        return switch (form) {
            case FURIOUS -> 5.5;
            case ANGRY -> 4.5;
            default -> 3.0;
        };
    }

    /** Public for the brain. */
    public static int tellTicks(Reputation.Form form) {
        return tell(form);
    }

    public static double reachOf(Reputation.Form form) {
        return reach(form);
    }

    static int floor(double v) {
        return Mth.floor(v);
    }
}
