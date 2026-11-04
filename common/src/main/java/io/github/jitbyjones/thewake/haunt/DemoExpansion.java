package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.entity.WakeBrain;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import io.github.jitbyjones.thewake.net.WakeFxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.world.LiminalPiece;
import io.github.jitbyjones.thewake.world.LiminalStructure;
import io.github.jitbyjones.thewake.world.Passages;
import io.github.jitbyjones.thewake.world.TheSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Development only: with {@code -Dthewake.demo2=true}, stages the expansion one scene at a time (its
 * faces, a chase, a door torn out, a pillar, the water, the watchtower, the flash, the chat box, its
 * places) so each can be screenshotted by {@code DemoExpansionClient}.
 */
public final class DemoExpansion {
    public static final boolean ENABLED = Boolean.getBoolean("thewake.demo2");
    public static volatile int startTick = -1;

    private static BlockPos origin;

    private DemoExpansion() {}

    public static void onJoin(ServerPlayer player) {
        if (!ENABLED || !player.server.isSingleplayer()) {
            return;
        }
        TheWake.LOG.info("[demo2] staging the expansion for {}", player.getGameProfile().getName());
        startTick = player.server.getTickCount();
        at(40, () -> setup(player));
        at(60, () -> face(player, 0));
        at(100, () -> face(player, 1));
        at(140, () -> face(player, 2));
        at(180, () -> face(player, 3));
        at(220, () -> chase(player));
        at(380, () -> ironDoor(player));
        at(620, () -> pillar(player));
        at(800, () -> water(player));
        at(960, () -> watchtower(player));
        at(1040, () -> flash(player));
        at(1110, () -> possession(player));
        at(1330, () -> structures(player));
        at(1510, () -> archive(player));
        at(1600, () -> set(player));
        at(1740, () -> lostTime(player));
        at(1770, () -> lastNight(player));
    }

