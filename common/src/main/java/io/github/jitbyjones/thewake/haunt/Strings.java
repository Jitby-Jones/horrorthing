package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.entity.WakeBrain;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Strings. In a dark room or a tunnel with a low ceiling, a length of twine hangs from the
 * ceiling to about the height of your face, pulled tight, creaking now and then. It is up there,
 * flat against the ceiling in the dark, holding the other end. Walk under it and it drops on you.
 * Cut it and it falls in front of you, and lies there a moment before it gets up.
 */
public final class Strings {
    private record Hang(BlockPos pos, long until) {}

    private static final Map<UUID, Hang> HANGS = new HashMap<>();

    private Strings() {}

    public static void onServerStopped() {
        HANGS.clear();
    }

    /** Every tick, from {@link Pursuit#tick}: the string you have, or the chance of one. */
    static boolean tick(ServerPlayer player, HauntState state, long gameTime, Reputation.Form form, boolean allowed) {
        ServerLevel level = player.serverLevel();
        Hang hang = HANGS.get(player.getUUID());
        if (hang != null) {
            if (!level.getBlockState(hang.pos()).is(WakeContent.HANGING_STRING.get())) {
                HANGS.remove(player.getUUID());
                return false;
            }
            Vec3 c = Vec3.atBottomCenterOf(hang.pos());
            double flat = Math.hypot(player.getX() - c.x, player.getZ() - c.z);
            if (allowed && flat < 0.75 && player.getY() < hang.pos().getY() && player.getY() > hang.pos().getY() - 4.5) {
                drop(player, state, hang);
                return true;
            }
            if (gameTime % 20L == 0L) {
                RandomSource random = player.getRandom();
                if (flat < 10.0 && random.nextInt(4) == 0) {
                    // The twine takes the weight of something, and creaks.
                    Household.send(player, WakeRegistry.DOOR_CREAK.get(), SoundSource.HOSTILE, Vec3.atCenterOf(hang.pos()).add(0.0, 0.5, 0.0),
                            0.35F * WakeConfig.get().soundVolume, 1.6F + random.nextFloat() * 0.2F);
                }
                if (gameTime > hang.until() && !Spots.visibleFrom(player, hang.pos().below())) {
                    level.setBlock(hang.pos(), Blocks.AIR.defaultBlockState(), 3);
                    HANGS.remove(player.getUUID());
                }
            }
            return false;
        }
        if (!allowed || gameTime % 20L != 0L || !WakeConfig.get().worldEdits || !WakeConfig.get().ambushes
                || !form.atLeast(Reputation.Form.ANGRY) || state.tension < 0.35F || !state.ready("string", gameTime)
                || player.getRandom().nextFloat() >= 0.01F + 0.01F * (form.ordinal() - 2)) {
            return false;
        }
        BlockPos at = spot(player);
        if (at == null) {
            return false;
        }
        level.setBlock(at, WakeContent.HANGING_STRING.get().defaultBlockState(), 3);
        HANGS.put(player.getUUID(), new Hang(at, gameTime + 20L * 60 * 4));
        state.cooldown("string", gameTime, form == Reputation.Form.FURIOUS ? 20L * 60 * 4 : 20L * 60 * 7);
        TheWake.LOG.debug("[{}] a string at {}", player.getGameProfile().getName(), at);
        return false;
    }

    /**
     * Somewhere you are about to walk, under a ceiling three or four blocks up, dark, not in sight:
     * the string goes just under the ceiling and hangs down to face height.
     */
    @Nullable
    private static BlockPos spot(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 dir = Ambush.heading(player);
        RandomSource random = player.getRandom();
        for (int i = 0; i < 24; i++) {
            double dist = 4.0 + random.nextDouble() * 6.0;
            double spread = (random.nextDouble() - 0.5) * 1.2;
            Vec3 p = player.position().add(dir.scale(dist)).add(-dir.z * spread * dist * 0.3, 0.0, dir.x * spread * dist * 0.3);
            BlockPos floor = Spots.groundNear(level, Mth.floor(p.x), player.getBlockY(), Mth.floor(p.z), 2);
            if (floor == null || !Hunter.dark(level, floor)) {
                continue;
            }
            for (int h = 3; h <= 4; h++) {
                BlockPos ceiling = floor.above(h);
                boolean open = true;
                for (int k = 0; k < h; k++) {
                    if (!level.getBlockState(floor.above(k)).isAir()) {
                        open = false;
                        break;
                    }
                }
                if (!open || !level.getBlockState(ceiling).isFaceSturdy(level, ceiling, net.minecraft.core.Direction.DOWN)) {
                    continue;
                }
                BlockPos string = ceiling.below();
                if (!Spots.visibleFrom(player, floor)) {
                    return string;
                }
            }
        }
        return null;
    }

    /** You walked under it. */
    private static void drop(ServerPlayer player, HauntState state, Hang hang) {
        ServerLevel level = player.serverLevel();
        HANGS.remove(player.getUUID());
        level.setBlock(hang.pos(), Blocks.AIR.defaultBlockState(), 3);
        Vec3 at = Vec3.atBottomCenterOf(hang.pos()).add(0.0, -0.6, 0.0);
        WakeEntity body = Pursuit.spawn(player, at, WakeBrain.Purpose.AMBUSH);
        if (body != null) {
            body.brain().ambush(Ambush.Kind.CEILING, hang.pos());
            body.brain().dropOn(player);
            state.lastScareTick = level.getGameTime();
        }
    }

    /** You cut it: it falls, and for a moment it cannot get up. */
    public static void cut(ServerPlayer player, BlockPos pos) {
        Hang hang = HANGS.get(player.getUUID());
        if (hang == null || !hang.pos().equals(pos)) {
            return;
        }
        HANGS.remove(player.getUUID());
        HauntState state = Haunt.state(player);
        ServerLevel level = player.serverLevel();
        BlockPos floor = Spots.groundNear(level, pos.getX(), pos.getY() - 2, pos.getZ(), 3);
        if (floor == null || Pursuit.active(player)) {
            return;
        }
        WakeEntity body = Pursuit.spawn(player, Vec3.atBottomCenterOf(floor), WakeBrain.Purpose.AMBUSH);
        if (body != null) {
            level.playSound(null, floor, WakeRegistry.ROOF_THUD.get(), SoundSource.HOSTILE, 1.4F * WakeConfig.get().soundVolume, 0.6F);
            body.brain().fallen(player);
        }
    }
}
