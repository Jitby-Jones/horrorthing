package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.net.WakeFxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Things that happen with nothing to see. Most of the time it is not trying to hurt you; it wants
 * you to know it is there. Someone knocks on the outside of your wall, three times. Something
 * breathes on the other side of the wall you are facing. Footsteps behind you stop a moment after
 * you do. A door in your house opens on its own and, later, closes. A chest lid lifts. Something
 * heavy walks across your roof. In a cave, stone scrapes on stone, further in. And you hear your
 * own footsteps, walking the way you walked yesterday.
 *
 * <p>Every one is sent to you alone, chosen for where you are, and spends a little of the
 * director's tension: noises get you ready; they are not the scare.
 */
public final class Torment {
    private enum Event { KNOCK, BREATH_WALL, STEPS_BEHIND, SCRAPE, DOOR, CHEST, ROOF, DISTANT_SCREAM, WHISPER, FLICKER, YESTERDAY, MIMIC, SHUTTER,
        MUSIC_BOX, CRYING, CHAINS, TELEVISION }

    private Torment() {}

    /** Once a second. */
    static void tick(ServerPlayer player, HauntState state, long gameTime) {
        WakeConfig config = WakeConfig.get();
        if (!config.torment || !Haunt.awake(state) || Temper.removed(state) || state.chasing || Pursuit.active(player)
                || !state.peakStage().atLeast(Stage.NIGHT_SHIFT) || player.isSleeping() || Hunter.hunting(player)) {
            return;
        }
        if (!state.ready("torment", gameTime) || gameTime - state.lastScareTick < 20L * 50) {
            return;
        }
        ServerLevel level = player.serverLevel();
        RandomSource random = player.getRandom();
        Reputation.Form form = Temper.form(state);
        boolean night = Haunt.isNight(level) || !level.dimensionType().hasSkyLight();
        float chance = 0.006F + state.tension * 0.03F + form.ordinal() * 0.006F;
        if (night) {
            chance *= 2.0F;
        }
        if (!state.peakStage().atLeast(Stage.WATCHING)) {
            chance *= 0.4F;
        }
        if (random.nextFloat() >= chance) {
            return;
        }
        List<Event> options = options(player, state, level, night);
        if (options.isEmpty()) {
            return;
        }
        Event event = options.get(random.nextInt(options.size()));
        if (run(player, state, level, event, random)) {
            long base = switch (form) {
                case WATCHER -> 20L * 150;
                case UNEASY -> 20L * 100;
                case ANGRY -> 20L * 70;
                case FURIOUS -> 20L * 45;
            };
            state.cooldown("torment", gameTime, base + random.nextInt((int) base));
            Tension.spike(state, 0.06F + 0.02F * form.ordinal());
        }
    }

    private static List<Event> options(ServerPlayer player, HauntState state, ServerLevel level, boolean night) {
        List<Event> out = new ArrayList<>();
        boolean underground = level.getBrightness(LightLayer.SKY, player.blockPosition().above()) == 0 && player.getY() < level.getSeaLevel() - 4;
        boolean roofed = roof(level, player) != null;
        boolean moving = player.walkDist - player.walkDistO > 0.08 && player.onGround();
        out.add(Event.WHISPER);
        if (roofed) {
            out.add(Event.KNOCK);
            out.add(Event.KNOCK);
            out.add(Event.ROOF);
            if (level.getBrightness(LightLayer.BLOCK, player.blockPosition().above()) >= 8) {
                out.add(Event.FLICKER);
            }
        }
        if (facingWall(level, player) != null) {
            out.add(Event.BREATH_WALL);
            out.add(Event.BREATH_WALL);
        }
        if (moving) {
            out.add(Event.STEPS_BEHIND);
            out.add(Event.STEPS_BEHIND);
        }
        if (underground) {
            out.add(Event.SCRAPE);
            out.add(Event.SCRAPE);
        }
        if (WakeConfig.get().worldEdits && door(level, player) != null) {
            out.add(Event.DOOR);
        }
        if (container(level, player) != null) {
            out.add(Event.CHEST);
        }
        if (night && !roofed && !underground && level.dimensionType().hasSkyLight()) {
            out.add(Event.DISTANT_SCREAM);
        }
        if (state.yesterday().size() >= 8 && state.peakStage().atLeast(Stage.SIGHTINGS)) {
            out.add(Event.YESTERDAY);
        }
        if (state.peakStage().atLeast(Stage.WATCHING)) {
            out.add(Event.MIMIC);
        }
        if (state.peakStage().atLeast(Stage.ROUTINE) && Temper.form(state).atLeast(Reputation.Form.UNEASY)) {
            out.add(Event.SHUTTER);
        }
        if (roofed && night && state.peakStage().atLeast(Stage.SIGHTINGS)) {
            out.add(Event.MUSIC_BOX);
        }
        if (night && state.peakStage().atLeast(Stage.SIGHTINGS) && Temper.form(state).atLeast(Reputation.Form.UNEASY)) {
            out.add(Event.CRYING);
        }
        if (underground || WakeDimensions.isOurs(level)) {
            out.add(Event.CHAINS);
        }
        if (television(level, player) != null) {
            out.add(Event.TELEVISION);
        }
        return out;
    }

