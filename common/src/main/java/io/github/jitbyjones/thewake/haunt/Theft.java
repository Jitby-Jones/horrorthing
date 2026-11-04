package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.phantom.Phantom;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Act IV. Rule 1 breaks: it no longer waits for you to lose time; it takes it back. Nights you kept
 * from it are collected later, in daylight, as blinks. Some mornings you wake where it went.
 */
final class Theft {
    private static final int WARNING_TICKS = 60;

    private Theft() {}

    /** Daylight debt collection. Only ever as many blinks as nights you kept. */
    static void tick(ServerPlayer player, HauntState state, ServerLevel level, long gameTime) {
        if (gameTime % 20L != 0L || state.debt() <= 0 || !WakeConfig.get().blinkEvents || level.getLevelData().isHardcore()) {
            return;
        }
        if (!state.peakStage().atLeast(Stage.HUNT) || Haunt.isNight(level) || Beats.hush(state, level)) {
            return;
        }
        if (!state.ready("blink", gameTime) || player.getRandom().nextFloat() > 1.0F / 240.0F || !safeMoment(player)) {
            return;
        }
        BlockPos target = alongYourRoute(player, state, level);
        if (target == null) {
            return;
        }
        state.cooldown("blink", gameTime, 24000L);
        // The clock in your hand stops. Then the time is gone.
        WakeNetwork.send(player, FxPayload.of(FxPayload.CLOCK_STOP, WARNING_TICKS + 40));
        Director.schedule(WARNING_TICKS, () -> {
            if (!player.hasDisconnected() && Haunt.eligible(player) && safeMoment(player)) {
                blink(player, state, level, target);
            }
        });
    }

    static boolean safeMoment(ServerPlayer player) {
        return player.onGround() && !player.isInLava() && !player.isInWater() && player.hurtTime == 0
                && player.getLastHurtByMob() == null && !player.isSleeping() && player.containerMenu == player.inventoryMenu
                && player.fallDistance < 1.0F && !player.isPassenger() && !player.isFallFlying();
    }

    private static void blink(ServerPlayer player, HauntState state, ServerLevel level, BlockPos target) {
        Vec3 before = player.position();
        WakeNetwork.send(player, FxPayload.of(FxPayload.BLACKOUT, 40));
        Director.schedule(6, () -> {
            if (player.hasDisconnected() || !isSafe(level, target)) {
                return;
            }
            state.lapse();
            state.payDebt();
            Haunt.gain(player, state, 0.015F);
            Vec3 to = Vec3.atBottomCenterOf(target);
            float yaw = Spots.yawTowards(before, to);
            player.connection.teleport(to.x, to.y, to.z, yaw, 8.0F);
            player.getFoodData().addExhaustion(6.0F);
            int slot = player.getInventory().selected;
            player.getInventory().selected = (slot + 1 + player.getRandom().nextInt(8)) % 9;
            player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
            player.awardStat(Stats.WALK_ONE_CM, (int) (before.distanceTo(to) * 100.0));
            if (player.server.isSingleplayer() && level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
                level.setDayTime(level.getDayTime() + 1000L);
            }
            // Where you were standing is still occupied.
            BlockPos where = BlockPos.containing(before);
            if (Spots.standable(level, where)) {
                Hunter.adopt(player, Appearances.still(player, where, Spots.yawTowards(before, to), CreaturePose.STAND));
            }
        });
    }

    /**
     * Some mornings you do not wake in bed. You wake where it went: the far end of a route you walk,
     * somewhere dark, facing the stone.
     */
    @Nullable
    static BlockPos stolenMorningSpot(ServerPlayer player, HauntState state, ServerLevel level, BlockPos bed) {
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        RandomSource random = player.getRandom();
        Breadcrumbs crumbs = state.crumbs();
        for (int i = 0; i < 160 && crumbs.size() > 30; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double r = 18.0 + random.nextDouble() * 46.0;
            BlockPos crumb = crumbs.nearest(Vec3.atCenterOf(bed).add(Math.cos(angle) * r, -random.nextInt(40), Math.sin(angle) * r), 6.0);
            if (crumb == null || !level.isLoaded(crumb) || !isSafe(level, crumb)) {
                continue;
            }
            if (level.canSeeSky(crumb) || level.getBrightness(LightLayer.BLOCK, crumb) > 7) {
                continue;
            }
            int score = enclosure(level, crumb) * 10 + (int) Math.sqrt(crumb.distSqr(bed)) - crumbs.neighbours(crumb, 1.9).size() * 3;
            if (score > bestScore) {
                bestScore = score;
                best = crumb;
            }
        }
        return best;
    }

    static void wakeAt(ServerPlayer player, ServerLevel level, BlockPos bed, BlockPos where) {
        if (player.isSleeping() || !isSafe(level, where)) {
            return;
        }
        Direction wall = facingWall(level, where);
        Vec3 to = Vec3.atBottomCenterOf(where);
        float yaw = wall != null ? wall.toYRot() : player.getYRot();
        player.connection.teleport(to.x, to.y, to.z, yaw, 0.0F);
        player.getFoodData().addExhaustion(10.0F);
        player.awardStat(Stats.WALK_ONE_CM, (int) (Math.sqrt(where.distSqr(bed)) * 100.0));
    }

    private static boolean isSafe(ServerLevel level, BlockPos pos) {
        if (!Spots.standable(level, pos)) {
            return false;
        }
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
            var state = level.getBlockState(p);
            if (state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)) {
                return false;
            }
        }
        List<Monster> monsters = level.getEntitiesOfClass(Monster.class, new AABB(pos).inflate(14.0));
        return monsters.isEmpty();
    }

    private static int enclosure(ServerLevel level, BlockPos pos) {
        int solid = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!Spots.isPassable(level, pos.relative(d))) {
                solid++;
            }
        }
        return solid;
    }

    @Nullable
    private static Direction facingWall(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!Spots.isPassable(level, pos.relative(d).above())) {
                return d;
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos alongYourRoute(ServerPlayer player, HauntState state, ServerLevel level) {
        RandomSource random = player.getRandom();
        Breadcrumbs crumbs = state.crumbs();
        if (crumbs.size() < 20) {
            return null;
        }
        for (int i = 0; i < 40; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double r = 8.0 + random.nextDouble() * 12.0;
            BlockPos crumb = crumbs.nearest(player.position().add(Math.cos(angle) * r, 0.0, Math.sin(angle) * r), 4.0);
            if (crumb != null && !crumb.closerThan(player.blockPosition(), 6.0) && isSafe(level, crumb)) {
                return crumb;
            }
        }
        return null;
    }

    /**
     * Calm words, in the game's own text: the thing in your hand has been renamed. It never takes
     * anything, never overwrites a name you gave, and only does this a handful of times.
     */
    static void caption(ServerPlayer player, HauntState state, String line) {
        if (!WakeConfig.get().worldEdits || state.captions() >= 6) {
            return;
        }
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || held.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
            return;
        }
        held.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(line));
        state.addCaption();
    }
}
