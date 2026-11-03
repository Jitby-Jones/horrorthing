package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.stopped.StoppedManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/**
 * Development only: with {@code -Dthewake.demo=true}, stages each beat in order on the first player
 * to join a singleplayer world, so the visuals can be checked (and screenshotted) in one run.
 * Dormant otherwise. The client half takes the screenshots on the same timeline.
 */
public final class DemoScript {
    public static final boolean ENABLED = Boolean.getBoolean("thewake.demo");
    /** Server tick count when the demo began; the client's screenshots are keyed to it. -1 before. */
    public static volatile int startTick = -1;

    private DemoScript() {}

    public static void onJoin(ServerPlayer player) {
        if (!ENABLED || !player.server.isSingleplayer()) {
            return;
        }
        TheWake.LOG.info("[demo] staging beats for {}", player.getGameProfile().getName());
        startTick = player.server.getTickCount();
        Director.schedule(40, () -> setup(player));
        Director.schedule(80, () -> sighting(player, 9.0));
        Director.schedule(160, () -> residue(player));
        Director.schedule(260, () -> headTurn(player));
        Director.schedule(400, () -> night(player));
        Director.schedule(520, () -> stopped(player));
        // Step onto its path: get caught.
        Director.schedule(740, () -> player.connection.teleport(origin.getX() - 6.5, origin.getY(), origin.getZ() + 10.5, -90.0F, 10.0F));
        // A second night in the Stopped: take a bed somewhere far from every old path, and sleep.
        Director.schedule(1000, () -> secondNight(player));
        Director.schedule(1030, () -> escapeSleep(player));
    }

    private static void secondNight(ServerPlayer player) {
        HauntState state = Haunt.state(player);
        if (StoppedManager.isIn(player) || state.ended()) {
            return;
        }
        StoppedManager.begin(player, state, player.serverLevel(), bedHead, Direction.NORTH, 18000L);
    }

    private static void escapeSleep(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos head = origin.offset(-4, 0, -17);
        BlockState bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(head.south(), bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        player.connection.teleport(head.getX() + 1.5, head.getY(), head.getZ() + 2.5, 180.0F, 10.0F);
        StoppedManager.onUseBlock(player, head);
    }

    private static BlockPos origin;
    private static BlockPos bedHead;

    private static void setup(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        HauntState state = Haunt.state(player);
        Showcase.wakeUp(state);
        BlockPos base = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.blockPosition());
        origin = base;
        // A flat yard to stand in: grass floor, nothing above.
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -24; dz <= 22; dz++) {
                BlockPos floor = base.offset(dx, -1, dz);
                level.setBlock(floor, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int dy = 0; dy < 8; dy++) {
                    level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        bedHead = base.offset(3, 0, 10);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(bedHead.south(), bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        level.setBlock(bedHead, bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        level.setBlock(base.offset(6, 0, 9), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        state.setBed(bedHead, Direction.NORTH.get2DDataValue());
        state.recordNightAt(bedHead);
        state.recordNightAt(bedHead);
        state.recordNightAt(bedHead);
        state.setLastLayDownDayTime(13200L);
        // A worn route: a loop through the yard, and a path out to the east.
        for (int i = 0; i <= 40; i++) {
            double a = i / 40.0 * Math.PI * 2.0;
            BlockPos p = base.offset((int) Math.round(Math.cos(a) * 7.0), 0, 10 + (int) Math.round(Math.sin(a) * 7.0));
            for (int k = 0; k < 6; k++) {
                state.crumbs().add(p);
            }
        }
        for (int x = 7; x <= 14; x++) {
            state.crumbs().add(base.offset(x, 0, 10));
        }
        player.getInventory().add(new ItemStack(Items.CLOCK));
        level.setDayTime(6000L);
        Haunt.force(player, state, 0.62F);
        player.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0.0F, 8.0F);
    }

    private static void sighting(ServerPlayer player, double distance) {
        HauntState state = Haunt.state(player);
        Vec3 at = Vec3.atBottomCenterOf(origin.offset(0, 0, (int) distance));
        Exposures.still(player, state, BlockPos.containing(at), 180.0F, Phantom.Stance.STAND).setFocus(1.0F).setTouch(0.0, false);
    }

    private static void residue(ServerPlayer player) {
        io.github.jitbyjones.thewake.phantom.Phantoms.removeIf(player, p -> true);
        // Stand near the bed, as if just woken.
        player.connection.teleport(bedHead.getX() - 2.5, bedHead.getY(), bedHead.getZ() - 3.5, -35.0F, 22.0F);
        HauntState state = Haunt.state(player);
        ServerLevel level = player.serverLevel();
        for (int i = 0; i < 5; i++) {
            BlockPos p = origin.offset(-6 + i * 2, 0, 6 + i);
            Exposures.passing(player, state, p, -60.0F).setOpacity(0.45F);
        }
        BlockPos side = Spots.bedside(level, bedHead, Direction.NORTH, player.position());
        if (side != null) {
            Exposures.still(player, state, side, Spots.yawTowards(Vec3.atBottomCenterOf(side), Vec3.atCenterOf(bedHead)),
                    Phantom.Stance.LOOK_DOWN).setTouch(0.0, false);
        }
        WakeNetwork.send(player, FxPayload.of(FxPayload.WAKE_TINT, 70));
    }

    private static void headTurn(ServerPlayer player) {
        io.github.jitbyjones.thewake.phantom.Phantoms.removeIf(player, p -> true);
        Residue.headTurn(player, Haunt.state(player), player.serverLevel(), bedHead, Direction.NORTH);
    }

    private static void night(ServerPlayer player) {
        io.github.jitbyjones.thewake.phantom.Phantoms.removeIf(player, p -> true);
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0.0F, 8.0F);
        player.serverLevel().setDayTime(18000L);
        sighting(player, 7.0);
    }

    private static void stopped(ServerPlayer player) {
        io.github.jitbyjones.thewake.phantom.Phantoms.removeIf(player, p -> true);
        HauntState state = Haunt.state(player);
        state.setClarity(1.0F);
        state.setFlag("head_turn");
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0.0F, 25.0F);
        StoppedManager.begin(player, state, player.serverLevel(), bedHead, Direction.NORTH, 18000L);
    }
}
