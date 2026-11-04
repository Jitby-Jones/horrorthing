package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.block.SpikeMarkBlock;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.registry.WakeContent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Its marks. Grey tape in an X on your floor, where it means to stand, the way a stage hand
 * marks a floor for an actor. You find them in the morning, on the way you always walk; furious,
 * beside your bed. Come near one in the dark and look away, and when you look back it may be
 * standing on it, waiting for its cue. Scrape one off and that one is gone; it minds.
 */
public final class Marks {
    private static final Map<UUID, Set<BlockPos>> USED = new HashMap<>();

    private Marks() {}

    public static void onServerStopped() {
        USED.clear();
    }

    /** Once a second, from {@link Pursuit#tick} (after the checks that it may come out at all). */
    static boolean tick(ServerPlayer player, HauntState state, long gameTime, Reputation.Form form) {
        if (!WakeConfig.get().worldEdits || !form.atLeast(Reputation.Form.UNEASY)) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        RandomSource random = player.getRandom();
        if (state.ready("mark", gameTime) && random.nextFloat() < 0.006F) {
            BlockPos at = placeFor(player, state, form);
            if (at != null) {
                level.setBlock(at, WakeContent.SPIKE_MARK.get().defaultBlockState().setValue(SpikeMarkBlock.TURN, random.nextInt(4)), 3);
                state.cooldown("mark", gameTime, switch (form) {
                    case FURIOUS -> 20L * 60 * 8;
                    case ANGRY -> 20L * 60 * 12;
                    default -> 20L * 60 * 20;
                });
            }
        }
        if (gameTime % 40L != 0L || state.tension < 0.3F || !WakeConfig.get().ambushes) {
            return false;
        }
        // One of its marks, near you, behind you.
        BlockPos mark = nearestUnused(player, 10);
        if (mark == null) {
            return false;
        }
        double d = Vec3.atBottomCenterOf(mark).distanceTo(player.position());
        if (d < 3.0 || Appearances.angleFromView(player, Vec3.atCenterOf(mark).add(0.0, 1.0, 0.0)) < 75.0F || !Hunter.dark(level, mark)) {
            return false;
        }
        USED.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(mark);
        float chance = switch (form) {
            case FURIOUS -> 0.8F;
            case ANGRY -> 0.5F;
            default -> 0.3F;
        };
        if (random.nextFloat() >= chance || !Spots.standable(level, mark)) {
            return false;
        }
        return Ambush.start(player, state, Vec3.atBottomCenterOf(mark), Ambush.Kind.MARK, mark);
    }

    @Nullable
    private static BlockPos nearestUnused(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        Set<BlockPos> used = USED.getOrDefault(player.getUUID(), Set.of());
        BlockPos at = player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-radius, -3, -radius), at.offset(radius, 3, radius))) {
            if (!level.getBlockState(p).is(WakeContent.SPIKE_MARK.get()) || used.contains(p)) {
                continue;
            }
            double d = p.distSqr(at);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /** Where it marks: beside your bed if it is furious; otherwise somewhere you will walk past. */
    @Nullable
    private static BlockPos placeFor(ServerPlayer player, HauntState state, Reputation.Form form) {
        ServerLevel level = player.serverLevel();
        if (form == Reputation.Form.FURIOUS && state.bed() != null && level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            BlockPos head = Spots.bedHead(level, state.bed());
            Direction facing = head == null ? null : Spots.bedFacing(level, head);
            if (head != null && facing != null) {
                BlockPos side = Spots.bedside(level, head, facing, null);
                if (side != null && free(level, side) && !Spots.visibleFrom(player, side)) {
                    return side;
                }
            }
        }
        BlockPos spot = Spots.standableNear(level, player.blockPosition(), 6, 14, player.getRandom(),
                p -> free(level, p) && !Spots.visibleFrom(player, p) && Hunter.dark(level, p), 30);
        return spot;
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() && WakeContent.SPIKE_MARK.get().defaultBlockState().canSurvive(level, p);
    }

    /** You scraped one off. */
    public static void scraped(ServerPlayer player, BlockPos pos) {
        HauntState state = Haunt.state(player);
        Reputation r = state.reputation();
        r.setAnger(r.anger() + 0.01F);
        player.serverLevel().playSound(null, pos, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 0.6F, 0.7F);
    }
}
