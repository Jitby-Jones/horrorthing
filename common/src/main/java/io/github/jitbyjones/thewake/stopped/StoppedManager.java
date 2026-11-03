package io.github.jitbyjones.thewake.stopped;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.haunt.Advancements;
import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.haunt.Ledger;
import io.github.jitbyjones.thewake.haunt.Spots;
import io.github.jitbyjones.thewake.haunt.Stage;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.StoppedPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The finale. You wake in the night it lives in: nothing moves but you and it. It is in your bed.
 * When it gets up it walks every place you have ever walked, drawn as ribbons of night, and the
 * new trail you lay as you run. Sleep somewhere far from your old paths before it reaches you.
 */
public final class StoppedManager {
    /** A bed counts as somewhere new only this far from every old ribbon. */
    public static final double ESCAPE_DISTANCE = 16.0;
    private static final int CATCH_LIMIT = 3;

    private static final Map<UUID, StoppedSession> SESSIONS = new HashMap<>();

    private StoppedManager() {}

    public static boolean isIn(Player player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /** Mobs leave players in the Stopped alone (only matters on servers, where the world keeps running). */
    public static boolean isProtected(@Nullable LivingEntity target) {
        return target instanceof Player p && SESSIONS.containsKey(p.getUUID());
    }

    /** Full clarity, the head turn already seen, and allowed here. */
    public static boolean ready(ServerPlayer player, HauntState state) {
        return state.clarity() >= Stage.STOPPED.threshold && state.hasFlag("head_turn") && !state.ended()
                && Haunt.finaleAllowed(player) && player.level().dimension() == Level.OVERWORLD && Haunt.eligible(player);
    }

    public static boolean shouldBeginOnLogin(ServerPlayer player, HauntState state) {
        return ready(player, state) && state.bed() != null;
    }

    // ---- beginning ------------------------------------------------------------------------

    /** The sleep path: called during the night skip, while the player is still in bed. */
    public static void begin(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, Direction facing, long dayTimeBefore) {
        if (isIn(player)) {
            return;
        }
        start(player, state, level, head, facing, dayTimeBefore);
    }

    /** The login path: it is waiting when you come back. */
    public static void beginOnLogin(ServerPlayer player, HauntState state) {
        BlockPos bed = state.bed();
        ServerLevel level = player.serverLevel();
        if (bed == null || isIn(player) || !ready(player, state) || !level.isLoaded(bed) || !bed.closerThan(player.blockPosition(), 128.0)) {
            return;
        }
        BlockPos head = Spots.bedHead(level, bed);
        Direction facing = head == null ? null : Spots.bedFacing(level, head);
        if (head == null || facing == null) {
            return;
        }
        long night = (level.getDayTime() / 24000L) * 24000L + 18000L;
        start(player, state, level, head, facing, night);
    }

    private static void start(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, Direction facing, long frozenDayTime) {
        MinecraftServer server = player.server;
        boolean singleplayer = server.isSingleplayer() && !server.isPublished();
        boolean wasFrozen = server.tickRateManager().isFrozen();
        StoppedSession session = new StoppedSession(player, level, head, facing, frozenDayTime, singleplayer, wasFrozen,
                state.crumbs(), state.caughtCount());
        SESSIONS.put(player.getUUID(), session);

        Phantoms.removeIf(player, p -> true);
        player.stopRiding();
        player.setInvulnerable(true);
        if (singleplayer) {
            // The world as it is when time does not pass. Vanilla's own freeze; players still move.
            level.setDayTime(frozenDayTime);
            server.tickRateManager().setFrozen(true);
        } else {
            for (Mob mob : level.getEntitiesOfClass(Mob.class, new AABB(player.blockPosition()).inflate(48.0), m -> m.getTarget() == player)) {
                mob.setTarget(null);
            }
        }

        // It is in your bed.
        Vec3 pillow = Vec3.atBottomCenterOf(head).add(0.0, 0.6875, 0.0);
        session.wake = Phantoms.create(player, pillow, facing.toYRot(), Phantom.Mode.SLEEPER, Phantom.Stance.LIE)
                .setBedDirection(facing.get2DDataValue()).setOpacity(1.0F).setSmear(0.0F).setFocus(1.0F)
                .setTouch(0.0, false).setLapseGeneration(-1);

        LongArrayList positions = new LongArrayList();
        ByteArrayList wear = new ByteArrayList();
        state.crumbs().export(positions, wear);
        WakeNetwork.send(player, new StoppedPayload(true, frozenDayTime, positions.toLongArray(), wear.toByteArray(),
                StoppedPayload.OUTCOME_NONE));
        TheWake.LOG.debug("{} entered the Stopped", player.getGameProfile().getName());
    }

    // ---- ticking --------------------------------------------------------------------------

    public static void tickServer(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        for (StoppedSession session : new ArrayList<>(SESSIONS.values())) {
            try {
                tickSession(session);
            } catch (RuntimeException e) {
                // Never let the finale take the server down with it: let the player go.
                TheWake.LOG.error("The Wake: the Stopped failed for {}; releasing them", session.player.getGameProfile().getName(), e);
                finish(session);
                if (!session.player.hasDisconnected()) {
                    WakeNetwork.send(session.player, StoppedPayload.leave(StoppedPayload.OUTCOME_NONE));
                }
            }
        }
    }

    private static void tickSession(StoppedSession session) {
        {
            ServerPlayer player = session.player;
            if (player.hasDisconnected() || player.isRemoved()) {
                return;
            }
            if (player.level() != session.level) {
                caught(session, false);
                return;
            }
            if (session.singleplayer && session.level.getDayTime() != session.frozenDayTime) {
                session.level.setDayTime(session.frozenDayTime);
            }
            if (session.escapeBed != null) {
                tickEscape(session);
            }
            if (session.phase == StoppedSession.Phase.DONE) {
                return;
            }
            if (session.caught || session.tick()) {
                caught(session, true);
            }
        }
    }

    private static void tickEscape(StoppedSession session) {
        ServerPlayer player = session.player;
        if (session.singleplayer) {
            if (!player.isSleeping()) {
                // Got up again. Still here.
                session.escapeBed = null;
                session.escapeTicks = 0;
                return;
            }
            if (player.getSleepTimer() >= 100) {
                escaped(session);
            }
        } else {
            session.escapeTicks++;
            Vec3 lie = Vec3.atBottomCenterOf(session.escapeBed).add(0, 0.6, 0);
            if (player.position().distanceToSqr(lie) > 1.0) {
                player.connection.teleport(lie.x, lie.y, lie.z, player.getYRot(), player.getXRot());
            }
            if (session.escapeTicks >= 100) {
                escaped(session);
            }
        }
    }

    // ---- interactions ---------------------------------------------------------------------

    /** Right-clicking a block inside the Stopped. Returns true if the interaction was consumed. */
    public static boolean onUseBlock(ServerPlayer player, BlockPos pos) {
        StoppedSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return false;
        }
        BlockState state = player.level().getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return false;
        }
        BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? pos : pos.relative(state.getValue(BedBlock.FACING));
        if (session.phase == StoppedSession.Phase.IN_BED && head.equals(session.oldBed)) {
            player.displayClientMessage(Component.translatable("block.minecraft.bed.occupied"), true);
            return true;
        }
        if (session.escapeBed != null) {
            return true;
        }
        double fromOldBed = Math.sqrt(Math.pow(head.getX() - session.oldBed.getX(), 2) + Math.pow(head.getZ() - session.oldBed.getZ(), 2));
        if (session.distanceToOldRibbon(head, ESCAPE_DISTANCE + 1.0) < ESCAPE_DISTANCE || fromOldBed < ESCAPE_DISTANCE) {
            // The game's own monster check found it. And it heard you.
            player.displayClientMessage(Component.translatable("block.minecraft.bed.not_safe"), true);
            session.alert(head);
            return true;
        }
        if (!player.blockPosition().closerThan(head, 3.0)) {
            player.displayClientMessage(Component.translatable("block.minecraft.bed.too_far_away"), true);
            return true;
        }
        session.escapeBed = head.immutable();
        session.escapeTicks = 0;
        if (session.singleplayer) {
            // The night is restored and frozen, so vanilla sleep works: the real lying-down view and fade.
            player.startSleeping(head);
        } else {
            WakeNetwork.send(player, FxPayload.of(FxPayload.FALL_ASLEEP, 100));
        }
        return true;
    }

    /**
     * Breaking a block inside the Stopped. Item entities are frozen here (their pickup delay never
     * runs out), so a broken bed goes straight into the inventory. Returns true to cancel vanilla.
     */
    public static boolean onBreakBlock(ServerPlayer player, BlockPos pos, BlockState state) {
        StoppedSession session = SESSIONS.get(player.getUUID());
        if (session == null || !(state.getBlock() instanceof BedBlock)) {
            return false;
        }
        Level level = player.level();
        BlockPos other = state.getValue(BedBlock.PART) == BedPart.HEAD
                ? pos.relative(state.getValue(BedBlock.FACING).getOpposite())
                : pos.relative(state.getValue(BedBlock.FACING));
        BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? pos : other;
        level.setBlock(other, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
        level.levelEvent(2001, pos, Block.getId(state));
        if (!player.isCreative()) {
            ItemStack bed = new ItemStack(state.getBlock().asItem());
            if (!player.getInventory().add(bed)) {
                player.drop(bed, false);
            }
        }
        if (head.equals(session.oldBed) && session.phase == StoppedSession.Phase.IN_BED) {
            session.getUp();
        }
        return true;
    }

    public static boolean onAttack(ServerPlayer player) {
        return isIn(player);
    }

    /** Pausing (singleplayer) moves it forward by the real time that passed. */
    public static void onPaused(ServerPlayer player, int millis) {
        StoppedSession session = SESSIONS.get(player.getUUID());
        if (session != null) {
            session.advance(millis / 50);
        }
    }

    /** The vanilla skip fired for someone in the Stopped (singleplayer escape sleep). */
    public static void onVanillaSkip(ServerPlayer player) {
        StoppedSession session = SESSIONS.get(player.getUUID());
        if (session != null && session.escapeBed != null) {
            escaped(session);
        }
    }

    /** Quitting inside counts as being caught. */
    public static void onLogout(ServerPlayer player) {
        StoppedSession session = SESSIONS.get(player.getUUID());
        if (session != null) {
            caught(session, false);
        }
    }

    /** Dying inside counts as being caught. */
    public static void onDeath(ServerPlayer player) {
        StoppedSession session = SESSIONS.get(player.getUUID());
        if (session != null) {
            caught(session, false);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        for (StoppedSession session : new ArrayList<>(SESSIONS.values())) {
            finish(session);
        }
        SESSIONS.clear();
    }

    // ---- endings --------------------------------------------------------------------------

    /** Somewhere new, asleep before it arrived. */
    private static void escaped(StoppedSession session) {
        if (session.phase == StoppedSession.Phase.DONE) {
            return;
        }
        session.phase = StoppedSession.Phase.DONE;
        ServerPlayer player = session.player;
        HauntState state = Haunt.state(player);
        BlockPos newBed = session.escapeBed;
        try {
            finish(session);
            if (session.singleplayer) {
                session.level.setDayTime((session.frozenDayTime / 24000L + 1L) * 24000L);
            }
            if (player.isSleeping()) {
                player.stopSleepInBed(false, true);
            }
            if (newBed != null) {
                player.setRespawnPosition(session.level.dimension(), newBed, player.getYRot(), false, true);
            }
            Advancements.award(player, "somewhere_new");
        } catch (RuntimeException e) {
            TheWake.LOG.error("The Wake: error while ending the Stopped (escaped)", e);
        } finally {
            state.setOldBed(session.oldBed);
            state.setKeptBed(null, session.facing.get2DDataValue());
            state.setEnded(true, false);
            state.forget();
            state.lapse();
            WakeNetwork.send(player, StoppedPayload.leave(StoppedPayload.OUTCOME_ESCAPED));
        }
    }

    /** It reached you. Laid down in your bed; the next day is lived for you. The third time, it keeps the bed. */
    private static void caught(StoppedSession session, boolean present) {
        if (session.phase == StoppedSession.Phase.DONE) {
            SESSIONS.remove(session.player.getUUID());
            return;
        }
        session.phase = StoppedSession.Phase.DONE;
        ServerPlayer player = session.player;
        HauntState state = Haunt.state(player);
        state.incrementCaught();
        boolean kept = state.caughtCount() >= CATCH_LIMIT;
        ServerLevel level = session.level;

        if (present && level.getBlockState(session.oldBed).getBlock() instanceof BedBlock && player.level() == level) {
            if (player.isSleeping()) {
                player.stopSleepInBed(false, false);
            }
            // Laid down in your own bed. Then the fade.
            player.startSleeping(session.oldBed);
        }
        WakeNetwork.send(player, FxPayload.of(FxPayload.BLACKOUT, 70));
        int delay = present ? 50 : 0;
        Runnable end = () -> {
            try {
                finish(session);
                if (player.isSleeping()) {
                    player.stopSleepInBed(true, false);
                }
                if (session.singleplayer) {
                    // It lived the next day for you. You wake at its end.
                    level.setDayTime((session.frozenDayTime / 24000L + 1L) * 24000L + 12000L);
                }
                if (player.isAlive() && player.level() == level && level.getBlockState(session.oldBed).getBlock() instanceof BedBlock) {
                    BedBlock.findStandUpPosition(player.getType(), level, session.oldBed, session.facing, player.getYRot())
                            .ifPresent(v -> player.teleportTo(v.x, v.y, v.z));
                }
                player.getFoodData().setFoodLevel(Math.max(4, player.getFoodData().getFoodLevel() - 8));
                Ledger.leaveStolenDay(player, state, level, session.oldBed);
            } catch (RuntimeException e) {
                TheWake.LOG.error("The Wake: error while ending the Stopped (caught)", e);
            } finally {
                // Whatever failed above, nobody stays trapped in the Stopped.
                state.lapse();
                if (kept) {
                    state.setKeptBed(session.oldBed, session.facing.get2DDataValue());
                    state.setEnded(true, true);
                }
                if (!player.hasDisconnected()) {
                    WakeNetwork.send(player, StoppedPayload.leave(kept ? StoppedPayload.OUTCOME_KEPT : StoppedPayload.OUTCOME_CAUGHT));
                }
            }
        };
        if (delay > 0 && !player.hasDisconnected()) {
            io.github.jitbyjones.thewake.haunt.Director.schedule(delay, end);
        } else {
            end.run();
        }
    }

    /** Restores everything the session changed. Safe to call more than once. */
    private static void finish(StoppedSession session) {
        SESSIONS.remove(session.player.getUUID());
        ServerPlayer player = session.player;
        if (session.singleplayer && !session.wasFrozen) {
            player.server.tickRateManager().setFrozen(false);
        }
        if (!session.wasInvulnerable && !player.isCreative() && !player.isSpectator()) {
            player.setInvulnerable(false);
        }
        if (session.wake != null) {
            Phantoms.remove(player, session.wake);
        }
    }

    // ---- afterward ------------------------------------------------------------------------

    /** The other ending: it keeps your bed. It lies there, for good, for you to see. */
    public static void keepBedVisible(ServerPlayer player, HauntState state, ServerLevel level) {
        BlockPos bed = state.keptBed();
        if (bed == null || player.level().dimension() != Level.OVERWORLD) {
            return;
        }
        boolean near = bed.closerThan(player.blockPosition(), 48.0);
        Phantom existing = Phantoms.nearest(player, 64.0, p -> p.mode() == Phantom.Mode.SLEEPER);
        if (near && existing == null) {
            Direction facing = Direction.from2DDataValue(Math.max(0, state.keptBedDirection()));
            Vec3 pillow = Vec3.atBottomCenterOf(bed).add(0.0, 0.6875, 0.0);
            Phantoms.create(player, pillow, facing.toYRot(), Phantom.Mode.SLEEPER, Phantom.Stance.LIE)
                    .setBedDirection(facing.get2DDataValue()).setOpacity(1.0F).setSmear(0.0F).setFocus(1.0F)
                    .setTouch(0.0, false).setLapseGeneration(-1).setFace(0.85F);
        } else if (!near && existing != null) {
            Phantoms.remove(player, existing);
        }
    }

    /** It keeps the bed: nobody sleeps in it again. */
    public static boolean isKeptBed(ServerPlayer player, BlockPos head) {
        HauntState state = Haunt.state(player);
        return state.endingKept() && state.keptBed() != null && state.keptBed().equals(head);
    }

    /** After escaping: sleeping at the old spot again starts it over from halfway. */
    public static void reawaken(ServerPlayer player, HauntState state) {
        state.setEnded(false, false);
        state.clearFlag("head_turn");
        state.forcePeak(Stage.ROUTINE);
        state.setClarity(0.5F);
        state.setLikenessDay(-1L);
    }
}
