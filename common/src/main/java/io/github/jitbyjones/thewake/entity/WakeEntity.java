package io.github.jitbyjones.thewake.entity;

import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.haunt.Reputation;
import io.github.jitbyjones.thewake.haunt.Temper;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

/**
 * Its body. Most of the time the creature is only ever an image (see {@code Phantom}): nothing you
 * can touch, nothing that moves while you watch. When it is angry and you are afraid enough, it
 * comes after you in this: a real thing with a hitbox, that climbs walls, digs through them, rips
 * doors out and throws them, and goes into the water after you.
 *
 * <p>It belongs to one player. Only that player's client is ever told it exists, so on a server
 * nobody else sees it, hears it or bumps into it. It is never saved: if the world closes mid-chase,
 * the chase is remembered in the haunt state and picks up again when you come back.
 */
public class WakeEntity extends PathfinderMob {
    public static final int FLAG_CLIMBING = 1;
    public static final int FLAG_STUNNED = 2;
    /** Draw it where it really is, every frame (a leap): otherwise it is drawn in stop-motion. */
    public static final int FLAG_SMOOTH = 4;
    public static final int FLAG_EYES_SHUT = 8;
    public static final int FLAG_LEAVING = 16;
    /** Held still on the wall or the ledge. */
    public static final int FLAG_HOLDING = 32;

