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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Development only: with {@code -Dthewake.demo3=true}, stages its tempers as models (each form up
 * close and whole, their own clips) and what they do (the small hand taking your torch, the neck
 * over a wall and up a pillar, the chewed-tape run, its marks, a string, its tape across a door),
 * one scene at a time, for {@code DemoFormsClient} to screenshot.
 */
public final class DemoForms {
    public static final boolean ENABLED = Boolean.getBoolean("thewake.demo3");
    public static volatile int startTick = -1;

    private static BlockPos origin;

    private DemoForms() {}

    public static void onJoin(ServerPlayer player) {
        if (!ENABLED || !player.server.isSingleplayer()) {
            return;
        }
        TheWake.LOG.info("[demo3] staging the forms for {}", player.getGameProfile().getName());
        startTick = player.server.getTickCount();
        at(40, () -> setup(player));
        int t = 60;
        for (int form = 0; form < 4; form++) {
            final int f = form;
            at(t, () -> still(player, f, CreaturePose.STAND, 0, 5.5, 0.0F));
            at(t + 30, () -> still(player, f, CreaturePose.STAND, 0, 2.3, 0.0F));
            t += 60;
        }
        // 300: their own clips, from the side
        at(300, () -> still(player, 1, CreaturePose.KNOCK, 1, 4.0, 60.0F));
        at(330, () -> still(player, 1, CreaturePose.PICK, 4, 4.0, 60.0F));
        at(360, () -> still(player, 1, CreaturePose.WALK, 2, 4.5, 75.0F));
        at(390, () -> still(player, 2, CreaturePose.REACH_WALL, 7, 6.0, 80.0F));
        at(420, () -> still(player, 2, CreaturePose.REACH_UP, 7, 6.5, 80.0F));
        at(450, () -> still(player, 2, CreaturePose.REACH_WINDOW, 7, 6.0, 80.0F));
        at(480, () -> still(player, 2, CreaturePose.WALK, 2, 5.0, 75.0F));
        at(510, () -> still(player, 3, CreaturePose.RUN, 2, 5.0, 75.0F));
        at(540, () -> still(player, 3, CreaturePose.LUNGE, 9, 4.0, 40.0F));
        at(570, () -> still(player, 3, CreaturePose.SCREAM, 4, 4.0, 20.0F));
        // 600: what they do
        at(600, () -> pick(player));
        at(700, () -> wall(player));
        at(840, () -> pillar(player));
        at(980, () -> chewed(player));
        at(1140, () -> traces(player));
    }

    private static void at(int tick, Runnable r) {
        Director.schedule(tick, () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                TheWake.LOG.error("[demo3] scene at {} failed", tick, e);
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
        WakeNetwork.send(player, new WakeFxPayload(WakeFxPayload.HELD, 0, 0, 0, 0.0F, 0.0F));
    }

    private static void temper(ServerPlayer player, int form) {
        HauntState state = Haunt.state(player);
        state.reputation().setAnger(Reputation.Form.values()[form].threshold + 0.05F);
        state.tension = 0.9F;
        state.syncDirty = true;
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
        fill(level, base.offset(-40, -1, -40), base.offset(40, -1, 40), Blocks.GRASS_BLOCK.defaultBlockState());
        fill(level, base.offset(-40, 0, -40), base.offset(40, 14, 40), Blocks.AIR.defaultBlockState());
        level.setDayTime(18000L);
        player.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 180.0F, 0.0F);
        player.getInventory().clearContent();
    }