    private static boolean run(ServerPlayer player, HauntState state, ServerLevel level, Event event, RandomSource random) {
        float v = WakeConfig.get().soundVolume;
        switch (event) {
            case KNOCK -> {
                Vec3 at = outsideWall(level, player, random);
                if (at == null) {
                    return false;
                }
                // Three, evenly, and then nothing.
                int gap = 8 + random.nextInt(4);
                for (int i = 0; i < 3; i++) {
                    Director.schedule(1 + i * gap, () -> play(player, WakeRegistry.KNOCK.get(), at, 1.0F * v, 0.92F + random.nextFloat() * 0.08F));
                }
                if (random.nextInt(4) == 0) {
                    // Then once more, somewhere else on the wall.
                    Vec3 again = outsideWall(level, player, random);
                    if (again != null) {
                        Director.schedule(3 * gap + 60 + random.nextInt(60), () -> play(player, WakeRegistry.KNOCK.get(), again, 1.1F * v, 0.85F));
                    }
                }
                return true;
            }
            case BREATH_WALL -> {
                Vec3 at = facingWall(level, player);
                if (at == null) {
                    return false;
                }
                play(player, WakeRegistry.BREATH_WALL.get(), at, 0.9F * v, 0.9F + random.nextFloat() * 0.15F);
                return true;
            }
            case STEPS_BEHIND -> {
                int steps = 4 + random.nextInt(4);
                for (int i = 0; i < steps; i++) {
                    final int n = i;
                    Director.schedule(6 + i * 7, () -> {
                        if (player.hasDisconnected()) {
                            return;
                        }
                        boolean stopped = player.walkDist - player.walkDistO < 0.02;
                        if (stopped && n > 1 && random.nextInt(3) > 0) {
                            return;
                        }
                        Vec3 back = behind(player, 3.5 + n * 0.1);
                        if (random.nextBoolean()) {
                            play(player, WakeRegistry.STEPS.get(), back, 0.45F * v, 0.9F + random.nextFloat() * 0.1F);
                        } else {
                            BlockState under = level.getBlockState(BlockPos.containing(back).below());
                            play(player, under.getSoundType().getStepSound(), back, 0.35F * v, 0.75F);
                        }
                    });
                }
                return true;
            }
            case SCRAPE -> {
                Vec3 at = caveSpot(level, player, random);
                if (at == null) {
                    return false;
                }
                play(player, WakeRegistry.SCRAPE.get(), at, 1.6F * v, 0.85F + random.nextFloat() * 0.2F);
                return true;
            }
            case DOOR -> {
                BlockPos door = door(level, player);
                if (door == null) {
                    return false;
                }
                BlockState s = level.getBlockState(door);
                if (!(s.getBlock() instanceof DoorBlock block) || s.getValue(DoorBlock.OPEN)) {
                    return false;
                }
                play(player, WakeRegistry.DOOR_CREAK.get(), Vec3.atCenterOf(door), 0.9F * v, 0.9F);
                block.setOpen(null, level, s, door, true);
                Director.schedule(60 + random.nextInt(80), () -> {
                    BlockState now = level.getBlockState(door);
                    if (now.getBlock() instanceof DoorBlock b && now.getValue(DoorBlock.OPEN)
                            && Appearances.angleFromView(player, Vec3.atCenterOf(door)) > 60.0F) {
                        b.setOpen(null, level, now, door, false);
                    }
                });
                return true;
            }
            case CHEST -> {
                BlockPos chest = container(level, player);
                if (chest == null) {
                    return false;
                }
                Block block = level.getBlockState(chest).getBlock();
                if (block instanceof ChestBlock) {
                    level.blockEvent(chest, block, 1, 1);
                    play(player, SoundEvents.CHEST_OPEN, Vec3.atCenterOf(chest), 0.6F, 0.8F);
                    Director.schedule(30 + random.nextInt(30), () -> {
                        level.blockEvent(chest, block, 1, 0);
                        play(player, SoundEvents.CHEST_CLOSE, Vec3.atCenterOf(chest), 0.6F, 0.8F);
                    });
                } else {
                    play(player, SoundEvents.BARREL_OPEN, Vec3.atCenterOf(chest), 0.6F, 0.8F);
                    Director.schedule(30 + random.nextInt(30), () -> play(player, SoundEvents.BARREL_CLOSE, Vec3.atCenterOf(chest), 0.6F, 0.8F));
                }
                return true;
            }
            case ROOF -> {
                BlockPos roof = roof(level, player);
                if (roof == null) {
                    return false;
                }
                // Across the roof, over your head, and away.
                float yaw = random.nextFloat() * 360.0F;
                Vec3 dir = Vec3.directionFromRotation(0.0F, yaw);
                Vec3 start = Vec3.atCenterOf(roof).add(0.0, 0.6, 0.0).subtract(dir.scale(4.0));
                int steps = 5 + random.nextInt(3);
                for (int i = 0; i < steps; i++) {
                    final Vec3 at = start.add(dir.scale(i * 1.4));
                    Director.schedule(i * 11, () -> play(player, WakeRegistry.ROOF_THUD.get(), at, 1.0F * v, 0.75F + random.nextFloat() * 0.1F));
                }
                return true;
            }
            case DISTANT_SCREAM -> {
                Vec3 dir = Vec3.directionFromRotation(0.0F, random.nextFloat() * 360.0F);
                Vec3 at = player.position().add(dir.scale(70.0 + random.nextInt(30))).add(0.0, 4.0, 0.0);
                play(player, WakeRegistry.DISTANT_SCREAM.get(), at, 7.0F * WakeConfig.get().screamVolume, 0.85F + random.nextFloat() * 0.15F);
                return true;
            }
            case WHISPER -> {
                Vec3 at = player.getEyePosition().subtract(player.getViewVector(1.0F).scale(0.8));
                play(player, WakeRegistry.WHISPER.get(), at, 0.32F * v, 0.9F + random.nextFloat() * 0.1F);
                return true;
            }
            case FLICKER -> {
                WakeNetwork.send(player, WakeFxPayload.of(WakeFxPayload.FLICKER, 0.7F, 16 + random.nextInt(20)));
                return true;
            }
            case YESTERDAY -> {
                return yesterday(player, state, level, random);
            }
            case MIMIC -> {
                SoundEvent[] yours = {SoundEvents.WOODEN_DOOR_OPEN, SoundEvents.CHEST_OPEN, SoundEvents.GENERIC_EAT, SoundEvents.STONE_BREAK,
                        SoundEvents.WOODEN_DOOR_CLOSE, SoundEvents.WOOD_PLACE, SoundEvents.PLAYER_BURP, SoundEvents.ITEM_PICKUP};
                Vec3 at = behind(player, 7.0 + random.nextInt(5));
                play(player, yours[random.nextInt(yours.length)], at, 0.7F, 0.95F + random.nextFloat() * 0.1F);
                return true;
            }
            case MUSIC_BOX -> {
                Vec3 at = outsideWall(level, player, random);
                if (at == null) {
                    return false;
                }
                play(player, WakeRegistry.MUSIC_BOX.get(), at, 0.45F * v, 0.92F + random.nextFloat() * 0.08F);
                return true;
            }
            case CRYING -> {
                Vec3 dir = Vec3.directionFromRotation(0.0F, random.nextFloat() * 360.0F);
                play(player, WakeRegistry.CRYING.get(), player.position().add(dir.scale(26.0 + random.nextInt(14))), 2.2F * v, 0.9F);
                return true;
            }
            case CHAINS -> {
                Vec3 at = caveSpot(level, player, random);
                if (at == null) {
                    at = behind(player, 10.0);
                }
                play(player, WakeRegistry.CHAINS.get(), at, 1.2F * v, 0.8F + random.nextFloat() * 0.2F);
                return true;
            }
            case TELEVISION -> {
                BlockPos vcr = television(level, player);
                if (vcr == null) {
                    return false;
                }
                // The machine comes on by itself, plays a few seconds of nothing, and stops.
                play(player, WakeRegistry.CLICK.get(), Vec3.atCenterOf(vcr), 0.8F, 1.0F);
                play(player, WakeRegistry.RADIO.get(), Vec3.atCenterOf(vcr), 0.7F * v, 1.0F);
                Director.schedule(70 + random.nextInt(50), () -> play(player, WakeRegistry.TAPE_STOP.get(), Vec3.atCenterOf(vcr), 0.8F, 1.0F));
                return true;
            }
            case SHUTTER -> {
                Vec3 at = behind(player, 2.5);
                play(player, WakeRegistry.CAMERA_FLASH.get(), at, 0.5F * v, 1.0F);
                WakeNetwork.send(player, new WakeFxPayload(WakeFxPayload.SHUTTER, at.x, at.y, at.z, 0.35F, 0.0F));
                return true;
            }
        }
        return false;
    }