    private static final EntityDataAccessor<Byte> DATA_CLIP = SynchedEntityData.defineId(WakeEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_FRAME = SynchedEntityData.defineId(WakeEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_FLAGS = SynchedEntityData.defineId(WakeEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_FORM = SynchedEntityData.defineId(WakeEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_PINS = SynchedEntityData.defineId(WakeEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_HEAD_YAW = SynchedEntityData.defineId(WakeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEAD_PITCH = SynchedEntityData.defineId(WakeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_JAW = SynchedEntityData.defineId(WakeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(WakeEntity.class,
            EntityDataSerializers.OPTIONAL_UUID);

    @Nullable
    private WakeBrain brain;
    /** Ground covered since it was made, for the walk cycle. */
    double travelled;

    // Client side only: where it was last drawn, for stop-motion.
    @Nullable
    public Vec3 shownPos;
    public float shownYaw;
    public int shownFrame = -1;
    public int shownClip = -1;
    public int clientAge;

    public WakeEntity(EntityType<? extends WakeEntity> type, Level level) {
        super(type, level);
        this.xpReward = 0;
        this.setPersistenceRequired();
        this.setPathfindingMalus(PathType.WATER, 0.0F);
        this.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
        this.setPathfindingMalus(PathType.DOOR_WOOD_CLOSED, 0.0F);
        this.setPathfindingMalus(PathType.FENCE, -1.0F);
        this.setPathfindingMalus(PathType.LEAVES, 2.0F);
        this.setPathfindingMalus(PathType.DANGER_FIRE, 0.0F);
        this.setPathfindingMalus(PathType.DAMAGE_FIRE, 0.0F);
        this.setPathfindingMalus(PathType.POWDER_SNOW, 0.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 400.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 96.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 1.05)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.SAFE_FALL_DISTANCE, 64.0)
                .add(Attributes.WATER_MOVEMENT_EFFICIENCY, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CLIP, (byte) CreaturePose.STAND.ordinal());
        builder.define(DATA_FRAME, (byte) 0);
        builder.define(DATA_FLAGS, (byte) 0);
        builder.define(DATA_FORM, (byte) 0);
        builder.define(DATA_PINS, (byte) 0);
        builder.define(DATA_HEAD_YAW, 0.0F);
        builder.define(DATA_HEAD_PITCH, 0.0F);
        builder.define(DATA_JAW, 0.0F);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        WallClimberNavigation nav = new WallClimberNavigation(this, level);
        nav.setCanOpenDoors(true);
        nav.setCanFloat(false);
        return nav;
    }

    // ---- ownership ------------------------------------------------------------------------

    public void setOwner(ServerPlayer player) {
        entityData.set(DATA_OWNER, Optional.of(player.getUUID()));
    }

    @Nullable
    public UUID ownerId() {
        return entityData.get(DATA_OWNER).orElse(null);
    }

    @Nullable
    public ServerPlayer owner() {
        UUID id = ownerId();
        if (id == null || !(level() instanceof ServerLevel server)) {
            return null;
        }
        ServerPlayer player = server.getServer().getPlayerList().getPlayer(id);
        return player != null && player.level() == level() ? player : null;
    }

    public boolean isOwner(@Nullable Entity entity) {
        return entity != null && entity.getUUID().equals(ownerId());
    }

    /** Only its owner is ever told it is there. */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return isOwner(player);
    }

    @Nullable
    public WakeBrain brain() {
        return brain;
    }

    public WakeBrain brain(ServerPlayer owner) {
        if (brain == null) {
            brain = new WakeBrain(this, owner.getUUID());
        }
        return brain;
    }

    // ---- what the client draws ------------------------------------------------------------

    public CreaturePose clip() {
        return CreaturePose.byId(entityData.get(DATA_CLIP));
    }

    public int frame() {
        return entityData.get(DATA_FRAME);
    }

    public int flags() {
        return entityData.get(DATA_FLAGS);
    }

    public boolean hasFlag(int flag) {
        return (flags() & flag) != 0;
    }

    public void setFlag(int flag, boolean on) {
        int f = flags();
        int next = on ? f | flag : f & ~flag;
        if (next != f) {
            entityData.set(DATA_FLAGS, (byte) next);
        }
    }

    public Reputation.Form form() {
        return Reputation.Form.values()[Mth.clamp(entityData.get(DATA_FORM), 0, Reputation.Form.values().length - 1)];
    }

    public void setForm(Reputation.Form form) {
        entityData.set(DATA_FORM, (byte) form.ordinal());
    }

    public int pins() {
        return entityData.get(DATA_PINS);
    }

    public void setPins(int pins) {
        entityData.set(DATA_PINS, (byte) Mth.clamp(pins, 0, 4));
    }

    public float headYaw() {
        return entityData.get(DATA_HEAD_YAW);
    }

    public float headPitch() {
        return entityData.get(DATA_HEAD_PITCH);
    }

    public float jaw() {
        return entityData.get(DATA_JAW);
    }

    public void setHead(float yaw, float pitch) {
        if (Math.abs(headYaw() - yaw) > 0.5F) {
            entityData.set(DATA_HEAD_YAW, yaw);
        }
        if (Math.abs(headPitch() - pitch) > 0.5F) {
            entityData.set(DATA_HEAD_PITCH, pitch);
        }
    }

    public void setJaw(float degrees) {
        if (Math.abs(jaw() - degrees) > 0.5F) {
            entityData.set(DATA_JAW, degrees);
        }
    }

    public void show(CreaturePose clip, int frame) {
        byte c = (byte) clip.ordinal();
        byte f = (byte) Math.floorMod(frame, Math.max(1, clip.frames));
        if (entityData.get(DATA_CLIP) != c) {
            entityData.set(DATA_CLIP, c);
        }
        if (entityData.get(DATA_FRAME) != f) {
            entityData.set(DATA_FRAME, f);
        }
    }

    /** The frame of a moving clip for the ground it has covered. */
    public int strideFrame(CreaturePose clip) {
        return (int) Math.floor(travelled / clip.stride() * clip.frames);
    }

    // ---- ticking --------------------------------------------------------------------------

    @Override
    public void tick() {
        Vec3 before = position();
        super.tick();
        if (level().isClientSide) {
            clientAge++;
            return;
        }
        travelled += position().subtract(before).horizontalDistance() + Math.abs(getY() - before.y) * 0.8;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        ServerPlayer owner = owner();
        if (owner == null || brain == null) {
            if (brain != null) {
                brain.ownerGone();
            }
            discard();
            return;
        }
        brain.tick(owner);
    }

    @Override
    public boolean onClimbable() {
        return hasFlag(FLAG_CLIMBING);
    }

    @Override
    public void checkDespawn() {
        // Peaceful does not send it away. Neither does distance: it goes when it decides to.
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    @Override
    public boolean isSensitiveToWater() {
        return false;
    }


    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    protected boolean canRide(Entity vehicle) {
        return false;
    }

    @Override
    public boolean dampensVibrations() {
        return true;
    }

    @Override
    public boolean canBeHitByProjectile() {
        return true;
    }

    @Override
    public boolean isPickable() {
        return !hasFlag(FLAG_LEAVING);
    }

    // ---- hurting it -----------------------------------------------------------------------

    /**
     * Commands do not kill it. {@code /kill} makes it angrier and it is somewhere else; weapons
     * stagger it, and it remembers who did it.
     */
    @Override
    public void kill() {
        ServerPlayer owner = owner();
        if (owner != null && brain != null) {
            HauntState state = Haunt.state(owner);
            Temper.provoke(owner, state, Temper.Provocation.CHEAT);
            Temper.learn(owner, state, Reputation.Tactic.CHEAT);
            brain.onKillAttempt(owner);
        }
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_DROWNING)
                || source.is(DamageTypeTags.IS_FREEZING) || source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypeTags.WITCH_RESISTANT_TO)
                || source.is(DamageTypeTags.IS_LIGHTNING)) {
            return true;
        }
        return super.isInvulnerableTo(source) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || hasFlag(FLAG_LEAVING)) {
            return false;
        }
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && source.getEntity() == null) {
            // /kill, the void: it is not that kind of thing.
            kill();
            return false;
        }
        if (isInvulnerableTo(source)) {
            return false;
        }
        Entity attacker = source.getEntity();
        ServerPlayer owner = owner();
        if (owner != null && brain != null && attacker == owner) {
            brain.onHurt(owner, source, amount);
            // It flinches. It does not fall.
            playHurtSound(source);
            this.hurtTime = this.hurtDuration = 10;
            return true;
        }
        return false;
    }

    @Override
    protected void actuallyHurt(DamageSource source, float amount) {
        // Its health is not something you can take away.
    }

    // ---- touching it ----------------------------------------------------------------------

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer && isOwner(player) && brain != null) {
            return brain.onInteract(serverPlayer, hand) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    // ---- holding you ----------------------------------------------------------------------

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && isOwner(passenger);
    }

    /**
     * In its hands, at arm's length, your eyes just under its card; under water, down by its chest.
     * Worked out from what both sides know (its yaw, the water), because each side places its own
     * rider: the client must agree with the server about where you are held.
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity entity, EntityDimensions dimensions, float partialTick) {
        boolean under = isInWater();
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        double reach = under ? 0.8 : 1.25;
        // The rider's feet are 0.6 below this point.
        double height = under ? 0.2 : 1.72;
        return new Vec3(-Mth.sin(yaw) * reach, height, Mth.cos(yaw) * reach);
    }

    /** Its health is not a bar under yours while it holds you. */
    @Override
    public boolean showVehicleHealth() {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        // Never saved (see shouldBeSaved).
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isSilent() {
        // Its sounds are its own, sent to its owner alone.
        return true;
    }
}
