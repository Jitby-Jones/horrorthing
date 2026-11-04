package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.entity.WakeBrain;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import io.github.jitbyjones.thewake.net.WakeFxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import io.github.jitbyjones.thewake.stopped.StoppedManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * When its body comes out, and what happens after. Most of the haunting is images that only move
 * while you are not watching; this is for the times it has had enough of watching. It comes when
 * three things are true at once: it is angry, you are afraid (the director's tension), and it is
 * dark. Never two chases close together; after one, a long quiet.
 */
public final class Pursuit {
    public enum Outcome {
        /** You outran it, or outlasted it. */
        ESCAPED,
        /** You hid and it did not find you. */
        LOST,
        /** It got you. */
        CAUGHT,
        /** You blinded it and it went. */
        STUNNED_AWAY,
        /** It gave up, or left on its own. */
        GAVE_UP,
        /** The card came off. */
        REMOVED
    }

    private static final Map<UUID, WakeEntity> BODIES = new HashMap<>();

    private Pursuit() {}

    @Nullable
    public static WakeEntity body(ServerPlayer player) {
        WakeEntity body = BODIES.get(player.getUUID());
        if (body != null && (body.isRemoved() || body.level() != player.level())) {
            BODIES.remove(player.getUUID());
            return null;
        }
        return body;
    }

    public static boolean active(ServerPlayer player) {
        return body(player) != null;
    }

    public static void onServerStopped() {
        BODIES.clear();
    }

    /** Puts its body in the world at {@code at}, for {@code why}. */
    @Nullable
    public static WakeEntity spawn(ServerPlayer owner, Vec3 at, WakeBrain.Purpose why) {
        WakeEntity old = body(owner);
        if (old != null) {
            old.discard();
        }
        ServerLevel level = owner.serverLevel();
        WakeEntity body = WakeContent.WAKE.get().create(level);
        if (body == null) {
            return null;
        }
        float yaw = Spots.yawTowards(at, owner.position());
        body.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        body.yBodyRot = yaw;
        body.yHeadRot = yaw;
        body.setOwner(owner);
        WakeBrain brain = body.brain(owner);
        if (!level.addFreshEntity(body)) {
            return null;
        }
        BODIES.put(owner.getUUID(), body);
        Hunter.release(owner, true);
        brain.start(owner, why);
        TheWake.LOG.debug("[{}] its body is out ({}) {} blocks away", owner.getGameProfile().getName(), why,
                String.format("%.1f", at.distanceTo(owner.position())));
        return body;
    }

    // ---- deciding -------------------------------------------------------------------------

    /** Whether it may come after you at all: the config, the act, where you are. */
    static boolean allowed(ServerPlayer player, HauntState state) {
        WakeConfig config = WakeConfig.get();
        return config.chases && Haunt.awake(state) && Haunt.eligible(player) && !Temper.removed(state) && !StoppedManager.isIn(player)
                && state.peakStage().atLeast(Stage.SIGHTINGS) && !Beats.hush(state, player.serverLevel()) && !LastNight.active(player);
    }

    /** Whether, given the chance, it would rather run at you than keep watching. */
    public static boolean wantsChase(ServerPlayer player, HauntState state, double distance) {
        if (!allowed(player, state)) {
            return false;
        }
        Reputation.Form form = Temper.form(state);
        boolean tagged = !state.ready("tagged", player.level().getGameTime());
        boolean angry = form.atLeast(Reputation.Form.ANGRY) || form.atLeast(Reputation.Form.UNEASY) && (state.stage().atLeast(Stage.HUNT) || tagged);
        return angry && state.tension >= (form == Reputation.Form.FURIOUS ? 0.3F : 0.55F);
    }

    /** Every second: the director's chance to send its body. */
    static void tick(ServerPlayer player, HauntState state, long gameTime) {
        afterRespawn(player, state);
        if (body(player) != null) {
            return;
        }
        if (Strings.tick(player, state, gameTime, Temper.form(state), allowed(player, state) && !Hunter.hunting(player))) {
            return;
        }
        if (state.chasing) {
            state.chasing = false;
            state.syncDirty = true;
        }
        if (state.reputation().chasePending() && allowed(player, state) && player.isAlive() && player.tickCount > 40) {
            // You left in the middle of it. It waited.
            Temper.setChasePending(state, false);
            Vec3 at = near(player, 10.0, 16.0, true);
            if (at != null) {
                spawn(player, at, WakeBrain.Purpose.CHASE);
            }
            return;
        }
        if (gameTime % 20L != 0L || !allowed(player, state) || Hunter.hunting(player) || player.isPassenger() && !(player.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat)) {
            return;
        }
        // You went to see what that was.
        if (Lure.tick(player, state, gameTime)) {
            return;
        }
        // It is standing on its mark.
        if (Marks.tick(player, state, gameTime, Temper.form(state))) {
            return;
        }
        ShedTape.tick(player, state, gameTime, Temper.form(state));
        ServerLevel level = player.serverLevel();
        Reputation.Form form = Temper.form(state);
        if (!form.atLeast(Reputation.Form.UNEASY) || !dark(player)) {
            return;
        }
        long since = gameTime - state.reputation().lastChaseTick();
        long sinceScare = gameTime - state.lastScareTick;
        RandomSource random = player.getRandom();
        // The watchtower: if you are up on something you built, it climbs up behind you.
        if (onLookout(player) && state.tension >= 0.3F && sinceScare > 20 * 150 && random.nextFloat() < 0.04F) {
            Vec3 base = towerBase(player);
            if (base != null) {
                spawn(player, base, WakeBrain.Purpose.LURK);
                state.lastScareTick = gameTime;
                return;
            }
        }
        // How afraid you have to be, and how long since the last one, by its temper.
        float need = switch (form) {
            case FURIOUS -> 0.5F;
            case ANGRY -> 0.62F;
            default -> 0.72F;
        };
        long cooldown = switch (form) {
            case FURIOUS -> 20L * 90;
            case ANGRY -> 20L * 60 * 4;
            default -> 20L * 60 * 6;
        };
        long breath = form == Reputation.Form.FURIOUS ? 20L * 30 : 20L * 60;
        if (wantsChase(player, state, 0.0) && since > cooldown && sinceScare > breath && state.tension >= need
                && random.nextFloat() < 0.08F + (state.tension - need)) {
            boolean shut = io.github.jitbyjones.thewake.entity.Obstacles.enclosed(level, player.blockPosition(), 70);
            Vec3 at = shut ? near(player, 4.0, 10.0, true) : chaseStart(player, state);
            if (at != null) {
                spawn(player, at, shut ? WakeBrain.Purpose.SIEGE : WakeBrain.Purpose.CHASE);
                return;
            }
        }
        // Or it waits for you, round the next corner, behind the next door.
        if (Ambush.tick(player, state, gameTime, form, sinceScare)) {
            return;
        }
        // Otherwise it follows you. Furious, it hardly ever stops following you, and from closer.
        boolean furious = form == Reputation.Form.FURIOUS;
        float stalkNeed = furious ? 0.2F : form == Reputation.Form.ANGRY ? 0.32F : 0.42F;
        long stalkGap = furious ? 20L * 40 : form == Reputation.Form.ANGRY ? 20L * 75 : 20L * 90;
        float stalkChance = furious ? 0.06F + state.tension * 0.05F : form == Reputation.Form.ANGRY ? 0.03F + state.tension * 0.03F
                : 0.02F + state.tension * 0.02F;
        if (state.tension >= stalkNeed && sinceScare > stalkGap && since > stalkGap && random.nextFloat() < stalkChance) {
            Vec3 at = furious ? near(player, 12.0, 18.0, true) : near(player, 20.0, 28.0, true);
            if (at != null) {
                spawn(player, at, WakeBrain.Purpose.STALK);
                state.lastScareTick = gameTime;
            }
        }
    }

    /**
     * Where a chase starts: out of sight, in the dark, twenty-odd blocks off. If it has learned that
     * you run, ahead of you; if you got away last time by hiding somewhere, near there.
     */
    @Nullable
    private static Vec3 chaseStart(ServerPlayer player, HauntState state) {
        if (Temper.knows(state, Reputation.Tactic.OUTRUN, 2)) {
            Vec3 motion = player.getDeltaMovement().multiply(1.0, 0.0, 1.0);
            if (motion.lengthSqr() > 0.004) {
                Vec3 ahead = player.position().add(motion.normalize().scale(22.0));
                BlockPos at = Spots.groundNear(player.serverLevel(), Mth.floor(ahead.x), Mth.floor(player.getY()), Mth.floor(ahead.z), 6);
                if (at != null && !Spots.visibleFrom(player, at)) {
                    return Vec3.atBottomCenterOf(at);
                }
            }
        }
        return near(player, 18.0, 26.0, true);
    }

    /** A dark standing place out of view, between {@code min} and {@code max} blocks from the player. */
    @Nullable
    static Vec3 near(ServerPlayer player, double min, double max, boolean hidden) {
        ServerLevel level = player.serverLevel();
        BlockPos spot = Spots.standableNear(level, player.blockPosition(), (int) min, (int) max, player.getRandom(),
                p -> (!hidden || !Spots.visibleFrom(player, p) || Appearances.angleFromView(player, Vec3.atCenterOf(p).add(0, 1.5, 0)) > 70.0F)
                        && Hunter.dark(level, p), 40);
        if (spot == null) {
            spot = Spots.standableNear(level, player.blockPosition(), (int) min, (int) max, player.getRandom(),
                    p -> Appearances.angleFromView(player, Vec3.atCenterOf(p).add(0, 1.5, 0)) > 60.0F, 40);
        }
        return spot == null ? null : Vec3.atBottomCenterOf(spot);
    }

    private static boolean dark(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (Haunt.isNight(level) && level.dimensionType().hasSkyLight()) {
            return true;
        }
        BlockPos head = player.blockPosition().above();
        int sky = level.getBrightness(LightLayer.SKY, head) - level.getSkyDarken();
        return sky <= 4 && level.getBrightness(LightLayer.BLOCK, head) <= 9 || WakeDimensions.isOurs(level);
    }

    // ---- what it does when it is out ------------------------------------------------------

    public static void chaseStarted(ServerPlayer owner, HauntState state, WakeEntity body) {
        Temper.chaseBegan(state, owner.level().getGameTime());
        state.chasing = true;
        state.syncDirty = true;
        state.lastScareTick = owner.level().getGameTime();
        WakeNetwork.send(owner, WakeFxPayload.of(WakeFxPayload.CHASE, 1.0F, 0.0F));
    }

    public static void ended(ServerPlayer owner, HauntState state, WakeEntity body, Outcome outcome) {
        long now = owner.level().getGameTime();
        boolean wasChase = state.chasing;
        state.chasing = false;
        state.syncDirty = true;
        state.lastScareTick = now;
        switch (outcome) {
            case ESCAPED, LOST -> {
                if (wasChase) {
                    Temper.escaped(state);
                    Temper.provoke(owner, state, Temper.Provocation.ESCAPED);
                }
                state.tension = Math.max(0.12F, afterFloor(state));
            }
            case CAUGHT -> {
                Temper.provoke(owner, state, Temper.Provocation.CAUGHT);
                Temper.caught(state, now);
                state.tension = Math.max(0.05F, afterFloor(state));
            }
            case STUNNED_AWAY -> state.tension = 0.2F;
            case GAVE_UP -> state.tension = Math.min(state.tension, 0.3F);
            case REMOVED -> state.tension = 0.0F;
        }
        WakeNetwork.send(owner, WakeFxPayload.of(WakeFxPayload.CHASE, 0.0F, 0.0F));
        vanish(owner, body);
        BODIES.remove(owner.getUUID());
        TheWake.LOG.debug("[{}] its body is gone: {}", owner.getGameProfile().getName(), outcome);
    }

    /** Gone in a burst of static, like the images. */
    private static void vanish(ServerPlayer owner, WakeEntity body) {
        Vec3 at = body.position().add(0.0, 1.6, 0.0);
        Household.send(owner, WakeRegistry.STATIC.get(), SoundSource.HOSTILE, at, 0.8F * WakeConfig.get().soundVolume, 0.9F);
        body.discard();
    }

    /**
     * How afraid you stay after one: the angrier it is, the less time it gives you before the next.
     * Furious, it hardly lets you get your breath back.
     */
    private static float afterFloor(HauntState state) {
        return switch (Temper.form(state)) {
            case FURIOUS -> 0.55F;
            case ANGRY -> 0.32F;
            default -> 0.0F;
        };
    }

    /** You died angry: when you are back, the VCR shows the tape is still playing. */
    public static void onDeath(ServerPlayer player) {
        HauntState state = Haunt.state(player);
        if (Haunt.awake(state) && !Temper.removed(state) && Temper.form(state).atLeast(Reputation.Form.ANGRY)) {
            state.setFlag("play_card");
        }
    }

    /** Every tick: back on your feet after dying angry, the VCR shows it is still playing. */
    static void afterRespawn(ServerPlayer player, HauntState state) {
        if (state.hasFlag("play_card") && player.isAlive() && player.tickCount > 20) {
            state.clearFlag("play_card");
            WakeNetwork.send(player, WakeFxPayload.of(WakeFxPayload.PLAY, 1.0F, 70.0F));
        }
    }

    /** Its owner logged off or went through a portal with it out. */
    public static void ownerGone(UUID ownerId, WakeEntity body, boolean chasing) {
        BODIES.remove(ownerId);
        if (body.level().getServer() == null) {
            return;
        }
        HauntState state = HauntData.get(body.level().getServer()).state(ownerId);
        state.chasing = false;
        if (chasing) {
            Temper.setChasePending(state, true);
            // Still here, somewhere else: a portal. Gone: they quit.
            boolean online = body.level().getServer().getPlayerList().getPlayer(ownerId) != null;
            state.reputation().count(online ? Reputation.Tactic.PORTAL : Reputation.Tactic.LOGOUT);
            Reputation r = state.reputation();
            r.setAnger(r.anger() + Temper.Provocation.CHEESE.amount);
        }
    }

    /** A dimension change with its body out: it will be where you come back. */
    public static void onChangedDimension(ServerPlayer player) {
        WakeEntity body = BODIES.get(player.getUUID());
        if (body != null && !body.isRemoved()) {
            HauntState state = Haunt.state(player);
            boolean chasing = state.chasing;
            BODIES.remove(player.getUUID());
            body.discard();
            state.chasing = false;
            if (chasing) {
                Temper.learn(player, state, Reputation.Tactic.PORTAL);
                Temper.provoke(player, state, Temper.Provocation.CHEESE);
                Temper.setChasePending(state, true);
            }
        }
    }

    /** The tape skips: it is somewhere else, all at once. */
    public static void tapeSkip(ServerPlayer owner, WakeEntity body, Vec3 to) {
        Household.send(owner, WakeRegistry.STATIC.get(), SoundSource.HOSTILE, body.position().add(0, 1.6, 0), 0.5F, 1.1F);
        body.getNavigation().stop();
        body.teleportTo(to.x, to.y, to.z);
        body.setYRot(Spots.yawTowards(to, owner.position()));
        body.yBodyRot = body.getYRot();
        Household.send(owner, WakeRegistry.STATIC.get(), SoundSource.HOSTILE, to.add(0, 1.6, 0), 0.6F, 0.8F);
        WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.TAPE_SKIP, to.x, to.y, to.z, 0.7F, 6.0F));
    }

    /** Somewhere near you it could stand and reach you from. */
    @Nullable
    public static Vec3 reachableNear(ServerPlayer owner, double min, double max) {
        ServerLevel level = owner.serverLevel();
        BlockPos spot = Spots.standableNear(level, owner.blockPosition(), (int) Math.floor(min), (int) Math.ceil(max), owner.getRandom(),
                p -> Math.abs(p.getY() - owner.getBlockY()) <= 1 && Appearances.angleFromView(owner, Vec3.atCenterOf(p).add(0, 1.0, 0)) > 50.0F, 40);
        if (spot == null) {
            spot = Spots.standableNear(level, owner.blockPosition(), (int) Math.floor(min), (int) Math.ceil(max), owner.getRandom(),
                    p -> Math.abs(p.getY() - owner.getBlockY()) <= 1, 40);
        }
        return spot == null ? null : Vec3.atBottomCenterOf(spot);
    }

    /** Behind you, about {@code distance} blocks back. */
    @Nullable
    public static Vec3 stalkPoint(ServerPlayer owner, double distance) {
        BlockPos p = Spots.behind(owner, Math.max(3.0, distance - 2.0), distance + 2.0, 10);
        return p == null ? null : Vec3.atBottomCenterOf(p);
    }

    // ---- high places ----------------------------------------------------------------------

    /**
     * Up on something narrow and high, built to watch from: standing well above the ground all
     * round, a few blocks out. A hilltop is not a lookout; a tower is.
     */
    public static boolean onLookout(ServerPlayer player) {
        return isolatedHeight(player, 3, 5, 6);
    }

    /** On a one-block pillar. */
    public static boolean onPillar(ServerPlayer player) {
        return isolatedHeight(player, 1, 4, 7);
    }

    private static boolean isolatedHeight(ServerPlayer player, int radius, int drop, int needed) {
        if (!player.onGround() || player.isInWater()) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        int feet = player.getBlockY();
        int low = 0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            int x = Mth.floor(player.getX() + Math.cos(a) * radius);
            int z = Mth.floor(player.getZ() + Math.sin(a) * radius);
            if (!level.isLoaded(new BlockPos(x, feet, z))) {
                return false;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (feet - ground >= drop) {
                low++;
            }
        }
        return low >= needed;
    }

    /** The foot of the tower you are on, on the side you are not looking at. */
    @Nullable
    private static Vec3 towerBase(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getViewVector(1.0F).multiply(1.0, 0.0, 1.0);
        if (look.lengthSqr() < 1.0E-4) {
            look = new Vec3(0, 0, 1);
        }
        Vec3 back = player.position().subtract(look.normalize().scale(4.0));
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(back.x), Mth.floor(back.z));
        BlockPos at = Spots.groundNear(level, Mth.floor(back.x), ground, Mth.floor(back.z), 3);
        return at == null ? null : Vec3.atBottomCenterOf(at);
    }
}