    /** Your own footsteps, on the blocks you walked on, along the way you walked yesterday. */
    private static boolean yesterday(ServerPlayer player, HauntState state, ServerLevel level, RandomSource random) {
        List<BlockPos> route = state.yesterday().points();
        int start = -1;
        double best = 24.0 * 24.0;
        for (int i = 0; i < route.size(); i++) {
            double d = route.get(i).distSqr(player.blockPosition());
            if (d < best && d > 36.0 && Appearances.angleFromView(player, Vec3.atCenterOf(route.get(i))) > 70.0F) {
                best = d;
                start = i;
            }
        }
        if (start < 0) {
            return false;
        }
        int count = Math.min(10, route.size() - start);
        for (int i = 0; i < count; i++) {
            BlockPos p = route.get(start + i);
            Director.schedule(i * 9, () -> {
                BlockState under = level.getBlockState(p.below());
                play(player, under.getSoundType().getStepSound(), Vec3.atBottomCenterOf(p), 0.55F, 1.0F);
            });
        }
        return count >= 3;
    }

    private static void play(ServerPlayer player, SoundEvent sound, Vec3 at, float volume, float pitch) {
        Lure.set(player, at);
        if (!player.hasDisconnected()) {
            Household.send(player, sound, SoundSource.AMBIENT, at, volume, pitch);
        }
    }

