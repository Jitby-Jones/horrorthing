package io.github.jitbyjones.thewake.entity;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.haunt.Ambush;
import io.github.jitbyjones.thewake.haunt.Attack;
import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.haunt.LastNight;
import io.github.jitbyjones.thewake.haunt.Possession;
import io.github.jitbyjones.thewake.haunt.Pursuit;
import io.github.jitbyjones.thewake.haunt.Reputation;
import io.github.jitbyjones.thewake.haunt.Temper;
import io.github.jitbyjones.thewake.net.AttackPayload;
import io.github.jitbyjones.thewake.net.WakeFxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What its body does, tick by tick. Server only.
 *
 * <p>Modes, roughly in the order you meet them:
 * <ul>
 * <li>STALK: it follows you through the dark, and moves only while you are not looking at it.
 * Look at it and it stops; keep looking and it goes, or it comes.</li>
 * <li>LURK: you are up on something you built to watch from. It climbs the side you are not
 * watching and waits at the edge behind you with its face over it.</li>
 * <li>CHASE: it runs at you. Fast, but not faster than you sprinting on open ground. It climbs,
 * digs, tears doors out, jumps into the water after you. It gives up after a while.</li>
 * <li>SEARCH: it lost you. It goes where it last saw you and looks, and listens.</li>
 * <li>SIEGE: you shut yourself in. It knocks first. Then it makes a hole and looks in.</li>
 * <li>GRAB: it has you. You can struggle (sneak, over and over).</li>
 * <li>and the moments in between: STUNNED, LEAP, SWIM, RIP, BREAK, LEAVE.</li>
 * </ul>
 */
public final class WakeBrain {
    public enum Mode { STALK, LURK, CHASE, SEARCH, SIEGE, GRAB, STUNNED, LEAP, SWIM, RIP, BREAK, LEAVE, SLEEP, AMBUSH, POUNCE, TAG, PICK, REACH }

    /** How it came to be out: decides what it does first. */
    public enum Purpose { STALK, CHASE, SIEGE, LURK, LAST_NIGHT, ASLEEP, AMBUSH }

    private static final double GRAB_RANGE = 1.9;
    /** Struggles (sneak presses and the dismounts they cause) that get you free before the bite. */
    private static final int STRUGGLE_FREE = 8;
    /** Ticks it holds you up to its face before the jaw goes: your window to struggle. */
    private static final int GRAB_LOOK = 36;
    private static final int GRAB_END = GRAB_LOOK + 48;

    private final WakeEntity body;
    private final UUID ownerId;
    private Mode mode = Mode.STALK;
    private Mode resume = Mode.CHASE;
    private Purpose purpose = Purpose.STALK;
    private int modeTicks;
    private int life;
    private int budget;

    // sight
    private int seenTicks;
    private int unseenTicks;
    private int lostTicks;
    @Nullable
    private Vec3 lastKnown;

    // progress
    private double lastDistance = Double.MAX_VALUE;
    private int stuckTicks;
    private int repathCooldown;

    // breaking
    @Nullable
    private BlockPos breaking;
    private int breakTotal;
    private int broken;

    // ripping
    @Nullable
    private BlockPos door;

    // leap
    private Vec3 leapFrom = Vec3.ZERO;
    private Vec3 leapTo = Vec3.ZERO;
    private int leapTotal;
    private double leapApex;

    // grab
    private boolean wasPassenger;
    private boolean lastShift;
    private int struggles;
    private boolean underwater;

    // stun
    private int stunTotal;

    // ambush and pounce
    private Ambush.Kind ambushKind = Ambush.Kind.CORNER;
    @Nullable
    private BlockPos ambushWatch;
    private int strikeIn;
    private boolean pouncing;
    private boolean lowLeap;
    private int pounceCooldown = 60;

    // uneasy: taking your light
    @Nullable
    private BlockPos pickAt;
    private boolean pickHand;
    private int pickCooldown = 100;
    private Mode afterPick = Mode.LEAVE;

    // angry: the neck
    private CreaturePose reachClip = CreaturePose.REACH_WALL;
    private int reachCooldown = 80;

    // furious: the chewed tape
    private int chewClock = 30;

    // siege
    private int siegePhase;
    @Nullable
    private BlockPos siegeWall;
    private int knockClock;

    // lurk
    @Nullable
    private BlockPos lookout;
    private boolean atLedge;

    // misc
    private int screamClock = 40;
    private int soundClock;
    private int lastFrame = -1;
    private boolean snuffed;
    private boolean learnedLight;
    private boolean learnedPillar;
    private boolean caught;
    private boolean ended;
    private float stareDegrees = 50.0F;

    WakeBrain(WakeEntity body, UUID ownerId) {
        this.body = body;
        this.ownerId = ownerId;
    }

    public Mode mode() {
        return mode;
    }

    public Purpose purpose() {
        return purpose;
    }

    public boolean lastNight() {
        return purpose == Purpose.LAST_NIGHT;
    }

    /** Sets what it is out for. Call once, right after it is added to the world. */
    public void start(ServerPlayer owner, Purpose why) {
        purpose = why;
        HauntState state = Haunt.state(owner);
        Reputation.Form form = Temper.form(state);
        body.setForm(why == Purpose.LAST_NIGHT ? Reputation.Form.FURIOUS : form);
        body.setPins(Temper.pins(state));
        RandomSource random = body.getRandom();
        budget = switch (why) {
            case CHASE -> 20 * (24 + 8 * form.ordinal() + random.nextInt(12));
            case STALK -> form == Reputation.Form.FURIOUS ? 20 * (90 + random.nextInt(60)) : 20 * (50 + random.nextInt(50));
            case LURK -> 20 * 120;
            case SIEGE -> 20 * 90;
            case LAST_NIGHT -> Integer.MAX_VALUE;
            case ASLEEP -> Integer.MAX_VALUE;
            case AMBUSH -> 20 * 60;
        };
        switch (why) {
            case CHASE, LAST_NIGHT -> beginChase(owner, true);
            case SIEGE -> set(Mode.SIEGE);
            case LURK -> set(Mode.LURK);
            case ASLEEP -> set(Mode.SLEEP);
            case AMBUSH -> set(Mode.AMBUSH);
            default -> set(Mode.STALK);
        }
    }

    /** It heard you (its tape rustling where you went through it): it knows where you are. */
    public void heard(ServerPlayer owner, Vec3 at) {
        lastKnown = at;
        HauntState state = Haunt.state(owner);
        if (mode == Mode.STALK || mode == Mode.SEARCH || mode == Mode.AMBUSH) {
            if (Temper.form(state).atLeast(Reputation.Form.ANGRY) && Pursuit.wantsChase(owner, state, body.distanceTo(owner))) {
                beginChase(owner, false);
            } else {
                body.getNavigation().moveTo(at.x, at.y, at.z, 0.9);
            }
        }
    }

    /** Off the ceiling and straight down on you (see {@code Strings}). */
    public void dropOn(ServerPlayer owner) {
        HauntState state = Haunt.state(owner);
        Pursuit.chaseStarted(owner, state, body);
        budget = Math.max(budget, 20 * 30);
        pouncing = true;
        lowLeap = true;
        body.setNoGravity(false);
        beginLeap(owner, owner.position());
    }

    /** The string was cut: it has fallen, and lies there a moment before it gets up. */
    public void fallen(ServerPlayer owner) {
        budget = Math.max(budget, 20 * 30);
        stun(owner, 40, Mode.CHASE);
    }

    /** What sort of wait this is, and what to watch (the door it is behind, the mark it is on). */
    public void ambush(Ambush.Kind kind, @Nullable BlockPos watch) {
        ambushKind = kind;
        ambushWatch = watch;
    }

    private void set(Mode next) {
        if (mode != next) {
            mode = next;
            modeTicks = 0;
        }
    }

    // ---- the tick -------------------------------------------------------------------------

    void tick(ServerPlayer owner) {
        life++;
        modeTicks++;
        if (ended) {
            return;
        }
        HauntState state = Haunt.state(owner);
        if (!owner.isAlive() || owner.isRemoved()) {
            finish(owner, state, Pursuit.Outcome.CAUGHT);
            return;
        }
        if (owner.isSpectator() || owner.isCreative()) {
            // Not a way out. It takes your chat box and puts you back.
            Possession.demand(owner, state, mode == Mode.GRAB ? Possession.Why.HELD : Possession.Why.CHASED);
        }
        updateSight(owner);
        if (life % 10 == 0 && (mode == Mode.CHASE || mode == Mode.STALK || mode == Mode.SEARCH)) {
            io.github.jitbyjones.thewake.haunt.ShedTape.trail(owner, body);
        }
        if (mode != Mode.GRAB && mode != Mode.LEAVE && mode != Mode.SLEEP && --budget <= 0) {
            giveUp(owner, state);
            return;
        }
        body.setNoGravity(mode == Mode.LEAP || mode == Mode.SWIM || mode == Mode.LURK && atLedge || mode == Mode.SLEEP
                || mode == Mode.AMBUSH && ambushKind == Ambush.Kind.CEILING);
        switch (mode) {
            case STALK -> stalk(owner, state);
            case LURK -> lurk(owner, state);
            case CHASE -> chase(owner, state);
            case SEARCH -> search(owner, state);
            case SIEGE -> siege(owner, state);
            case GRAB -> grab(owner, state);
            case STUNNED -> stunned(owner, state);
            case LEAP -> leap(owner, state);
            case SWIM -> swim(owner, state);
            case RIP -> rip(owner, state);
            case BREAK -> breakBlock(owner, state);
            case LEAVE -> leave(owner, state);
            case SLEEP -> sleep(owner, state);
            case AMBUSH -> waitFor(owner, state);
            case POUNCE -> pounce(owner, state);
            case TAG -> tagged(owner, state);
            case PICK -> picking(owner, state);
            case REACH -> reaching(owner, state);
        }
        animate(owner);
    }