    private static void at(int tick, Runnable r) {
        Director.schedule(tick, () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                TheWake.LOG.error("[demo2] scene at {} failed", tick, e);
            }
        });
    }

    private static void clear(ServerPlayer player) {
        WakeEntity body = Pursuit.body(player);
        if (body != null) {
            Pursuit.ended(player, Haunt.state(player), body, Pursuit.Outcome.GAVE_UP);
        }
        Phantoms.removeIf(player, p -> true);
        Hunter.release(player, false);
        player.stopRiding();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        WakeNetwork.send(player, new WakeFxPayload(WakeFxPayload.HELD, 0, 0, 0, 0.0F, 0.0F));
    }

    private static void look(ServerPlayer player, Vec3 at) {
        Vec3 eye = player.position().add(0.0, 1.62, 0.0);
        float yaw = Spots.yawTowards(player.position(), at);
        float pitch = (float) -Math.toDegrees(Math.atan2(at.y - eye.y, at.subtract(eye).horizontalDistance()));
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch);
    }

    private static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            level.setBlock(p, state, Block.UPDATE_CLIENTS);
        }
    }

    private static void setup(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        HauntState state = Haunt.state(player);
        state.reset();
        Showcase.wakeUp(state);
        Haunt.force(player, state, Stage.HUNT.threshold + 0.02F);
        player.setGameMode(GameType.SURVIVAL);
        BlockPos base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.blockPosition());
        origin = base;
        // A wide flat yard at night.
        fill(level, base.offset(-40, -1, -40), base.offset(40, -1, 40), Blocks.GRASS_BLOCK.defaultBlockState());
        fill(level, base.offset(-40, 0, -40), base.offset(40, 14, 40), Blocks.AIR.defaultBlockState());
        level.setDayTime(18000L);
        player.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180.0F, 0.0F);
        player.getInventory().clearContent();
    }

    /** Its four faces, close, lit: watcher, uneasy, angry, furious. */
    private static void face(ServerPlayer player, int form) {
        clear(player);
        HauntState state = Haunt.state(player);
        state.reputation().setAnger(Reputation.Form.values()[form].threshold + 0.05F);
        state.syncDirty = true;
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 180.0F, 0.0F);
        Vec3 at = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() - 2.2);
        Phantom p = Phantoms.create(player, at, Spots.yawTowards(at, player.position()), Phantom.Mode.STILL, CreaturePose.STAND)
                .setTouch(0.0, false).setFlags(Phantom.FLAG_LIT | Phantom.FLAG_NO_TWITCH);
        look(player, at.add(0.0, 2.75, 0.0));
    }

    /** In the open, furious: it runs at you and has you. */
    private static void chase(ServerPlayer player) {
        clear(player);
        HauntState state = Haunt.state(player);
        state.reputation().setAnger(0.8F);
        state.syncDirty = true;
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 180.0F, -8.0F);
        Vec3 at = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() - 16.5);
        Pursuit.spawn(player, at, WakeBrain.Purpose.CHASE);
        look(player, at.add(0.0, 2.0, 0.0));
    }

    /** Shut in behind an iron door. It knocks, and then the door comes out of the wall. */
    private static void ironDoor(ServerPlayer player) {
        clear(player);
        Haunt.state(player).reputation().setAnger(0.8F);
        ServerLevel level = player.serverLevel();
        BlockPos room = origin.offset(14, 0, 0);
        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
        fill(level, room.offset(-2, -1, -2), room.offset(2, 3, 2), stone);
        fill(level, room.offset(-1, 0, -1), room.offset(1, 2, 1), Blocks.AIR.defaultBlockState());
        BlockPos door = room.offset(0, 0, -2);
        BlockState d = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        level.setBlock(door, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
        level.setBlock(door.above(), d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        level.setBlock(room.offset(1, 2, 1), Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        player.connection.teleport(room.getX() + 0.5, room.getY(), room.getZ() + 1.3, 180.0F, 5.0F);
        Vec3 at = Vec3.atBottomCenterOf(room.offset(0, 0, -9));
        Pursuit.spawn(player, at, WakeBrain.Purpose.CHASE);
        look(player, Vec3.atCenterOf(door.above()));
    }

    /** Up a pillar with a lip round the top. It climbs, and breaks the lip. */
    private static void pillar(ServerPlayer player) {
        clear(player);
        Haunt.state(player).reputation().setAnger(0.8F);
        ServerLevel level = player.serverLevel();
        BlockPos foot = origin.offset(-14, 0, 0);
        BlockState dirt = Blocks.COBBLESTONE.defaultBlockState();
        fill(level, foot, foot.above(6), dirt);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            level.setBlock(foot.above(6).relative(dir), dirt, Block.UPDATE_CLIENTS);
        }
        player.connection.teleport(foot.getX() + 0.5, foot.getY() + 7, foot.getZ() + 0.5, 0.0F, 50.0F);
        Vec3 at = Vec3.atBottomCenterOf(foot.offset(0, 0, 9));
        Pursuit.spawn(player, at, WakeBrain.Purpose.CHASE);
        look(player, at.add(0.0, 1.0, 0.0));
    }

    /** In the pond. It comes over the water at you, and under it. */
    private static void water(ServerPlayer player) {
        clear(player);
        Haunt.state(player).reputation().setAnger(0.8F);
        ServerLevel level = player.serverLevel();
        BlockPos pond = origin.offset(0, 0, 22);
        fill(level, pond.offset(-5, -3, -5), pond.offset(5, -1, 5), Blocks.WATER.defaultBlockState());
        fill(level, pond.offset(-6, -4, -6), pond.offset(6, -4, 6), Blocks.DIRT.defaultBlockState());
        player.connection.teleport(pond.getX() + 0.5, pond.getY() - 1.6, pond.getZ() + 0.5, 0.0F, 0.0F);
        Vec3 at = Vec3.atBottomCenterOf(pond.offset(0, 0, -14));
        Pursuit.spawn(player, at, WakeBrain.Purpose.CHASE);
        look(player, at.add(0.0, 2.0, 0.0));
    }

    /** On a lookout, watching the dark. Turn round. */
    private static void watchtower(ServerPlayer player) {
        clear(player);
        ServerLevel level = player.serverLevel();
        BlockPos base = origin.offset(-14, 0, 22);
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        fill(level, base, base.offset(2, 7, 2), planks);
        player.connection.teleport(base.getX() + 1.5, base.getY() + 8, base.getZ() + 1.5, 0.0F, 0.0F);
        Vec3 behind = new Vec3(base.getX() + 1.5, base.getY() + 8, base.getZ() - 0.4);
        WakeEntity body = Pursuit.spawn(player, behind, WakeBrain.Purpose.LURK);
        if (body != null) {
            body.setYRot(0.0F);
            body.yBodyRot = 0.0F;
            body.brain(player).debugLedge(player);
        }
        at(30, () -> player.connection.teleport(player.getX(), player.getY(), player.getZ(), 180.0F, 30.0F));
    }

    /** The flash, in its face. */
    private static void flash(ServerPlayer player) {
        clear(player);
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 180.0F, 0.0F);
        player.getInventory().setItem(player.getInventory().selected, new ItemStack(WakeContent.OLD_CAMERA.get()));
        player.getInventory().add(new ItemStack(WakeContent.FLASH_BULB.get(), 8));
        Vec3 at = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() - 9.5);
        Pursuit.spawn(player, at, WakeBrain.Purpose.CHASE);
        look(player, at.add(0.0, 2.6, 0.0));
        at(18, () -> {
            WakeEntity b = Pursuit.body(player);
            if (b != null) {
                look(player, b.position().add(0.0, 2.6, 0.0));
            }
            Flash.fire(player);
        });
    }

    /** Creative mode, and your chat box, typing by itself. */
    private static void possession(ServerPlayer player) {
        clear(player);
        player.setGameMode(GameType.CREATIVE);
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 180.0F, 0.0F);
        HauntState state = Haunt.state(player);
        Possession.demand(player, state, Possession.Why.SWITCHED);
    }

    /** Its places, built in the yard one after another. */
    private static void structures(ServerPlayer player) {
        clear(player);
        player.setGameMode(GameType.SURVIVAL);
        ServerLevel level = player.serverLevel();
        BlockPos row = origin.offset(-30, 0, -32);
        int x = 0;
        for (LiminalStructure.Kind kind : LiminalStructure.Kind.values()) {
            BlockPos at = row.offset(x, 0, 0);
            LiminalPiece piece = new LiminalPiece(kind, at, Direction.NORTH);
            BoundingBox box = piece.getBoundingBox();
            piece.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), level.getRandom(),
                    new BoundingBox(box.minX() - 1, box.minY() - 8, box.minZ() - 1, box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1),
                    new ChunkPos(at), at);
            x += kind.width + 4;
        }
        level.setDayTime(6000L);
        player.connection.teleport(row.getX() + 27.5, row.getY() + 6, row.getZ() + 44.0, 180.0F, 12.0F);
        at(60, () -> player.connection.teleport(row.getX() + 9.5, row.getY() + 1, row.getZ() + 17.0, 180.0F, -14.0F));
        at(90, () -> player.connection.teleport(row.getX() + 29.5, row.getY() + 1, row.getZ() + 8.5, 180.0F, 5.0F));
        at(120, () -> player.connection.teleport(row.getX() + 42.5, row.getY() + 1, row.getZ() + 10.5, 180.0F, 8.0F));
        at(150, () -> player.connection.teleport(row.getX() + 52.5, row.getY() + 1, row.getZ() + 26.5, 180.0F, 2.0F));
    }

    private static void archive(ServerPlayer player) {
        clear(player);
        Passages.intoArchive(player);
    }

    private static void set(ServerPlayer player) {
        clear(player);
        WakeDimensions.home(player);
        at(20, () -> TheSet.enter(player));
    }

    /**
     * The end of it: three pins already out, the tape in the machine; the flash, the last pin, the
     * eject phase with its forced screens, and eject.
     */
    private static void lastNight(ServerPlayer player) {
        clear(player);
        WakeDimensions.home(player);
        ServerLevel level = player.server.overworld();
        level.setDayTime(18000L);
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 180.0F, 0.0F);
        BlockPos vcr = origin.offset(0, 0, 2);
        level.setBlock(vcr, WakeContent.VCR.get().defaultBlockState(), Block.UPDATE_ALL);
        HauntState state = Haunt.state(player);
        while (Temper.pins(state) < 3) {
            Temper.pullPin(state, false);
        }
        if (LastNight.begin(player, vcr)) {
            io.github.jitbyjones.thewake.block.VcrBlock.setTape(level, vcr, true);
        }
        at(100, () -> {
            WakeEntity b = Pursuit.body(player);
            if (b != null) {
                look(player, b.position().add(0.0, 2.6, 0.0));
                Flash.fire(player);
                LastNight.pullPin(player, state, b, false);
            }
        });
        at(260, () -> {
            look(player, Vec3.atCenterOf(vcr));
            LastNight.eject(player, vcr);
        });
    }

    private static void lostTime(ServerPlayer player) {
        WakeNetwork.send(player, WakeFxPayload.of(WakeFxPayload.LOST_TIME, 4372, 0.0F));
    }
}