    /** One form, lit, held on one frame of one clip, {@code distance} away, turned {@code turn} degrees. */
    private static void still(ServerPlayer player, int form, CreaturePose pose, int frame, double distance, float turn) {
        clear(player);
        temper(player, form);
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 180.0F, 0.0F);
        Vec3 at = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5 - distance);
        float yaw = Spots.yawTowards(at, player.position()) + turn;
        Phantoms.create(player, at, yaw, Phantom.Mode.STILL, pose).setPose(pose, frame)
                .setTouch(0.0, false).setFlags(Phantom.FLAG_LIT | Phantom.FLAG_NO_TWITCH);
        double h = distance < 3.0 ? 2.7 : 1.6;
        look(player, at.add(0.0, h, 0.0));
    }

    /** Uneasy: you are holding a torch. Round the corner, the small hand takes it. */
    private static void pick(ServerPlayer player) {
        clear(player);
        temper(player, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH, 8));
        player.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 180.0F, 0.0F);
        Vec3 at = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() - 2.3);
        Ambush.start(player, Haunt.state(player), at, Ambush.Kind.CORNER, null);
        look(player, at.add(0.0, 2.2, 0.0));
    }

    /** Angry: a wall three blocks high between you. Its face comes over the top, down your side. */
    private static void wall(ServerPlayer player) {
        clear(player);
        temper(player, 2);
        ServerLevel level = player.serverLevel();
        BlockPos w = origin.offset(18, 0, 0);
        fill(level, w.offset(-12, 0, 0), w.offset(12, 2, 0), Blocks.COBBLESTONE.defaultBlockState());
        player.connection.teleport(w.getX() + 0.5, w.getY(), w.getZ() + 2.6, 180.0F, 0.0F);
        Vec3 at = Vec3.atBottomCenterOf(w.offset(0, 0, -6));
        Pursuit.spawn(player, at, WakeBrain.Purpose.CHASE);
        look(player, Vec3.atCenterOf(w.above(3)));
    }

    /** Angry: up a pillar. It does not climb: its neck comes up, and its face hangs over you. */
    private static void pillar(ServerPlayer player) {
        clear(player);
        temper(player, 2);
        ServerLevel level = player.serverLevel();
        BlockPos foot = origin.offset(-18, 0, 0);
        fill(level, foot, foot.above(2), Blocks.COBBLESTONE.defaultBlockState());
        player.connection.teleport(foot.getX() + 0.5, foot.getY() + 3, foot.getZ() + 0.5, 0.0F, 30.0F);
        Vec3 at = Vec3.atBottomCenterOf(foot.offset(0, 0, 8));
        Pursuit.spawn(player, at, WakeBrain.Purpose.CHASE);
        look(player, at.add(0.0, 1.0, 0.0));
    }

    /** Furious: in the open, through a doorway. It comes like a chewed tape, and leaves tape in the door. */
    private static void chewed(ServerPlayer player) {
        clear(player);
        temper(player, 3);
        ServerLevel level = player.serverLevel();
        BlockPos w = origin.offset(0, 0, 18);
        fill(level, w.offset(-8, 0, 0), w.offset(8, 3, 0), Blocks.SPRUCE_PLANKS.defaultBlockState());
        fill(level, w, w.above(1), Blocks.AIR.defaultBlockState());
        player.connection.teleport(w.getX() + 0.5, w.getY(), w.getZ() + 8.5, 180.0F, 0.0F);
        Vec3 at = Vec3.atBottomCenterOf(w.offset(0, 0, -14));
        Pursuit.spawn(player, at, WakeBrain.Purpose.CHASE);
        look(player, Vec3.atCenterOf(w.above()));
    }

    /** Its marks on the floor, a string from the ceiling, its tape across a door. */
    private static void traces(ServerPlayer player) {
        clear(player);
        temper(player, 3);
        ServerLevel level = player.serverLevel();
        BlockPos room = origin.offset(0, 0, -20);
        fill(level, room.offset(-4, -1, -4), room.offset(4, 4, 4), Blocks.SPRUCE_PLANKS.defaultBlockState());
        fill(level, room.offset(-3, 0, -3), room.offset(3, 2, 3), Blocks.AIR.defaultBlockState());
        level.setBlock(room.offset(0, 0, -1), WakeContent.SPIKE_MARK.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(room.offset(-2, 0, 1), WakeContent.SPIKE_MARK.get().defaultBlockState()
                .setValue(io.github.jitbyjones.thewake.block.SpikeMarkBlock.TURN, 1), Block.UPDATE_ALL);
        level.setBlock(room.offset(1, 2, -2), WakeContent.HANGING_STRING.get().defaultBlockState(), Block.UPDATE_ALL);
        // a doorway in the far wall, tape across it
        fill(level, room.offset(0, 0, -4), room.offset(0, 1, -4), Blocks.AIR.defaultBlockState());
        level.setBlock(room.offset(0, 1, -4), WakeContent.SHED_TAPE.get().defaultBlockState()
                .setValue(io.github.jitbyjones.thewake.block.ShedTapeBlock.AXIS, Direction.Axis.X), Block.UPDATE_ALL);
        level.setBlock(room.offset(2, 0, 2), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        player.connection.teleport(room.getX() + 0.5, room.getY(), room.getZ() + 2.5, 180.0F, 20.0F);
    }
}
