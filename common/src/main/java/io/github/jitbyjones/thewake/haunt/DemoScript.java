package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Development only: with {@code -Dthewake.demo=true}, stages each beat in order on the first player
 * to join a singleplayer world, so the visuals can be checked (and screenshotted) in one run.
 * Dormant otherwise. The client half ({@code DemoClient}) opens menus and takes the screenshots on
 * the same timeline.
 */
public final class DemoScript {
    public static final boolean ENABLED = Boolean.getBoolean("thewake.demo");
    /** Server tick count when the demo began; the client's timeline is keyed to it. -1 before. */
    public static volatile int startTick = -1;

    /** Ticks (from the start) the client acts on. */
    public static final int OPEN_INVENTORY = 470;
    public static final int CLOSE_INVENTORY = 700;
    public static final int PAUSE = 720;

    private static BlockPos origin;
    private static BlockPos bedHead;

    private DemoScript() {}

    public static void onJoin(ServerPlayer player) {
        if (!ENABLED || !player.server.isSingleplayer()) {
            return;
        }
        TheWake.LOG.info("[demo] staging beats for {}", player.getGameProfile().getName());
        startTick = player.server.getTickCount();
        Director.schedule(40, () -> setup(player));
        Director.schedule(60, () -> giant(player));
        Director.schedule(100, () -> cornerOfEye(player));
        Director.schedule(135, () -> lookAt(player));
        Director.schedule(170, () -> behind(player));
        Director.schedule(200, () -> turnAround(player));
        Director.schedule(230, () -> footOfBed(player));
        Director.schedule(270, () -> headTurn(player));
        Director.schedule(440, () -> hunt(player));
        Director.schedule(760, () -> attack(player));
        Director.schedule(830, () -> stopped(player));
        // Step onto its path: get caught.
        Director.schedule(1050, () -> player.connection.teleport(origin.getX() + 2.5, origin.getY(), origin.getZ() - 3.5, 30.0F, 8.0F));
        // A second night in the Stopped: take a bed somewhere far from every old path, and sleep.
        Director.schedule(1310, () -> secondNight(player));
        Director.schedule(1340, () -> escapeSleep(player));
    }

