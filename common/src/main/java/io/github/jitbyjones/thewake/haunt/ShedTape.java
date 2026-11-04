package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.entity.WakeBrain;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import io.github.jitbyjones.thewake.registry.WakeContent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * FURIOUS sheds tape. Where it has been through a doorway, a length of it is left strung across
 * at chest height, and in the night it strings more across the doorways round you. It does not
 * stop you. It rustles when you go through, and it hears that, and then it knows where you are.
 */
public final class ShedTape {
    private static final int KEEP = 8;
    private static final Map<UUID, Deque<BlockPos>> PLACED = new HashMap<>();
    private static final Map<UUID, Long> LAST_RUSTLE = new HashMap<>();

    private ShedTape() {}

    public static void onServerStopped() {
        PLACED.clear();
        LAST_RUSTLE.clear();
    }

    /** Every 10 ticks while its body is out: if it is in a doorway, it leaves tape there. */
    public static void trail(ServerPlayer owner, WakeEntity body) {
        if (!WakeConfig.get().worldEdits || body.form() != Reputation.Form.FURIOUS) {
            return;
        }
        BlockPos chest = body.blockPosition().above();
        Direction.Axis across = doorway((ServerLevel) body.level(), chest);
        if (across != null) {
            string(owner, chest, across);
        }
    }

    /** Once a second at night, furious: now and then, across a doorway near you, out of sight. */
    static void tick(ServerPlayer player, HauntState state, long gameTime, Reputation.Form form) {
        if (form != Reputation.Form.FURIOUS || !WakeConfig.get().worldEdits || !state.ready("shed_tape", gameTime)
                || player.getRandom().nextFloat() >= 0.01F) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos at = player.blockPosition();
        for (int i = 0; i < 40; i++) {
            BlockPos p = at.offset(player.getRandom().nextInt(17) - 8, player.getRandom().nextInt(3) - 1, player.getRandom().nextInt(17) - 8);
            BlockPos chest = p.above();
            Direction.Axis across = doorway(level, chest);
            if (across != null && !Spots.visibleFrom(player, p)) {
                string(player, chest, across);
                state.cooldown("shed_tape", gameTime, 20L * 60 * 3);
                return;
            }
        }
    }

    /** If {@code chest} is the middle of a one-wide gap at chest height, the axis the tape runs along. */
    @Nullable
    static Direction.Axis doorway(ServerLevel level, BlockPos chest) {
        if (!level.getBlockState(chest).isAir() || !level.getBlockState(chest.below()).isAir()) {
            return null;
        }
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            Direction a = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
            boolean walls = solid(level, chest.relative(a)) && solid(level, chest.relative(a.getOpposite()));
            Direction b = a.getClockWise();
            boolean open = level.getBlockState(chest.relative(b)).isAir() && level.getBlockState(chest.relative(b.getOpposite())).isAir();
            if (walls && open) {
                return axis;
            }
        }
        return null;
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).isCollisionShapeFullBlock(level, p);
    }

    private static void string(ServerPlayer owner, BlockPos chest, Direction.Axis across) {
        ServerLevel level = owner.serverLevel();
        Deque<BlockPos> placed = PLACED.computeIfAbsent(owner.getUUID(), k -> new ArrayDeque<>());
        if (placed.contains(chest)) {
            return;
        }
        level.setBlock(chest, WakeContent.SHED_TAPE.get().defaultBlockState()
                .setValue(io.github.jitbyjones.thewake.block.ShedTapeBlock.AXIS, across), 3);
        placed.addLast(chest);
        while (placed.size() > KEEP) {
            BlockPos old = placed.removeFirst();
            if (level.getBlockState(old).is(WakeContent.SHED_TAPE.get())) {
                level.setBlock(old, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** You went through some. It rustles; it hears; it knows where you are. */
    public static void walkedThrough(ServerPlayer player, BlockPos pos) {
        long now = player.level().getGameTime();
        Long last = LAST_RUSTLE.get(player.getUUID());
        if (last != null && now - last < 40) {
            return;
        }
        LAST_RUSTLE.put(player.getUUID(), now);
        ServerLevel level = player.serverLevel();
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 0.5F);
        level.playSound(null, pos, SoundEvents.AZALEA_LEAVES_STEP, SoundSource.BLOCKS, 0.8F, 0.6F);
        HauntState state = Haunt.state(player);
        Tension.spike(state, 0.12F);
        WakeEntity body = Pursuit.body(player);
        if (body != null && body.brain() != null) {
            body.brain().heard(player, Vec3.atBottomCenterOf(pos));
            return;
        }
        if (Temper.form(state) == Reputation.Form.FURIOUS && Pursuit.allowed(player, state) && player.getRandom().nextFloat() < 0.4F) {
            Vec3 at = Pursuit.near(player, 12.0, 18.0, true);
            if (at != null) {
                Pursuit.spawn(player, at, WakeBrain.Purpose.STALK);
            }
        }
    }
}