    // ---- where -----------------------------------------------------------------------------

    private static Vec3 behind(ServerPlayer player, double distance) {
        Vec3 look = player.getViewVector(1.0F).multiply(1.0, 0.0, 1.0);
        if (look.lengthSqr() < 1.0E-4) {
            look = new Vec3(0, 0, 1);
        }
        return player.position().subtract(look.normalize().scale(distance)).add(0.0, 0.1, 0.0);
    }

    /** The block above your head, two to five up: you are under a roof. */
    @Nullable
    private static BlockPos roof(ServerLevel level, ServerPlayer player) {
        BlockPos head = player.blockPosition().above();
        for (int up = 1; up <= 5; up++) {
            BlockPos p = head.above(up);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                return level.canSeeSky(p.above()) ? p : null;
            }
        }
        return null;
    }

    /** The far side of the wall in front of you, if one is close. */
    @Nullable
    private static Vec3 facingWall(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(2.8)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || Math.abs(look.y) > 0.6) {
            return null;
        }
        return hit.getLocation().add(look.scale(1.2));
    }

    /** A point just outside one of the walls round you, at about head height. */
    @Nullable
    private static Vec3 outsideWall(ServerLevel level, ServerPlayer player, RandomSource random) {
        Vec3 eye = player.getEyePosition().add(0.0, -0.3, 0.0);
        Vec3 look = player.getViewVector(1.0F);
        for (int i = 0; i < 8; i++) {
            Direction d = Direction.from2DDataValue(random.nextInt(4));
            Vec3 dir = Vec3.atLowerCornerOf(d.getNormal());
            // Never the wall you are looking straight at: you would be waiting for it.
            if (dir.dot(look.multiply(1, 0, 1).normalize()) > 0.7) {
                continue;
            }
            BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(7.0)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(eye) > 1.2) {
                return hit.getLocation().add(dir.scale(0.6));
            }
        }
        return null;
    }

    /** An open place in the cave, out of sight, further in. */
    @Nullable
    private static Vec3 caveSpot(ServerLevel level, ServerPlayer player, RandomSource random) {
        for (int i = 0; i < 16; i++) {
            Vec3 dir = Vec3.directionFromRotation(random.nextFloat() * 30.0F - 15.0F, random.nextFloat() * 360.0F);
            Vec3 at = player.getEyePosition().add(dir.scale(9.0 + random.nextInt(8)));
            BlockPos p = BlockPos.containing(at);
            if (level.isLoaded(p) && level.getBlockState(p).isAir() && !Spots.visibleFrom(player, p.below())) {
                return at;
            }
        }
        return null;
    }

    /** A closed wooden door in the house, not too near, out of your sight. */
    @Nullable
    private static BlockPos door(ServerLevel level, ServerPlayer player) {
        BlockPos center = player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-12, -3, -12), center.offset(12, 3, 12))) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(s) && !s.getValue(DoorBlock.OPEN)
                    && s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                    && p.distSqr(center) > 16.0 && Appearances.angleFromView(player, Vec3.atCenterOf(p)) > 75.0F) {
                return p.immutable();
            }
        }
        return null;
    }

    /** A video recorder nearby: it has one of its own in every room it builds. */
    @Nullable
    private static BlockPos television(ServerLevel level, ServerPlayer player) {
        BlockPos center = player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-10, -3, -10), center.offset(10, 3, 10))) {
            if (level.getBlockState(p).getBlock() instanceof io.github.jitbyjones.thewake.block.VcrBlock && p.distSqr(center) > 9.0) {
                return p.immutable();
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos container(ServerLevel level, ServerPlayer player) {
        BlockPos center = player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-9, -2, -9), center.offset(9, 2, 9))) {
            Block b = level.getBlockState(p).getBlock();
            if ((b instanceof ChestBlock || b instanceof BarrelBlock) && p.distSqr(center) > 9.0
                    && Appearances.angleFromView(player, Vec3.atCenterOf(p)) > 80.0F) {
                return p.immutable();
            }
        }
        return null;
    }

    /** Lost time on the VHS counter, after a lapse long enough to matter. */
    public static void lostTime(ServerPlayer player, HauntState state, int seconds) {
        if (seconds >= 15 && state.peakStage().atLeast(Stage.SIGHTINGS) && !Temper.removed(state)) {
            WakeNetwork.send(player, WakeFxPayload.of(WakeFxPayload.LOST_TIME, seconds, 0.0F));
        }
    }

    /** Unused: clamps a volume for callers that compute their own. */
    static float clampVolume(float v) {
        return Mth.clamp(v, 0.0F, 8.0F);
    }
}