    private static void setup(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        HauntState state = Haunt.state(player);
        // The demo world is reused: start every run from nothing.
        state.reset();
        Showcase.wakeUp(state);
        BlockPos base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.blockPosition());
        origin = base;
        // A flat yard to stand in: grass floor, nothing above.
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -24; dz <= 22; dz++) {
                level.setBlock(base.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int dy = 0; dy < 10; dy++) {
                    level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        // A hut: plank walls, a three-block ceiling, a door, a bed, one torch.
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        for (int dx = -1; dx <= 7; dx++) {
            for (int dz = 6; dz <= 14; dz++) {
                level.setBlock(base.offset(dx, -1, dz), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setBlock(base.offset(dx, 3, dz), planks, Block.UPDATE_CLIENTS);
                boolean wall = dx == -1 || dx == 7 || dz == 6 || dz == 14;
                for (int dy = 0; dy < 3 && wall; dy++) {
                    level.setBlock(base.offset(dx, dy, dz), planks, Block.UPDATE_CLIENTS);
                }
            }
        }
        BlockPos door = base.offset(3, 0, 14);
        BlockState doorState = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        level.setBlock(door, doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_CLIENTS);
        level.setBlock(door.above(), doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
        level.setBlock(base.offset(0, 1, 7), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH),
                Block.UPDATE_ALL);
        bedHead = base.offset(3, 0, 8);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(bedHead.south(), bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        level.setBlock(bedHead, bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        level.setBlock(base.offset(6, 0, 7), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        state.setBed(bedHead, Direction.NORTH.get2DDataValue());
        state.recordNightAt(bedHead);
        state.recordNightAt(bedHead);
        state.recordNightAt(bedHead);
        state.setLastLayDownDayTime(13200L);
        // A worn route: a loop round the yard to the west, and a path out to the east.
        for (int i = 0; i <= 40; i++) {
            double a = i / 40.0 * Math.PI * 2.0;
            BlockPos p = base.offset(-9 + (int) Math.round(Math.cos(a) * 5.0), 0, -4 + (int) Math.round(Math.sin(a) * 5.0));
            for (int k = 0; k < 6; k++) {
                state.crumbs().add(p);
            }
        }
        for (int x = -4; x <= 12; x++) {
            state.crumbs().add(base.offset(x, 0, -4));
        }
        // From the bed, out of the door, round the hut and up to the path.
        for (int z = 9; z <= 16; z++) {
            state.crumbs().add(base.offset(3, 0, z));
        }
        for (int x = -3; x <= 3; x++) {
            state.crumbs().add(base.offset(x, 0, 16));
        }
        for (int z = -4; z <= 16; z++) {
            state.crumbs().add(base.offset(-3, 0, z));
        }
        player.getInventory().add(new ItemStack(Items.CLOCK));
        level.setDayTime(6000L);
        Haunt.force(player, state, Stage.ROUTINE.threshold + 0.02F);
        player.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180.0F, 0.0F);
    }

    /** Act II: far off, four times its size. */
    private static void giant(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos column = origin.offset(10, 0, -40);
        // Standing in the canopy: head and shoulders above the trees.
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
        Vec3 at = Vec3.atBottomCenterOf(top);
        Phantom giant = Phantoms.create(player, at, Spots.yawTowards(at, player.position()), Phantom.Mode.STILL, CreaturePose.STAND)
                .setScale(Appearances.GIANT_SCALE).setTouch(0.0, false);
        TheWake.LOG.info("[demo] giant {} at {} (player at {})", giant.id(), at, player.position());
        Vec3 eye = player.position().add(0.0, 1.62, 0.0);
        Vec3 head = at.add(0.0, 10.0, 0.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(head.y - eye.y, head.subtract(eye).horizontalDistance()));
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, Spots.yawTowards(player.position(), at) - 25.0F,
                pitch * 0.6F);
    }

    /** At the edge of your view: its head jerks and tilts while you are not looking straight at it. */
    private static void cornerOfEye(ServerPlayer player) {
        Phantoms.removeIf(player, p -> true);
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 180.0F, 0.0F);
        // 36 degrees to the left of straight ahead (north), nine blocks away.
        double rad = Math.toRadians(180.0F - 36.0F);
        Vec3 at = new Vec3(origin.getX() + 0.5 - Math.sin(rad) * 9.0, origin.getY(), origin.getZ() + 0.5 + Math.cos(rad) * 9.0);
        Phantom p = Phantoms.create(player, at, Spots.yawTowards(at, player.position()), Phantom.Mode.STILL, CreaturePose.STAND)
                .setTouch(0.0, false);
        Hunter.adopt(player, p);
    }

    private static void lookAt(ServerPlayer player) {
        Phantom p = Hunter.creature(player);
        if (p != null) {
            float yaw = Spots.yawTowards(player.position(), p.pos());
            player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, yaw, -12.0F);
        }
    }

    /** Behind you, facing away. Turn round and its head has already turned to you. */
    private static void behind(ServerPlayer player) {
        Phantoms.removeIf(player, p -> true);
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 180.0F, 0.0F);
        Vec3 at = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() + 4.5);
        Phantom p = Phantoms.create(player, at, 0.0F, Phantom.Mode.STILL, CreaturePose.STAND).setTouch(0.0, false);
        Hunter.adopt(player, p);
    }

    private static void turnAround(ServerPlayer player) {
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0.0F, -16.0F);
    }

    private static void footOfBed(ServerPlayer player) {
        Phantoms.removeIf(player, p -> true);
        ServerLevel level = player.serverLevel();
        level.setDayTime(18000L);
        // Just woken: standing beside the bed, looking toward its foot.
        player.connection.teleport(bedHead.getX() + 1.5, bedHead.getY(), bedHead.getZ() + 0.5, 30.0F, -8.0F);
        Mornings.after(player, Haunt.state(player), level, bedHead, Direction.NORTH);
        face(player);
    }

    private static void headTurn(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        player.connection.teleport(bedHead.getX() + 2.5, bedHead.getY(), bedHead.getZ() + 4.5, 145.0F, 8.0F);
        Mornings.headTurn(player, Haunt.state(player), level, bedHead, Direction.NORTH);
        Phantoms.of(player).stream().filter(p -> !p.isRemoved()).findFirst().ifPresent(p -> face(player, p.pos(), 1.6));
    }

    /** Turns the player to look at the creature's face. */
    private static void face(ServerPlayer player) {
        Phantom p = Hunter.creature(player);
        if (p != null) {
            face(player, p.pos(), p.pose().height * 0.85);
        }
    }

    private static void face(ServerPlayer player, Vec3 feet, double height) {
        Vec3 eye = player.position().add(0.0, 1.62, 0.0);
        Vec3 target = feet.add(0.0, height, 0.0);
        float yaw = Spots.yawTowards(player.position(), feet);
        float pitch = (float) -Math.toDegrees(Math.atan2(target.y - eye.y, target.subtract(eye).horizontalDistance()));
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch);
    }

    /**
     * In the hut, the open door at the edge of the view. It starts out in the yard; the client
     * opens the inventory, and it comes in while the menu is up.
     */
    private static void hunt(ServerPlayer player) {
        Phantoms.removeIf(player, p -> true);
        Hunter.release(player, true);
        ServerLevel level = player.serverLevel();
        level.setDayTime(14000L);
        BlockPos door = origin.offset(3, 0, 14);
        BlockState state = level.getBlockState(door);
        if (state.getBlock() instanceof DoorBlock) {
            level.setBlock(door, state.setValue(DoorBlock.OPEN, true), Block.UPDATE_ALL);
        }
        Vec3 stand = new Vec3(origin.getX() + 1.5, origin.getY(), origin.getZ() + 10.5);
        float toDoor = Spots.yawTowards(stand, Vec3.atBottomCenterOf(door));
        player.connection.teleport(stand.x, stand.y, stand.z, toDoor + 32.0F, 12.0F);
        BlockPos yard = origin.offset(5, 0, 21);
        Vec3 at = Vec3.atBottomCenterOf(yard);
        Hunter.adopt(player, Appearances.still(player, yard, Spots.yawTowards(at, stand), CreaturePose.STAND).setTouch(0.0, false));
    }

    private static void attack(ServerPlayer player) {
        HauntState state = Haunt.state(player);
        Haunt.force(player, state, Stage.HUNT.threshold + 0.02F);
        state.setLastAttackDay(-1L);
        Phantom p = Hunter.creature(player);
        if (p == null) {
            BlockPos spot = Spots.behind(player, 2.0, 3.0, 20);
            if (spot == null) {
                return;
            }
            p = Appearances.still(player, spot, Spots.yawTowards(Vec3.atBottomCenterOf(spot), player.position()), CreaturePose.STAND);
        }
        Attack.begin(player, state, p);
    }

    private static void stopped(ServerPlayer player) {
        Phantoms.removeIf(player, p -> true);
        Hunter.release(player, false);
        HauntState state = Haunt.state(player);
        player.setHealth(player.getMaxHealth());
        state.setClarity(1.0F);
        state.setFlag("head_turn");
        player.connection.teleport(bedHead.getX() + 2.5, bedHead.getY(), bedHead.getZ() + 3.5, 140.0F, 25.0F);
        StoppedManager.begin(player, state, player.serverLevel(), bedHead, Direction.NORTH, 18000L);
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
        BlockPos head = origin.offset(12, 0, -18);
        BlockState bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(head.south(), bed.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_ALL);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
        player.connection.teleport(head.getX() + 1.5, head.getY(), head.getZ() + 2.5, 180.0F, 10.0F);
        StoppedManager.onUseBlock(player, head);
    }
}
