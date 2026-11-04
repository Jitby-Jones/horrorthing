package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.PhantomBehavior;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Where it is when you find it: placed during a lapse, frozen by the time you are back. */
public final class Appearances {
    /** Scale of the far-off giant of Act II. */
    public static final float GIANT_SCALE = 4.0F;

    private Appearances() {}

    /** Still, at a block, facing a yaw, in a pose that fits the room. */
    public static Phantom still(ServerPlayer owner, BlockPos feet, float yaw, CreaturePose wanted) {
        CreaturePose pose = fitting(owner.serverLevel(), feet, wanted, owner.getRandom());
        return Phantoms.create(owner, Vec3.atBottomCenterOf(feet), yaw, Phantom.Mode.STILL, pose).setTouch(0.9, true);
    }

    /**
     * It is too tall for your house. Under a three-block ceiling it stands with its head against it
     * or stoops; where there are only two blocks it crawls.
     */
    public static CreaturePose fitting(ServerLevel level, BlockPos feet, CreaturePose wanted, RandomSource random) {
        int room = Field.clearance(level, feet, 4);
        if (room >= 4 || wanted.height <= room) {
            return wanted;
        }
        if (room == 3) {
            return wanted == CreaturePose.LUNGE ? CreaturePose.REACH : CreaturePose.STOOP;
        }
        return random.nextBoolean() ? CreaturePose.CRAWL_A : CreaturePose.CRAWL_B;
    }

    /** Degrees between where the player is looking and {@code at}. */
    public static float angleFromView(ServerPlayer player, Vec3 at) {
        Vec3 look = player.getViewVector(1.0F);
        Vec3 to = at.subtract(player.getEyePosition());
        double len = to.length();
        if (len < 1.0E-4) {
            return 0.0F;
        }
        return (float) Math.toDegrees(Math.acos(Mth.clamp(look.dot(to) / len, -1.0, 1.0)));
    }

    /**
     * Act II. Before it ever comes close: far off, on the horizon or above a treeline, four times
     * its size, facing your way. Somewhere you were not looking when you came back.
     */
    @Nullable
    public static Phantom giant(ServerPlayer owner) {
        ServerLevel level = owner.serverLevel();
        RandomSource random = owner.getRandom();
        int view = Math.min(owner.server.getPlayerList().getViewDistance(), Math.max(2, owner.requestedViewDistance()));
        double max = Math.min(120.0, view * 16.0 - 20.0);
        double min = Math.min(64.0, max - 10.0);
        if (max < 40.0) {
            return null;
        }
        float look = owner.getYHeadRot();
        for (int i = 0; i < 24; i++) {
            float side = random.nextBoolean() ? 1.0F : -1.0F;
            float yaw = look + side * (70.0F + random.nextFloat() * 80.0F);
            double dist = min + random.nextDouble() * (max - min);
            double rad = Math.toRadians(yaw);
            int x = Mth.floor(owner.getX() - Math.sin(rad) * dist);
            int z = Mth.floor(owner.getZ() + Math.cos(rad) * dist);
            if (!level.hasChunkAt(new BlockPos(x, owner.getBlockY(), z))) {
                continue;
            }
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (!level.getFluidState(top.below()).isEmpty() || top.getY() < level.getSeaLevel() - 4) {
                continue;
            }
            Vec3 at = Vec3.atBottomCenterOf(top);
            Phantom giant = Phantoms.create(owner, at, Spots.yawTowards(at, owner.position()), Phantom.Mode.STILL, CreaturePose.STAND)
                    .setScale(GIANT_SCALE).setTouch(0.0, false);
            giant.setBehavior(new PhantomBehavior() {
                @Override
                public void tick(Phantom p, ServerPlayer player) {
                    // Walk toward it and it is not there any more, the next time you look away.
                    if (p.pos().distanceToSqr(player.position()) < 40.0 * 40.0 && angleFromView(player, p.pos().add(0, 8, 0)) > 75.0F) {
                        Phantoms.remove(player, p);
                    }
                }
            });
            return giant;
        }
        return null;
    }

    /**
     * Back from a lapse, it is somewhere at the edge of your view: never in the middle of it, never
     * where you were already looking.
     */
    @Nullable
    public static Phantom sighting(ServerPlayer owner, HauntState state) {
        ServerLevel level = owner.serverLevel();
        RandomSource random = owner.getRandom();
        Stage stage = state.stage();
        double min = stage.atLeast(Stage.HUNT) ? 6.0 : stage.atLeast(Stage.ROUTINE) ? 9.0 : 14.0;
        double max = min * 1.8;
        float look = owner.getYHeadRot();
        for (int i = 0; i < 30; i++) {
            float side = random.nextBoolean() ? 1.0F : -1.0F;
            float yaw = look + side * (34.0F + random.nextFloat() * 22.0F);
            double dist = min + random.nextDouble() * (max - min);
            double rad = Math.toRadians(yaw);
            BlockPos spot = Spots.groundNear(level, Mth.floor(owner.getX() - Math.sin(rad) * dist), owner.getBlockY(),
                    Mth.floor(owner.getZ() + Math.cos(rad) * dist), 5);
            if (spot == null || Field.clearance(level, spot, 3) < 2) {
                continue;
            }
            Vec3 at = Vec3.atBottomCenterOf(spot);
            return still(owner, spot, Spots.yawTowards(at, owner.position()), CreaturePose.STAND);
        }
        return null;
    }
}