    // ---- sight ----------------------------------------------------------------------------

    /** Whether its owner can see it right now: in front of their eyes, nothing in the way. */
    boolean seenBy(ServerPlayer owner) {
        Vec3 eye = owner.getEyePosition();
        Vec3 head = body.position().add(0.0, Math.min(2.6, body.clip().height * 0.85), 0.0);
        Vec3 chest = body.position().add(0.0, 1.1, 0.0);
        Vec3 look = owner.getViewVector(1.0F);
        boolean any = false;
        for (Vec3 p : new Vec3[] {head, chest}) {
            Vec3 to = p.subtract(eye);
            double len = to.length();
            if (len < 1.0E-3) {
                return true;
            }
            double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(to) / len, -1.0, 1.0)));
            if (angle > stareDegrees) {
                continue;
            }
            BlockHitResult hit = owner.level().clip(new ClipContext(eye, p, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, owner));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(eye) > len * len - 0.5) {
                any = true;
                break;
            }
        }
        return any;
    }

    /** Whether it can see its owner: a line from its face to them. */
    private boolean seesOwner(ServerPlayer owner) {
        Vec3 face = body.position().add(0.0, 2.4, 0.0);
        for (Vec3 p : new Vec3[] {owner.getEyePosition(), owner.position().add(0.0, 0.4, 0.0)}) {
            BlockHitResult hit = body.level().clip(new ClipContext(face, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(p) < 0.6) {
                return true;
            }
        }
        return false;
    }

    private void updateSight(ServerPlayer owner) {
        if (seenBy(owner)) {
            seenTicks++;
            unseenTicks = 0;
        } else {
            unseenTicks++;
            seenTicks = 0;
        }
        if (seesOwner(owner)) {
            lostTicks = 0;
            lastKnown = owner.position();
        } else {
            lostTicks++;
        }
    }

    // ---- stalking -------------------------------------------------------------------------

    private void stalk(ServerPlayer owner, HauntState state) {
        double d = body.distanceTo(owner);
        if (Pursuit.onLookout(owner)) {
            lookout = owner.blockPosition();
            set(Mode.LURK);
            return;
        }
        Reputation.Form form = Temper.form(state);
        boolean furious = form == Reputation.Form.FURIOUS;
        if (seenTicks > 0) {
            // Caught moving: stop dead. The frame holds.
            body.getNavigation().stop();
            body.setDeltaMovement(Vec3.ZERO);
            faceOwner(owner, 0.0F);
            if (seenTicks == 1) {
                Temper.interest(state, 0.01F);
            }
            // Angry, it takes being looked at for a moment; furious, hardly at all.
            int patience = furious ? 12 : form == Reputation.Form.ANGRY ? 30 : 50;
            if (seenTicks > patience) {
                if (Pursuit.wantsChase(owner, state, d) && form.atLeast(Reputation.Form.ANGRY)) {
                    beginChase(owner, false);
                } else if (!furious) {
                    Temper.provoke(owner, state, Temper.Provocation.STARED);
                    startLeaving(owner);
                }
                // Furious and not yet afraid enough to run at you: it does not go. It waits for you
                // to look away, and comes closer.
            }
            return;
        }
        if (unseenTicks < 3) {
            return;
        }
        // It passes your torches, and they go out, one by one.
        if (form.atLeast(Reputation.Form.UNEASY) && --pickCooldown <= 0 && body.form() == Reputation.Form.UNEASY) {
            pickCooldown = 40;
            if (beginPick(owner, false)) {
                return;
            }
        }
        // Furious, close, and you have not turned round: that is long enough.
        if (furious && d < 9.0 && unseenTicks > 20 && Pursuit.wantsChase(owner, state, d) && body.getRandom().nextFloat() < 0.012F) {
            beginChase(owner, false);
            return;
        }
        // Behind you, a little closer every time you do not turn round. Furious, a lot closer.
        double want = furious ? Mth.clamp(10.0 - life / 120.0, 3.5, 10.0)
                : form == Reputation.Form.ANGRY ? Mth.clamp(13.0 - life / 140.0, 5.0, 13.0) : Mth.clamp(16.0 - life / 160.0, 7.0, 16.0);
        if (d > want + 1.5 && repathCooldown-- <= 0) {
            Vec3 point = Pursuit.stalkPoint(owner, want);
            if (point != null) {
                body.getNavigation().moveTo(point.x, point.y, point.z, 0.85);
            }
            repathCooldown = 20;
        } else if (d <= want) {
            body.getNavigation().stop();
            faceOwner(owner, 0.0F);
        }
        doorsOnTheWay(owner, state, false);
        // When you stop, it takes one more step.
        if (owner.walkDist - owner.walkDistO < 0.01 && body.getNavigation().isInProgress() && --soundClock <= 0 && d < 20) {
            soundClock = 30 + body.getRandom().nextInt(40);
            WakeSounds.at(owner, WakeRegistry.STEP.get(), body.position(), 0.6F, 0.8F);
        }
    }

    // ---- the watchtower -------------------------------------------------------------------

    /**
     * You built something high to watch from. It comes up the side you are not looking at, without a
     * sound, and stops with its face just over the edge, behind you, and waits for you to turn.
     */
    private void lurk(ServerPlayer owner, HauntState state) {
        if (lookout == null) {
            lookout = owner.blockPosition();
        }
        if (!Pursuit.onLookout(owner) && !atLedge) {
            set(Mode.STALK);
            return;
        }
        if (atLedge) {
            body.setDeltaMovement(Vec3.ZERO);
            body.setFlag(WakeEntity.FLAG_HOLDING, true);
            faceOwner(owner, 30.0F);
            if (seenTicks == 1) {
                WakeSounds.at(owner, WakeRegistry.WET_BREATH.get(), body.position().add(0, 1.0, 0), 0.8F, 0.75F);
            }
            if (seenTicks > 18) {
                body.setFlag(WakeEntity.FLAG_HOLDING, false);
                atLedge = false;
                Temper.learn(owner, state, Reputation.Tactic.TOWER);
                if (Temper.form(state).atLeast(Reputation.Form.ANGRY) && Pursuit.wantsChase(owner, state, body.distanceTo(owner))) {
                    // Up and over the edge.
                    body.setPos(owner.getX() + (body.getX() - owner.getX()) * 0.5, owner.getY(), owner.getZ() + (body.getZ() - owner.getZ()) * 0.5);
                    beginChase(owner, false);
                } else {
                    // It lets go.
                    body.setNoGravity(false);
                    startLeaving(owner);
                }
            }
            if (modeTicks > 20 * 90) {
                startLeaving(owner);
            }
            return;
        }
        if (seenTicks > 0) {
            body.getNavigation().stop();
            body.setDeltaMovement(body.getDeltaMovement().multiply(0.0, 0.0, 0.0));
            body.setFlag(WakeEntity.FLAG_HOLDING, body.onClimbable());
            return;
        }
        body.setFlag(WakeEntity.FLAG_HOLDING, false);
        // The far side of the tower from where you are looking.
        Vec3 look = owner.getViewVector(1.0F);
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() < 1.0E-4) {
            flat = new Vec3(0.0, 0.0, 1.0);
        }
        flat = flat.normalize();
        Vec3 behind = owner.position().subtract(flat.scale(1.6));
        double dy = owner.getY() - body.getY();
        double horizontal = Math.hypot(behind.x - body.getX(), behind.z - body.getZ());
        if (horizontal > 1.2) {
            body.getNavigation().moveTo(behind.x, body.getY(), behind.z, 0.9);
            body.setFlag(WakeEntity.FLAG_CLIMBING, body.horizontalCollision);
        } else {
            // Right under you: up.
            body.getNavigation().stop();
            body.setFlag(WakeEntity.FLAG_CLIMBING, true);
            if (dy > 0.6) {
                body.setDeltaMovement(flat.scale(0.02).add(0.0, 0.16, 0.0));
            }
        }
        if (dy < 0.4 && body.onClimbable()) {
            // Its head is just over the edge.
            atLedge = true;
            body.setPos(body.getX(), owner.getY() - 0.02, body.getZ());
            body.setFlag(WakeEntity.FLAG_CLIMBING, true);
            Temper.rememberLookout(state, owner.blockPosition());
        }
    }

    // ---- waiting for you ------------------------------------------------------------------

    /**
     * Round the corner, behind the door, on the ceiling: still and silent until you are close. Then
     * the tell (everything goes quiet), then it comes. If you see it first, from far enough away,
     * it stares back, and then either comes anyway or goes.
     */
    private void waitFor(ServerPlayer owner, HauntState state) {
        body.getNavigation().stop();
        body.setDeltaMovement(ambushKind == Ambush.Kind.CEILING ? Vec3.ZERO : new Vec3(0.0, body.getDeltaMovement().y, 0.0));
        Reputation.Form form = lastNight() ? Reputation.Form.FURIOUS : Temper.form(state);
        double d = body.distanceTo(owner);
        if (strikeIn > 0) {
            faceOwner(owner, 0.0F);
            if (--strikeIn == 0) {
                strike(owner, state, form);
            }
            return;
        }
        if (seenTicks > 0 && d > 5.0) {
            // Found out. It looks back.
            faceOwner(owner, 0.0F);
            if (seenTicks > 30) {
                if (form.atLeast(Reputation.Form.ANGRY) && Pursuit.wantsChase(owner, state, d)) {
                    beginChase(owner, false);
                } else {
                    Temper.provoke(owner, state, Temper.Provocation.STARED);
                    startLeaving(owner);
                }
            }
            return;
        }
        boolean opened = ambushKind == Ambush.Kind.DOOR && ambushWatch != null && Obstacles.isOpenDoor(body.level(), ambushWatch);
        boolean close = d <= Ambush.reachOf(form) && (seesOwner(owner) || ambushKind == Ambush.Kind.CEILING);
        if (close || opened && d < 6.0) {
            strikeIn = Ambush.tellTicks(form);
            faceOwner(owner, 0.0F);
            WakeNetwork.send(owner, WakeFxPayload.of(WakeFxPayload.HUSH, 1.0F, strikeIn + 8.0F));
            WakeSounds.at(owner, WakeRegistry.WET_BREATH.get(), body.position().add(0.0, 1.6, 0.0), 0.9F, 0.7F);
            return;
        }
        if (modeTicks > 20 * 45) {
            startLeaving(owner);
        }
    }

    /** Out of the wait: uneasy, it only touches you; angrier, it jumps on you. */
    private void strike(ServerPlayer owner, HauntState state, Reputation.Form form) {
        if (form == Reputation.Form.UNEASY && !lastNight()) {
            if (!beginPick(owner, true)) {
                beginTag(owner, state);
            }
            return;
        }
        if (ambushKind == Ambush.Kind.CEILING) {
            body.setNoGravity(false);
        }
        Temper.chaseBegan(state, owner.level().getGameTime());
        Pursuit.chaseStarted(owner, state, body);
        budget = Math.max(budget, 20 * (20 + 8 * form.ordinal()));
        pouncing = true;
        beginLeap(owner, owner.position());
    }

    /**
     * It is suddenly right behind you, and its hand is on your shoulder. That is all, tonight. But
     * tonight, if you give it a reason, it will chase you as if it were angry.
     */
    private void beginTag(ServerPlayer owner, HauntState state) {
        Vec3 dir = owner.getViewVector(1.0F).multiply(1.0, 0.0, 1.0);
        dir = dir.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : dir.normalize();
        Vec3 behind = owner.position().subtract(dir.scale(1.3));
        BlockPos spot = io.github.jitbyjones.thewake.haunt.Spots.groundNear((ServerLevel) body.level(), Mth.floor(behind.x), owner.getBlockY(),
                Mth.floor(behind.z), 1);
        if (spot != null) {
            body.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        }
        faceOwner(owner, 0.0F);
        set(Mode.TAG);
        WakeSounds.at(owner, WakeRegistry.GRAB.get(), owner.getEyePosition(), 0.7F, 1.3F);
        WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.SHAKE, 0, 0, 0, 0.45F, 10.0F));
        Vec3 face = body.position().add(0.0, 2.7, 0.0);
        WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.LOOK_AT, face.x, face.y, face.z, 1.0F, 14.0F));
        long now = owner.level().getGameTime();
        state.cooldown("tagged", now, 20L * 60 * 8);
        state.tension = Math.max(state.tension, 0.9F);
        state.lastScareTick = now;
    }

    private void tagged(ServerPlayer owner, HauntState state) {
        body.getNavigation().stop();
        faceOwner(owner, -6.0F);
        body.setJaw(Math.min(30.0F, modeTicks * 2.0F));
        if (modeTicks > 26) {
            finish(owner, state, Pursuit.Outcome.GAVE_UP);
        }
    }

    /**
     * UNEASY's hook, and its only attack: the small hand takes your light. A torch out of your hand,
     * or off your wall, or a lantern off its hook, and it is gone, and so is the light.
     * {@code fromYou}: an ambush, so it is right there in front of you when it does it.
     */
    private boolean beginPick(ServerPlayer owner, boolean fromYou) {
        pickHand = false;
        pickAt = null;
        if (fromYou && (isLight(owner.getMainHandItem()) || isLight(owner.getOffhandItem()))) {
            pickHand = true;
            Vec3 dir = owner.position().subtract(body.position()).multiply(1.0, 0.0, 1.0);
            if (dir.lengthSqr() > 1.0E-4) {
                Vec3 front = owner.position().subtract(dir.normalize().scale(0.9));
                BlockPos spot = io.github.jitbyjones.thewake.haunt.Spots.groundNear((ServerLevel) body.level(), Mth.floor(front.x),
                        owner.getBlockY(), Mth.floor(front.z), 1);
                if (spot != null) {
                    body.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                }
            }
        } else {
            pickAt = Obstacles.lightNear(body.level(), fromYou ? owner.blockPosition() : body.blockPosition(), fromYou ? 5 : 2);
            if (pickAt == null) {
                return false;
            }
        }
        afterPick = fromYou ? Mode.LEAVE : Mode.STALK;
        body.getNavigation().stop();
        set(Mode.PICK);
        return true;
    }

    private static boolean isLight(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.TORCH) || stack.is(net.minecraft.world.item.Items.SOUL_TORCH)
                || stack.is(net.minecraft.world.item.Items.LANTERN) || stack.is(net.minecraft.world.item.Items.SOUL_LANTERN)
                || stack.is(net.minecraft.world.item.Items.REDSTONE_TORCH);
    }

    private void picking(ServerPlayer owner, HauntState state) {
        body.setDeltaMovement(new Vec3(0.0, body.getDeltaMovement().y, 0.0));
        if (pickAt != null) {
            body.setYRot(Spots.yawTowards(body.position(), Vec3.atCenterOf(pickAt)));
            body.yBodyRot = body.getYRot();
        } else {
            faceOwner(owner, 0.0F);
        }
        if (modeTicks == 8) {
            ServerLevel level = (ServerLevel) body.level();
            if (pickHand) {
                for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
                    ItemStack held = owner.getItemInHand(hand);
                    if (isLight(held)) {
                        held.shrink(held.getCount());
                        break;
                    }
                }
                WakeSounds.at(owner, WakeRegistry.GRAB.get(), owner.getEyePosition(), 0.7F, 1.5F);
                WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.SHAKE, 0, 0, 0, 0.3F, 6.0F));
            } else if (pickAt != null && Obstacles.isLight(level.getBlockState(pickAt))) {
                level.removeBlock(pickAt, false);
                level.playSound(null, pickAt, net.minecraft.sounds.SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 0.6F, 0.6F);
            }
            state.tension = Math.min(1.0F, state.tension + 0.08F);
        }
        if (modeTicks >= 18) {
            pickCooldown = 120 + body.getRandom().nextInt(160);
            if (afterPick == Mode.STALK) {
                set(Mode.STALK);
            } else {
                finish(owner, state, Pursuit.Outcome.GAVE_UP);
            }
        }
    }

    /**
     * ANGRY: you are somewhere its body cannot get to (up a pillar, behind a wall, in a room with a
     * window), and its neck can. It unfolds, one vertebra at a time, and at the end of it the jaw
     * drops, while its body stays out there in the dark.
     */
    private boolean beginReach(ServerPlayer owner) {
        if (reachCooldown > 0 || body.form() != Reputation.Form.ANGRY) {
            return false;
        }
        double dy = owner.getY() - body.getY();
        double flat = Math.hypot(owner.getX() - body.getX(), owner.getZ() - body.getZ());
        CreaturePose clip = null;
        if (dy >= 1.8 && dy <= 4.2 && flat <= 2.6) {
            clip = CreaturePose.REACH_UP;
        } else if (flat <= 3.2 && Math.abs(dy) < 1.0) {
            clip = seesOwner(owner) ? CreaturePose.REACH_WINDOW : CreaturePose.REACH_WALL;
        }
        if (clip == null) {
            return false;
        }
        reachClip = clip;
        reachCooldown = 20 * 8;
        body.getNavigation().stop();
        set(Mode.REACH);
        WakeSounds.at(owner, WakeRegistry.CRACK.get(), body.position().add(0.0, 2.6, 0.0), 1.0F, 0.6F);
        return true;
    }

    /** Where the end of its neck gets to, from its feet: forward and up, by clip. */
    private Vec3 reachTip() {
        double fwd = reachClip == CreaturePose.REACH_UP ? 0.5 : reachClip == CreaturePose.REACH_WINDOW ? 2.2 : 1.0;
        double up = reachClip == CreaturePose.REACH_UP ? 4.4 : reachClip == CreaturePose.REACH_WINDOW ? 2.6 : 2.8;
        return body.position().add(Vec3.directionFromRotation(0.0F, body.getYRot()).scale(fwd)).add(0.0, up, 0.0);
    }

    private void reaching(ServerPlayer owner, HauntState state) {
        body.setDeltaMovement(new Vec3(0.0, body.getDeltaMovement().y, 0.0));
        if (modeTicks < 20) {
            faceOwner(owner, 0.0F);
        }
        // It unfolds in clicks: one vertebra a frame.
        if (modeTicks % 3 == 0 && modeTicks <= 20) {
            WakeSounds.at(owner, WakeRegistry.CRACK.get(), reachTip(), 0.5F, 1.4F + body.getRandom().nextFloat() * 0.3F);
        }
        if (modeTicks == 22) {
            body.setJaw(60.0F);
            if (reachTip().distanceTo(owner.getEyePosition()) < 1.9) {
                Vec3 face = reachTip();
                WakeNetwork.send(owner, new AttackPayload(face.x, face.y, face.z));
                hurtOwner(owner, state);
            }
        }
        if (modeTicks > 50) {
            body.setJaw(0.0F);
            stuckTicks = 0;
            set(Mode.CHASE);
        }
    }

    /** The tell before it jumps: it drops low and goes still, and so does everything else. */
    private void pounce(ServerPlayer owner, HauntState state) {
        body.getNavigation().stop();
        body.setDeltaMovement(new Vec3(0.0, body.getDeltaMovement().y, 0.0));
        faceOwner(owner, 0.0F);
        if (--strikeIn <= 0) {
            pouncing = true;
            beginLeap(owner, owner.position());
        }
    }

    // ---- the chase ------------------------------------------------------------------------

    private void beginChase(ServerPlayer owner, boolean fresh) {
        set(Mode.CHASE);
        stareDegrees = 50.0F;
        HauntState state = Haunt.state(owner);
        if (!fresh || purpose != Purpose.CHASE && purpose != Purpose.LAST_NIGHT) {
            budget = Math.max(budget, 20 * (24 + 8 * Temper.form(state).ordinal()));
        }
        Pursuit.chaseStarted(owner, state, body);
        scream(owner, 1.0F);
        // It learned that you run into the light.
        if (Temper.knows(state, Reputation.Tactic.LIGHT, 1) && body.getRandom().nextFloat() < 0.6F) {
            owner.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 20 * 14, 0, false, false));
        }
    }

    private double chaseSpeed(ServerPlayer owner, HauntState state, double d) {
        Reputation.Form form = lastNight() ? Reputation.Form.FURIOUS : Temper.form(state);
        // A mob's top speed on flat ground is about 44 x (attribute x modifier)^2 metres a second: with
        // the attribute at 0.3 these are 4.8 to 5.8, against your 5.6 sprinting and about 7
        // sprint-jumping. A sprint on open ground slowly gets away from it; anything else does not.
        double s = switch (form) {
            case WATCHER -> 1.10;
            case UNEASY -> 1.13;
            case ANGRY -> 1.17;
            case FURIOUS -> 1.21;
        };
        if (lastNight()) {
            s += 0.03 * Temper.pins(state);
        }
        if (d > 22.0) {
            s *= 1.15;
        } else if (d < 4.0) {
            s *= 1.06;
        }
        if (owner.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) {
            s *= 0.95;
        }
        return s;
    }

    private void chase(ServerPlayer owner, HauntState state) {
        ServerLevel level = (ServerLevel) body.level();
        double d = body.distanceTo(owner);
        // In reach: it has you.
        if (d < GRAB_RANGE && Math.abs(owner.getY() - body.getY()) < 2.2 && grabbable(owner)) {
            beginGrab(owner, state);
            return;
        }
        // Angry, close, and something between you (a wall, or you are up on something): its neck.
        if (reachCooldown > 0) {
            reachCooldown--;
        } else if (body.form() == Reputation.Form.ANGRY && !lastNight()) {
            double flat = Math.hypot(owner.getX() - body.getX(), owner.getZ() - body.getZ());
            double dy = owner.getY() - body.getY();
            if ((flat < 3.2 && !seesOwner(owner) || flat < 2.6 && dy >= 1.8) && beginReach(owner)) {
                return;
            }
        }
        // Into the water after you.
        if (!body.isInWater() && (owner.isInWater() || owner.getVehicle() instanceof Boat) && d > 3.0) {
            double range = Temper.knows(state, Reputation.Tactic.WATER, 1) ? 22.0 : 15.0;
            if (d < range && seesOwner(owner)) {
                Temper.learn(owner, state, owner.getVehicle() instanceof Boat ? Reputation.Tactic.BOAT : Reputation.Tactic.WATER);
                beginLeap(owner, owner.position());
                return;
            }
        }
        if (body.isInWater()) {
            set(Mode.SWIM);
            return;
        }
        // Light: you ran to it. It learns, and the angrier it is the less light means.
        int light = level.getBrightness(LightLayer.BLOCK, owner.blockPosition());
        if (light >= 12) {
            if (!learnedLight) {
                learnedLight = true;
                Temper.learn(owner, state, Reputation.Tactic.LIGHT);
            }
            if (!snuffed && d < 16.0 && (Temper.form(state) == Reputation.Form.FURIOUS || lastNight()) && WakeConfig.get().chaseBreaksBlocks) {
                snuffed = true;
                if (Obstacles.snuffLights(level, owner.blockPosition(), 7, 8) > 0) {
                    WakeNetwork.send(owner, WakeFxPayload.of(WakeFxPayload.FLICKER, 1.0F, 30.0F));
                }
            }
        }
        // Up a pillar.
        if (!learnedPillar && Pursuit.onPillar(owner)) {
            learnedPillar = true;
            Temper.learn(owner, state, Reputation.Tactic.PILLAR);
            Temper.provoke(owner, state, Temper.Provocation.CHEESE);
        }
        // A door in the way.
        if (doorsOnTheWay(owner, state, true)) {
            return;
        }
        // An iron door between you: it goes for the door, not the wall.
        if (body.tickCount % 10 == 0 && d > 2.5 && WakeConfig.get().chaseRipsDoors) {
            BlockPos iron = Obstacles.rippableToward(body, owner, 5);
            if (iron != null) {
                door = iron;
                resume = Mode.CHASE;
                set(Mode.RIP);
                Temper.learn(owner, state, Reputation.Tactic.DOOR);
                return;
            }
        }
        // Angry or worse, at the right distance, it does not run the last of the way: it jumps.
        Reputation.Form temper = lastNight() ? Reputation.Form.FURIOUS : Temper.form(state);
        if (pounceCooldown > 0) {
            pounceCooldown--;
        } else if (temper.atLeast(Reputation.Form.ANGRY) && d > 4.5 && d < 8.5 && body.onGround() && !body.onClimbable()
                && Math.abs(owner.getY() - body.getY()) < 1.5 && seesOwner(owner) && body.getRandom().nextFloat() < 0.06F) {
            pounceCooldown = 20 * (temper == Reputation.Form.FURIOUS ? 6 : 9) + body.getRandom().nextInt(80);
            strikeIn = Ambush.tellTicks(temper);
            set(Mode.POUNCE);
            WakeNetwork.send(owner, WakeFxPayload.of(WakeFxPayload.HUSH, 1.0F, strikeIn + 6.0F));
            return;
        }
        // Furious: it moves the way a chewed tape plays. It holds, and then it is somewhere else,
        // further along, all at once, and for a moment there are three of it.
        if (temper == Reputation.Form.FURIOUS && --chewClock <= 0) {
            chewClock = 14 + body.getRandom().nextInt(16);
            if (d > 5.0 && d < 30.0 && seesOwner(owner)) {
                Vec3 to = owner.position().subtract(body.position()).multiply(1.0, 0.0, 1.0);
                double hop = Math.min(d - 3.0, 2.5 + body.getRandom().nextDouble() * 1.5);
                Vec3 target = body.position().add(to.normalize().scale(hop));
                BlockPos spot = io.github.jitbyjones.thewake.haunt.Spots.groundNear((ServerLevel) body.level(), Mth.floor(target.x),
                        body.getBlockY(), Mth.floor(target.z), 2);
                if (spot != null) {
                    Vec3 from = body.position();
                    body.getNavigation().stop();
                    body.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                    WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.SKIP, from.x, from.y, from.z, 1.0F, body.getYRot()));
                    WakeSounds.at(owner, WakeRegistry.WARBLE.get(), body.position().add(0.0, 1.6, 0.0), 0.9F, 0.7F + body.getRandom().nextFloat() * 0.3F);
                    return;
                }
            }
        }
        double speed = chaseSpeed(owner, state, d);
        if (--repathCooldown <= 0 || body.getNavigation().isDone()) {
            body.getNavigation().moveTo(owner, speed);
            repathCooldown = d < 8.0 ? 4 : 10;
        }
        body.getNavigation().setSpeedModifier(speed);
        boolean above = owner.getY() > body.getY() + 1.2;
        body.setFlag(WakeEntity.FLAG_CLIMBING, body.horizontalCollision && (above || stuckTicks > 10));
        // Not getting closer: something is in the way.
        if (body.tickCount % 10 == 0) {
            if (d > lastDistance - 0.35 && d > 2.2) {
                stuckTicks += 10;
            } else {
                stuckTicks = Math.max(0, stuckTicks - 10);
            }
            lastDistance = d;
        }
        if (stuckTicks >= 20 && beginReach(owner)) {
            stuckTicks = 0;
            return;
        }
        if (stuckTicks >= 30) {
            stuckTicks = 0;
            if (dealWithObstacle(owner, state)) {
                return;
            }
            // Nothing it can break between you, and it cannot reach you: you are up something, or shut
            // in by things it cannot move. While you are not looking, the tape skips.
            if (unseenTicks > 20 || Temper.knows(state, Reputation.Tactic.PILLAR, 2)) {
                Vec3 near = Pursuit.reachableNear(owner, 2.5, 5.0);
                if (near != null) {
                    Pursuit.tapeSkip(owner, body, near);
                }
            }
        }
        // Shut in: knock first.
        if (body.tickCount % 40 == 0 && d < 14.0 && Obstacles.enclosed(level, owner.blockPosition(), 70)) {
            Temper.learn(owner, state, Reputation.Tactic.BOX);
            Temper.provoke(owner, state, Temper.Provocation.CHEESE);
            set(Mode.SIEGE);
            siegePhase = 0;
            return;
        }
        // Lost you.
        if (lostTicks > 110 && d > 14.0) {
            set(Mode.SEARCH);
            return;
        }
        if (--screamClock <= 0) {
            screamClock = 90 + body.getRandom().nextInt(110);
            if (d < 48.0) {
                scream(owner, 0.9F + body.getRandom().nextFloat() * 0.2F);
            }
        }
        faceOwner(owner, 0.0F);
    }

    private boolean grabbable(ServerPlayer owner) {
        return !owner.isSpectator() && !caught && owner.getVehicle() == null || owner.getVehicle() == body;
    }

    /**
     * The closed door on its way: wooden ones it bangs open (or, furious, through), iron ones it tears
     * out of the wall and throws. Returns true if it is busy with one.
     */
    private boolean doorsOnTheWay(ServerPlayer owner, HauntState state, boolean chasing) {
        BlockPos at = Obstacles.doorAhead(body);
        if (at == null) {
            return false;
        }
        BlockState s = body.level().getBlockState(at);
        if (Obstacles.rippable(s)) {
            if (!chasing) {
                // Stalking: it does not make a sound. It goes another way.
                return false;
            }
            if (!WakeConfig.get().chaseRipsDoors) {
                return false;
            }
            door = at;
            set(Mode.RIP);
            resume = Mode.CHASE;
            Temper.learn(owner, state, Reputation.Tactic.DOOR);
            return true;
        }
        if (chasing && Temper.form(state) == Reputation.Form.FURIOUS && WakeConfig.get().chaseBreaksBlocks && broken < WakeConfig.get().chaseBreakLimit) {
            body.level().destroyBlock(at, true, body);
            broken++;
            body.level().playSound(null, at, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 1.0F, 0.6F);
            return false;
        }
        Obstacles.bang(body, at);
        return false;
    }

    /** Something in the way and it cannot path round: rip it, break it. */
    private boolean dealWithObstacle(ServerPlayer owner, HauntState state) {
        BlockPos block = Obstacles.blocking(body, owner);
        if (block == null) {
            return false;
        }
        BlockState s = body.level().getBlockState(block);
        if (Obstacles.rippable(s) && WakeConfig.get().chaseRipsDoors) {
            door = block;
            resume = Mode.CHASE;
            set(Mode.RIP);
            return true;
        }
        if (!WakeConfig.get().chaseBreaksBlocks || broken >= WakeConfig.get().chaseBreakLimit || !Obstacles.breakable(body.level(), block, s)) {
            return false;
        }
        breaking = block;
        breakTotal = Obstacles.breakTicks(body.level(), block, s, lastNight() ? Reputation.Form.FURIOUS : Temper.form(state));
        resume = mode == Mode.SIEGE ? Mode.SIEGE : Mode.CHASE;
        set(Mode.BREAK);
        return true;
    }

    // ---- searching ------------------------------------------------------------------------

    private void search(ServerPlayer owner, HauntState state) {
        double d = body.distanceTo(owner);
        Vec3 target = lastKnown != null ? lastKnown : owner.position();
        if (modeTicks == 1) {
            BlockPos known = Temper.knownHide(state, BlockPos.containing(target), 16.0);
            if (known != null) {
                // It has found you there before.
                target = Vec3.atBottomCenterOf(known);
            }
            body.getNavigation().moveTo(target.x, target.y, target.z, 0.9);
            WakeSounds.at(owner, WakeRegistry.WET_BREATH.get(), body.position().add(0, 2.2, 0), 0.9F, 0.7F);
        }
        if (body.getNavigation().isDone() && modeTicks % 40 == 0) {
            Vec3 around = target.add(body.getRandom().nextGaussian() * 4.0, 0.0, body.getRandom().nextGaussian() * 4.0);
            body.getNavigation().moveTo(around.x, around.y, around.z, 0.7);
        }
        // Its head goes slowly from side to side.
        body.setHead(Mth.sin(modeTicks * 0.06F) * 70.0F, 10.0F);
        boolean sees = seesOwner(owner);
        boolean noisy = owner.isSprinting() || !owner.isShiftKeyDown() && owner.walkDist - owner.walkDistO > 0.05;
        if (sees && (d < 12.0 && !owner.isShiftKeyDown() || d < 5.0) || noisy && d < 18.0 || d < 2.5) {
            beginChase(owner, false);
            return;
        }
        if (modeTicks % 60 == 0) {
            WakeSounds.at(owner, WakeRegistry.WET_BREATH.get(), body.position().add(0, 2.2, 0), 0.7F, 0.75F + body.getRandom().nextFloat() * 0.1F);
        }
        if (modeTicks > 20 * 16) {
            // You hid, and it went. It will remember where.
            Temper.learn(owner, state, Reputation.Tactic.HIDE);
            Temper.rememberHide(state, owner.blockPosition());
            finish(owner, state, Pursuit.Outcome.LOST);
        }
    }

    // ---- shut in --------------------------------------------------------------------------

    /**
     * You walled yourself in. It does not hurry: first it knocks, all round the walls, and breathes
     * through them. Then one block goes, at the height of your face, and its face is in the hole.
     * Then the rest.
     */
    private void siege(ServerPlayer owner, HauntState state) {
        ServerLevel level = (ServerLevel) body.level();
        double d = body.distanceTo(owner);
        if (!Obstacles.enclosed(level, owner.blockPosition(), 70)) {
            set(Mode.CHASE);
            return;
        }
        switch (siegePhase) {
            case 0 -> {
                // Up to the wall.
                if (d > 2.6 && !body.horizontalCollision) {
                    body.getNavigation().moveTo(owner, 0.9);
                    body.setFlag(WakeEntity.FLAG_CLIMBING, body.horizontalCollision && owner.getY() > body.getY() + 1.0);
                }
                BlockPos iron = Obstacles.rippableToward(body, owner, 6);
                if (iron != null && body.position().distanceTo(Vec3.atCenterOf(iron)) > 1.8 && modeTicks < 200) {
                    // Your door: it knocks on that.
                    body.getNavigation().moveTo(iron.getX() + 0.5, iron.getY(), iron.getZ() + 0.5, 0.9);
                    return;
                }
                if (d <= 2.6 || body.horizontalCollision && modeTicks > 30 || modeTicks > 200 || iron != null) {
                    body.getNavigation().stop();
                    siegeWall = iron != null ? iron : Obstacles.wallToward(level, owner, body.position().add(0.0, 1.5, 0.0));
                    siegePhase = 1;
                    knockClock = 0;
                }
            }
            case 1 -> {
                faceOwner(owner, 0.0F);
                if (--knockClock <= 0) {
                    knockClock = 22 + body.getRandom().nextInt(30);
                    BlockPos at = siegeWall != null ? siegeWall : body.blockPosition().above();
                    Vec3 p = Vec3.atCenterOf(at);
                    if (body.getRandom().nextInt(3) == 0) {
                        WakeSounds.at(owner, WakeRegistry.BREATH_WALL.get(), p, 1.0F, 0.9F);
                    } else {
                        WakeSounds.at(owner, WakeRegistry.KNOCK.get(), p, 1.4F, 0.85F + body.getRandom().nextFloat() * 0.2F);
                    }
                    // It moves along the walls between knocks.
                    if (body.getRandom().nextInt(3) == 0) {
                        Vec3 side = owner.position().add(Vec3.directionFromRotation(0, body.getRandom().nextFloat() * 360F).scale(2.4));
                        body.getNavigation().moveTo(side.x, body.getY(), side.z, 0.6);
                    }
                }
                int knocking = switch (lastNight() ? Reputation.Form.FURIOUS : Temper.form(state)) {
                    case WATCHER, UNEASY -> 20 * 16;
                    case ANGRY -> 20 * 11;
                    case FURIOUS -> 20 * 6;
                };
                if (modeTicks > knocking) {
                    siegePhase = 2;
                    modeTicks = 0;
                }
            }
            case 2 -> {
                // The hole, at the height of your face.
                BlockPos hole = Obstacles.blocking(body, owner);
                if (hole == null) {
                    hole = siegeWall;
                }
                if (hole != null && Obstacles.rippable(level.getBlockState(hole)) && WakeConfig.get().chaseRipsDoors) {
                    // The door, then: out of its frame.
                    door = hole;
                    resume = Mode.CHASE;
                    set(Mode.RIP);
                    return;
                }
                if (hole == null || !Obstacles.breakable(level, hole, level.getBlockState(hole)) || !WakeConfig.get().chaseBreaksBlocks
                        || broken >= WakeConfig.get().chaseBreakLimit) {
                    siegePhase = 4;
                    modeTicks = 0;
                    return;
                }
                breaking = hole;
                breakTotal = Obstacles.breakTicks(level, hole, level.getBlockState(hole), Temper.form(state));
                resume = Mode.SIEGE;
                siegePhase = 3;
                set(Mode.BREAK);
            }
            case 3 -> {
                // Its face in the hole. It looks at you for a while before it does anything else.
                faceOwner(owner, 0.0F);
                body.setJaw(Math.min(30.0F, modeTicks * 0.6F));
                if (modeTicks == 2) {
                    WakeSounds.at(owner, WakeRegistry.BREATH_CLOSE.get(), body.position().add(0, 2.0, 0), 1.0F, 0.8F);
                }
                if (modeTicks > 55) {
                    // Then it makes the hole bigger.
                    if (!dealWithObstacle(owner, state)) {
                        siegePhase = 4;
                        modeTicks = 0;
                    }
                }
            }
            default -> {
                // Nothing here it can break. It waits, and then the tape skips and it is in with you,
                // if there is room, and if not, it goes, and it will remember.
                if (modeTicks > 20 * 12) {
                    Vec3 inside = Pursuit.reachableNear(owner, 1.2, 2.5);
                    if (inside != null && body.getRandom().nextBoolean()) {
                        Pursuit.tapeSkip(owner, body, inside);
                        set(Mode.CHASE);
                    } else {
                        Temper.provoke(owner, state, Temper.Provocation.CHEESE);
                        finish(owner, state, Pursuit.Outcome.GAVE_UP);
                    }
                }
            }
        }
    }

    // ---- holding you ----------------------------------------------------------------------

    private void beginGrab(ServerPlayer owner, HauntState state) {
        if (owner.isCreative() || owner.isSpectator()) {
            Possession.demand(owner, state, Possession.Why.HELD);
        }
        if (owner.getVehicle() instanceof Boat boat) {
            owner.stopRiding();
            boat.hurt(body.damageSources().mobAttack(body), 1000.0F);
        }
        owner.stopRiding();
        if (!owner.startRiding(body, true)) {
            return;
        }
        set(Mode.GRAB);
        underwater = body.isInWater();
        struggles = 0;
        wasPassenger = true;
        lastShift = owner.isShiftKeyDown();
        body.getNavigation().stop();
        WakeSounds.at(owner, WakeRegistry.GRAB.get(), owner.getEyePosition(), 1.2F, 0.9F);
        WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.HELD, 0, 0, 0, 1.0F, 1.0F));
        WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.SHAKE, 0, 0, 0, 0.8F, 14.0F));
        // Your head is turned to its face (hung forward of its body on the long neck).
        Vec3 face = body.position().add(Vec3.directionFromRotation(0.0F, body.yBodyRot).scale(0.5)).add(0.0, 3.0, 0.0);
        WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.LOOK_AT, face.x, face.y, face.z, 1.0F, 24.0F));
        Temper.grabbed(state);
    }

    private void grab(ServerPlayer owner, HauntState state) {
        boolean riding = owner.getVehicle() == body;
        if (!riding) {
            if (modeTicks < GRAB_END && owner.isAlive()) {
                struggles++;
                owner.startRiding(body, true);
            }
        }
        boolean shift = owner.isShiftKeyDown();
        if (shift && !lastShift) {
            struggles++;
        }
        lastShift = shift;
        body.setDeltaMovement(underwater ? new Vec3(0.0, -0.03, 0.0) : Vec3.ZERO);
        if (underwater) {
            owner.setAirSupply(Math.max(-19, owner.getAirSupply() - 6));
        }
        faceOwner(owner, -10.0F);
        body.setJaw(Math.min(55.0F, modeTicks * 1.4F));
        if (modeTicks < GRAB_LOOK && struggles >= STRUGGLE_FREE) {
            // You got free. It drops you, and staggers.
            owner.stopRiding();
            WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.HELD, 0, 0, 0, 0.0F, 0.0F));
            Vec3 away = owner.position().subtract(body.position()).multiply(1.0, 0.0, 1.0).normalize();
            owner.setDeltaMovement(away.x * 0.6, 0.35, away.z * 0.6);
            owner.hurtMarked = true;
            Temper.escaped(state);
            stun(owner, 40, Mode.CHASE);
            return;
        }
        if (modeTicks == GRAB_LOOK) {
            Vec3 face = body.position().add(0.0, 2.6, 0.0);
            WakeNetwork.send(owner, new AttackPayload(face.x, face.y, face.z));
        }
        if (modeTicks == GRAB_LOOK + Attack.BITE_TICK) {
            hurtOwner(owner, state);
        }
        if (modeTicks >= GRAB_END) {
            // Thrown away like the door.
            owner.stopRiding();
            WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.HELD, 0, 0, 0, 0.0F, 0.0F));
            Vec3 away = Vec3.directionFromRotation(0.0F, body.getYRot() + (body.getRandom().nextBoolean() ? 70 : -70));
            owner.setDeltaMovement(away.x * 1.1, 0.55, away.z * 1.1);
            owner.hurtMarked = true;
            caught = true;
            finish(owner, state, Pursuit.Outcome.CAUGHT);
        }
    }

    private void hurtOwner(ServerPlayer owner, HauntState state) {
        ServerLevel level = owner.serverLevel();
        float amount = lastNight() ? WakeConfig.get().chaseDamage * 1.3F : WakeConfig.get().chaseDamage;
        // The more often it has had you lately, the harder it bites.
        amount *= 1.0F + 0.2F * Math.min(2, Temper.recentCatches(state, level.getGameTime()));
        if (level.getLevelData().isHardcore() && !WakeConfig.get().attackHardcoreKills) {
            amount = Math.min(amount, owner.getHealth() - 1.0F);
        }
        if (amount <= 0.0F) {
            return;
        }
        DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(Attack.LOOKED_AWAY),
                body);
        owner.invulnerableTime = 0;
        owner.hurt(source, amount);
    }

    // ---- blinded --------------------------------------------------------------------------

    /** The flash: it cannot see, and it does not move, for a moment. */
    public void stun(ServerPlayer owner, int ticks, Mode then) {
        if (mode == Mode.GRAB) {
            owner.stopRiding();
            WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.HELD, 0, 0, 0, 0.0F, 0.0F));
        }
        stunTotal = ticks;
        resume = then;
        set(Mode.STUNNED);
        body.getNavigation().stop();
        body.setFlag(WakeEntity.FLAG_STUNNED, true);
        body.setFlag(WakeEntity.FLAG_CLIMBING, false);
        body.setFlag(WakeEntity.FLAG_HOLDING, false);
        atLedge = false;
        WakeSounds.scream(owner, WakeRegistry.SCREAM.get(), body.position().add(0.0, 2.4, 0.0), 1.25F);
    }

    public boolean stunned() {
        return mode == Mode.STUNNED;
    }

    /** Whether a flash right now would blind it: not if it shut its eyes in time. */
    public boolean eyesShut() {
        return body.hasFlag(WakeEntity.FLAG_EYES_SHUT);
    }

    private void stunned(ServerPlayer owner, HauntState state) {
        body.setDeltaMovement(new Vec3(0.0, body.getDeltaMovement().y, 0.0));
        if (modeTicks >= stunTotal) {
            body.setFlag(WakeEntity.FLAG_STUNNED, false);
            if (lastNight()) {
                LastNight.afterStun(owner, state, body);
                beginChase(owner, false);
            } else if (resume == Mode.CHASE && Temper.form(state).atLeast(Reputation.Form.ANGRY) && budget > 100) {
                beginChase(owner, false);
            } else {
                finish(owner, state, Pursuit.Outcome.STUNNED_AWAY);
            }
        }
    }

    // ---- the leap and the water ------------------------------------------------------------

    private void beginLeap(ServerPlayer owner, Vec3 target) {
        leapFrom = body.position();
        // A little past you: it lands on you, not by you.
        Vec3 dir = target.subtract(leapFrom).multiply(1.0, 0.0, 1.0);
        leapTo = target.add(dir.lengthSqr() > 1.0E-4 ? dir.normalize().scale(0.6) : Vec3.ZERO);
        double dist = leapFrom.distanceTo(leapTo);
        leapTotal = Mth.clamp((int) Math.round(dist * 1.35), lowLeap ? 6 : 10, 30);
        leapApex = lowLeap ? 0.1 : 2.0 + dist * 0.16;
        lowLeap = false;
        body.getNavigation().stop();
        body.setYRot(Spots.yawTowards(leapFrom, leapTo));
        body.yBodyRot = body.getYRot();
        set(Mode.LEAP);
        body.setFlag(WakeEntity.FLAG_SMOOTH, true);
        scream(owner, 1.1F);
    }

    private void leap(ServerPlayer owner, HauntState state) {
        double t = Math.min(1.0, modeTicks / (double) leapTotal);
        Vec3 next = leapFrom.lerp(leapTo, t).add(0.0, 4.0 * leapApex * t * (1.0 - t), 0.0);
        Vec3 delta = next.subtract(body.position());
        body.move(MoverType.SELF, delta);
        body.setDeltaMovement(Vec3.ZERO);
        if (pouncing && t > 0.6 && body.distanceTo(owner) < GRAB_RANGE && grabbable(owner)) {
            pouncing = false;
            body.setFlag(WakeEntity.FLAG_SMOOTH, false);
            beginGrab(owner, state);
            return;
        }
        if (t >= 1.0 || modeTicks > 3 && (body.horizontalCollision || body.onGround() && t > 0.5) || body.isInWater() && t > 0.4) {
            body.setFlag(WakeEntity.FLAG_SMOOTH, false);
            if (pouncing && !body.isInWater()) {
                pouncing = false;
                if (body.distanceTo(owner) < GRAB_RANGE + 0.6 && grabbable(owner)) {
                    beginGrab(owner, state);
                } else {
                    // You stepped aside. It comes down wrong, and for a moment it cannot get up.
                    stun(owner, 30, Mode.CHASE);
                }
                return;
            }
            if (body.isInWater()) {
                ServerLevel level = (ServerLevel) body.level();
                level.playSound(null, body.blockPosition(), WakeRegistry.SPLASH.get(), SoundSource.HOSTILE, 2.0F * WakeConfig.get().soundVolume, 0.8F);
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, body.getX(), body.getY() + 0.5, body.getZ(), 60, 1.0, 0.3, 1.0, 0.2);
                set(Mode.SWIM);
            } else {
                set(Mode.CHASE);
            }
            if (owner.getVehicle() instanceof Boat boat && body.distanceTo(boat) < 3.0) {
                // It came down on your boat.
                owner.stopRiding();
                boat.hurt(body.damageSources().mobAttack(body), 1000.0F);
            }
        }
    }

    private void swim(ServerPlayer owner, HauntState state) {
        if (!body.isInWater()) {
            set(Mode.CHASE);
            return;
        }
        Vec3 to = owner.position().add(0.0, 0.3, 0.0).subtract(body.position());
        double d = to.length();
        if (d < GRAB_RANGE && grabbable(owner)) {
            beginGrab(owner, state);
            return;
        }
        if (owner.getVehicle() instanceof Boat boat && d < 3.0) {
            owner.stopRiding();
            boat.hurt(body.damageSources().mobAttack(body), 1000.0F);
        }
        double speed = lastNight() ? 0.3 : 0.26;
        Vec3 step = d > 1.0E-3 ? to.scale(Math.min(speed, d) / d) : Vec3.ZERO;
        body.move(MoverType.SELF, step);
        body.setDeltaMovement(Vec3.ZERO);
        body.setYRot(Spots.yawTowards(body.position(), owner.position()));
        body.yBodyRot = body.getYRot();
        if (modeTicks % 50 == 1) {
            WakeSounds.at(owner, WakeRegistry.UNDERWATER_ROAR.get(), body.position(), 1.5F, 0.8F + body.getRandom().nextFloat() * 0.2F);
        }
    }

    // ---- doors and walls -------------------------------------------------------------------

    private void rip(ServerPlayer owner, HauntState state) {
        if (door == null || !Obstacles.rippable(body.level().getBlockState(door))) {
            door = null;
            set(resume);
            return;
        }
        Vec3 at = Vec3.atBottomCenterOf(door);
        double d = Math.hypot(at.x - body.getX(), at.z - body.getZ());
        if (d > 1.6 && modeTicks < 40) {
            body.getNavigation().moveTo(at.x, at.y, at.z, 1.1);
            return;
        }
        body.getNavigation().stop();
        body.setYRot(Spots.yawTowards(body.position(), at));
        body.yBodyRot = body.getYRot();
        if (modeTicks == 2 || modeTicks == 42) {
            body.level().playSound(null, door, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.4F, 0.5F);
            WakeSounds.at(owner, WakeRegistry.CRACK.get(), body.position().add(0, 2, 0), 1.0F, 0.7F);
        }
        if (modeTicks >= 52 || d <= 1.6 && modeTicks >= 14) {
            Vec3 facing = Vec3.directionFromRotation(0.0F, body.getYRot());
            Vec3 aside = new Vec3(-facing.z, 0.0, facing.x).scale(body.getRandom().nextBoolean() ? 1.0 : -1.0).add(facing.scale(-0.4));
            Obstacles.rip(body, door, aside);
            Temper.provoke(owner, state, Temper.Provocation.CHEESE, 0.5F);
            door = null;
            set(resume);
            stuckTicks = 0;
        }
    }

    private void breakBlock(ServerPlayer owner, HauntState state) {
        ServerLevel level = (ServerLevel) body.level();
        if (breaking == null) {
            set(resume);
            return;
        }
        BlockState s = level.getBlockState(breaking);
        if (!Obstacles.breakable(level, breaking, s)) {
            level.destroyBlockProgress(body.getId(), breaking, -1);
            breaking = null;
            set(resume);
            return;
        }
        body.getNavigation().stop();
        Vec3 c = Vec3.atCenterOf(breaking);
        body.setYRot(Spots.yawTowards(body.position(), c));
        body.yBodyRot = body.getYRot();
        int stage = Mth.clamp(modeTicks * 10 / Math.max(1, breakTotal), 0, 9);
        level.destroyBlockProgress(body.getId(), breaking, stage);
        if (modeTicks % 7 == 1) {
            level.playSound(null, breaking, s.getSoundType().getHitSound(), SoundSource.BLOCKS, 1.4F, 0.6F);
            level.playSound(null, breaking, WakeRegistry.BREAK.get(), SoundSource.HOSTILE, 1.6F * WakeConfig.get().soundVolume,
                    0.8F + body.getRandom().nextFloat() * 0.3F);
        }
        if (modeTicks >= breakTotal) {
            level.destroyBlockProgress(body.getId(), breaking, -1);
            level.destroyBlock(breaking, true, body);
            broken++;
            Temper.provoke(owner, state, Temper.Provocation.CHEESE, 0.25F);
            breaking = null;
            stuckTicks = 0;
            set(resume);
        }
    }

    // ---- going -----------------------------------------------------------------------------

    /** The chase is over without it catching you. */
    private void giveUp(ServerPlayer owner, HauntState state) {
        if (mode == Mode.CHASE || mode == Mode.SEARCH || mode == Mode.BREAK || mode == Mode.RIP || mode == Mode.SWIM) {
            Temper.learn(owner, state, Reputation.Tactic.OUTRUN);
            finish(owner, state, Pursuit.Outcome.ESCAPED);
        } else {
            finish(owner, state, Pursuit.Outcome.GAVE_UP);
        }
    }

    private void startLeaving(ServerPlayer owner) {
        set(Mode.LEAVE);
        body.getNavigation().stop();
    }

    private void leave(ServerPlayer owner, HauntState state) {
        body.setDeltaMovement(new Vec3(0.0, body.getDeltaMovement().y, 0.0));
        // It goes the moment you look away; if you will not, it goes anyway, in a burst of static.
        if (unseenTicks > 2 || modeTicks > 60) {
            finish(owner, state, Pursuit.Outcome.GAVE_UP);
        }
    }

    private void finish(ServerPlayer owner, HauntState state, Pursuit.Outcome outcome) {
        if (ended) {
            return;
        }
        ended = true;
        if (owner.getVehicle() == body) {
            owner.stopRiding();
        }
        if (breaking != null) {
            body.level().destroyBlockProgress(body.getId(), breaking, -1);
        }
        body.setFlag(WakeEntity.FLAG_LEAVING, true);
        Pursuit.ended(owner, state, body, outcome);
    }

    /** Its owner left the game or the dimension in the middle of it. */
    void ownerGone() {
        if (!ended) {
            ended = true;
            Pursuit.ownerGone(ownerId, body, mode == Mode.CHASE || mode == Mode.SEARCH || mode == Mode.SIEGE || mode == Mode.GRAB);
        }
    }

    void onKillAttempt(ServerPlayer owner) {
        // Somewhere else, at once, and closer.
        Vec3 near = Pursuit.reachableNear(owner, 5.0, 9.0);
        if (near != null) {
            Pursuit.tapeSkip(owner, body, near);
        }
        if (mode == Mode.STALK || mode == Mode.LURK) {
            beginChase(owner, false);
        }
    }

    void onHurt(ServerPlayer owner, DamageSource source, float amount) {
        HauntState state = Haunt.state(owner);
        long now = owner.level().getGameTime();
        if (now - state.reputation().lastHurtTick() > 10) {
            Temper.provoke(owner, state, Temper.Provocation.HIT);
            Temper.learn(owner, state, Reputation.Tactic.HURT);
        }
        Temper.hurtAt(state, now);
        WakeSounds.at(owner, WakeRegistry.CRACK.get(), body.position().add(0, 2.0, 0), 1.0F, 0.6F);
        if (mode == Mode.STALK || mode == Mode.LURK || mode == Mode.SEARCH || mode == Mode.LEAVE) {
            // You should not have done that.
            beginChase(owner, false);
        } else if (mode == Mode.SLEEP) {
            wake(owner, state);
        }
    }

    /** Right-click on it. Only while it is blinded, or asleep: its pins. */
    boolean onInteract(ServerPlayer owner, InteractionHand hand) {
        HauntState state = Haunt.state(owner);
        if (body.distanceTo(owner) > 3.6) {
            return false;
        }
        ItemStack held = owner.getItemInHand(hand);
        if (!held.isEmpty() && !held.is(WakeContent.PIN.get())) {
            return false;
        }
        if (mode == Mode.STUNNED && (lastNight() || Temper.form(state).atLeast(Reputation.Form.UNEASY))) {
            LastNight.pullPin(owner, state, body, false);
            return true;
        }
        if (mode == Mode.SLEEP) {
            LastNight.pullPin(owner, state, body, true);
            return true;
        }
        return false;
    }

    // ---- asleep in the rerun ----------------------------------------------------------------

    private void sleep(ServerPlayer owner, HauntState state) {
        body.getNavigation().stop();
        body.setDeltaMovement(Vec3.ZERO);
        // It wakes if you are loud near it: running, jumping, hitting things.
        double d = body.distanceTo(owner);
        boolean loud = owner.isSprinting() || !owner.onGround() && owner.getDeltaMovement().y > 0.1 || owner.swinging && d < 3.0;
        if (loud && d < 9.0 && !owner.isShiftKeyDown() || modeTicks > 20 * 60 * 4) {
            wake(owner, state);
        }
    }

    public void wake(ServerPlayer owner, HauntState state) {
        purpose = Purpose.CHASE;
        budget = 20 * 40;
        body.setNoGravity(false);
        beginChase(owner, false);
    }

    // ---- looking and drawing -----------------------------------------------------------------

    private void faceOwner(ServerPlayer owner, float pitchBias) {
        Vec3 face = body.position().add(0.0, 2.6, 0.0);
        Vec3 to = owner.getEyePosition().subtract(face);
        float toYaw = Spots.yawTowards(body.position(), owner.position());
        float rel = Mth.clamp(Mth.wrapDegrees(toYaw - body.yBodyRot), -120.0F, 120.0F);
        float pitch = (float) Math.toDegrees(Math.atan2(-to.y, Math.max(0.2, to.horizontalDistance())));
        body.setHead(rel, Mth.clamp(pitch + pitchBias, -60.0F, 70.0F));
    }

    private void scream(ServerPlayer owner, float pitch) {
        WakeSounds.scream(owner, WakeRegistry.CHASE_SCREAM.get(), body.position().add(0.0, 2.6, 0.0), pitch);
    }

    /** Picks the clip and frame for what it is doing, and its footfalls. */
    private void animate(ServerPlayer owner) {
        CreaturePose clip;
        int frame = 0;
        boolean moving = body.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4 || body.getNavigation().isInProgress();
        switch (mode) {
            case GRAB -> clip = CreaturePose.HOLD;
            case STUNNED -> {
                clip = CreaturePose.STUNNED;
                frame = Math.min(clip.frames - 1, modeTicks / 3);
            }
            case LEAP -> {
                clip = CreaturePose.LEAP;
                frame = Math.min(clip.frames - 1, modeTicks * clip.frames / Math.max(1, leapTotal));
            }
            case SWIM -> {
                clip = CreaturePose.SWIM;
                frame = body.strideFrame(clip);
            }
            case RIP -> {
                clip = modeTicks < 14 ? CreaturePose.REACH : CreaturePose.THROW;
                frame = Math.min(CreaturePose.THROW.frames - 1, Math.max(0, modeTicks - 14) / 2);
            }
            case BREAK -> {
                clip = CreaturePose.THROW;
                frame = (modeTicks / 2) % clip.frames;
            }
            case SLEEP -> clip = CreaturePose.LIE;
            case AMBUSH -> clip = ambushKind == Ambush.Kind.DOOR || ambushKind == Ambush.Kind.MARK ? CreaturePose.STAND
                    : ambushKind == Ambush.Kind.CEILING ? CreaturePose.LEDGE : CreaturePose.CROUCH;
            case POUNCE -> clip = CreaturePose.LEAP;
            case TAG -> clip = CreaturePose.REACH;
            case LEAVE -> clip = body.clip();
            case LURK -> {
                if (atLedge) {
                    clip = CreaturePose.LEDGE;
                } else if (body.onClimbable()) {
                    clip = CreaturePose.CLIMB;
                    frame = body.strideFrame(clip);
                } else {
                    clip = moving ? CreaturePose.WALK : CreaturePose.STAND;
                    frame = body.strideFrame(clip);
                }
            }
            case SIEGE -> {
                if (siegePhase == 1 && !moving && body.form() == Reputation.Form.UNEASY) {
                    // The small hand on your wall: three, a pause, one.
                    clip = CreaturePose.KNOCK;
                    frame = (modeTicks / 2) % clip.frames;
                } else {
                    clip = siegePhase == 3 ? CreaturePose.REACH : moving ? CreaturePose.WALK : CreaturePose.STAND;
                }
            }
            case PICK -> {
                clip = CreaturePose.PICK;
                frame = Math.min(clip.frames - 1, modeTicks / 2);
            }
            case REACH -> {
                clip = reachClip;
                frame = Math.min(clip.frames - 1, modeTicks * clip.frames / 20);
            }
            default -> {
                if (body.onClimbable() && body.horizontalCollision) {
                    clip = CreaturePose.CLIMB;
                    frame = body.strideFrame(clip);
                } else if (moving) {
                    int room = headroom();
                    clip = room <= 2 ? CreaturePose.CRAWL : room == 3 ? CreaturePose.STOOP_WALK
                            : mode == Mode.CHASE ? CreaturePose.RUN : CreaturePose.WALK;
                    frame = body.strideFrame(clip);
                } else {
                    clip = headroom() <= 2 ? CreaturePose.CROUCH : mode == Mode.STALK ? CreaturePose.STAND : CreaturePose.REACH;
                }
            }
        }
        body.show(clip, frame);
        // Eyes shut: it has learned what the camera does.
        body.setFlag(WakeEntity.FLAG_EYES_SHUT, mode == Mode.STUNNED || mode == Mode.SLEEP || shutsEyes(owner));
        int f = body.frame();
        if (clip.isMoving() && f != lastFrame && (f == 0 || f == clip.frames / 2)) {
            float volume = mode == Mode.CHASE ? 1.2F : mode == Mode.STALK || mode == Mode.LURK ? 0.25F : 0.7F;
            if (clip == CreaturePose.CLIMB) {
                WakeSounds.at(owner, WakeRegistry.CLIMB.get(), body.position().add(0, 1.0, 0), volume, 0.9F + body.getRandom().nextFloat() * 0.2F);
            } else if (clip != CreaturePose.SWIM && !(mode == Mode.LURK)) {
                WakeSounds.at(owner, mode == Mode.CHASE ? WakeRegistry.RUN.get() : WakeRegistry.STEP.get(), body.position(), volume,
                        0.85F + body.getRandom().nextFloat() * 0.25F);
            }
        }
        lastFrame = f;
        if (mode != Mode.CHASE && mode != Mode.SEARCH && mode != Mode.GRAB && mode != Mode.SIEGE && mode != Mode.LURK) {
            body.setJaw(mode == Mode.STUNNED ? 40.0F : 0.0F);
        } else if (mode == Mode.CHASE) {
            body.setJaw(20.0F + Mth.sin(life * 0.3F) * 8.0F);
        }
    }

    /** It learned the camera: when you raise it, it closes its eyes, and runs at you by ear. */
    private boolean shutsEyes(ServerPlayer owner) {
        if (mode != Mode.CHASE) {
            return false;
        }
        HauntState state = Haunt.state(owner);
        int flashes = state.reputation().tactic(Reputation.Tactic.FLASH);
        if (flashes < 2 || !owner.isHolding(WakeContent.OLD_CAMERA.get())) {
            return false;
        }
        // The more it has been blinded the sooner it shuts them; never all the time.
        return (life / 20) % 5 < Math.min(3, flashes - 1);
    }

    private int headroom() {
        BlockPos feet = body.blockPosition();
        int room = 0;
        for (int up = 0; up < 4; up++) {
            BlockPos p = feet.above(up);
            if (!body.level().getBlockState(p).getCollisionShape(body.level(), p).isEmpty()) {
                break;
            }
            room++;
        }
        return room;
    }

    /** Development: put it on the ledge behind {@code owner} at once. */
    public void debugLedge(ServerPlayer owner) {
        lookout = owner.blockPosition();
        atLedge = true;
        set(Mode.LURK);
        body.setFlag(WakeEntity.FLAG_CLIMBING, true);
        body.setNoGravity(true);
    }

    @Override
    public String toString() {
        return "WakeBrain[" + mode + ", " + purpose + ", life " + life + "]";
    }

    /** For a log line. */
    void debug(String what) {
        TheWake.LOG.debug("[wake {}] {} {}", body.getId(), mode, what);
    }

    /** Unused: kept so the entity can ask whether something is its own body. */
    static boolean isBody(@Nullable Entity e) {
        return e instanceof WakeEntity;
    }

    private static final class Spots {
        static float yawTowards(Vec3 from, Vec3 to) {
            return io.github.jitbyjones.thewake.haunt.Spots.yawTowards(from, to);
        }
    }
}
