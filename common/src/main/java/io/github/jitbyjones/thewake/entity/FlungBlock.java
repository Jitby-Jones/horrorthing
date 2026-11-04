package io.github.jitbyjones.thewake.entity;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A door it tore out, in the air. It turns over as it flies, lands with a sound you feel, lies on
 * the ground a moment, and then it is only an iron door item in the grass.
 */
public class FlungBlock extends Entity {
    private static final EntityDataAccessor<BlockState> DATA_LOWER = SynchedEntityData.defineId(FlungBlock.class,
            EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<BlockState> DATA_UPPER = SynchedEntityData.defineId(FlungBlock.class,
            EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Float> DATA_SPIN = SynchedEntityData.defineId(FlungBlock.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_LANDED = SynchedEntityData.defineId(FlungBlock.class, EntityDataSerializers.BOOLEAN);

    private int landedTicks;
    public float roll;
    public float prevRoll;

    public FlungBlock(EntityType<? extends FlungBlock> type, Level level) {
        super(type, level);
    }

    public FlungBlock(Level level, Vec3 at, BlockState lower, BlockState upper) {
        this(WakeContent.FLUNG_BLOCK.get(), level);
        setPos(at.x, at.y, at.z);
        entityData.set(DATA_LOWER, lower);
        entityData.set(DATA_UPPER, upper);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_LOWER, Blocks.IRON_DOOR.defaultBlockState());
        builder.define(DATA_UPPER, Blocks.AIR.defaultBlockState());
        builder.define(DATA_SPIN, 0.0F);
        builder.define(DATA_LANDED, false);
    }

    public BlockState lower() {
        return entityData.get(DATA_LOWER);
    }

    public BlockState upper() {
        return entityData.get(DATA_UPPER);
    }

    public boolean landed() {
        return entityData.get(DATA_LANDED);
    }

    void setSpin(float degreesPerTick) {
        entityData.set(DATA_SPIN, degreesPerTick);
    }

    @Override
    public void tick() {
        super.tick();
        prevRoll = roll;
        if (!landed()) {
            roll += entityData.get(DATA_SPIN);
            Vec3 v = getDeltaMovement().add(0.0, -0.06, 0.0);
            setDeltaMovement(v);
            move(MoverType.SELF, v);
            setDeltaMovement(getDeltaMovement().scale(0.985));
            if ((onGround() || horizontalCollision) && tickCount > 2) {
                land();
            }
            if (tickCount > 200) {
                land();
            }
        } else {
            // Settles flat.
            float target = Math.round(roll / 180.0F) * 180.0F + 90.0F;
            roll += (target - roll) * 0.4F;
            if (!level().isClientSide && ++landedTicks > 70) {
                drop();
            }
        }
    }

    private void land() {
        if (level().isClientSide) {
            return;
        }
        entityData.set(DATA_LANDED, true);
        setDeltaMovement(Vec3.ZERO);
        ServerLevel level = (ServerLevel) level();
        level.playSound(null, blockPosition(), WakeRegistry.METAL_IMPACT.get(), SoundSource.BLOCKS, 2.0F * WakeConfig.get().soundVolume,
                0.8F + random.nextFloat() * 0.2F);
        level.playSound(null, blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.5F, 0.5F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, lower()), getX(), getY() + 0.2, getZ(), 24, 0.6, 0.1, 0.6, 0.1);
        level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.1, getZ(), 6, 0.5, 0.05, 0.5, 0.01);
    }

    private void drop() {
        BlockState state = lower();
        ItemStack stack = new ItemStack(state.getBlock().asItem());
        if (!stack.isEmpty()) {
            ItemEntity item = new ItemEntity(level(), getX(), getY() + 0.2, getZ(), stack);
            item.setDefaultPickUpDelay();
            level().addFreshEntity(item);
        }
        discard();
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        // If the world closes with it in the air, it lands as an item instead.
        return false;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide && reason == RemovalReason.UNLOADED_TO_CHUNK && !isRemoved()) {
            drop();
            return;
        }
        super.remove(reason);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Lower")) {
            entityData.set(DATA_LOWER, NbtUtils.readBlockState(level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    tag.getCompound("Lower")));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Lower", NbtUtils.writeBlockState(lower()));
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    public BlockPos landingPos() {
        return blockPosition();
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.0;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) {
        return true;
    }

    /** For a renderer: the rotation this frame. */
    public float roll(float partial) {
        return prevRoll + (roll - prevRoll) * partial;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    @Override
    public boolean isCurrentlyGlowing() {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    /** Unused hook kept for symmetry with vanilla falling blocks. */
    public Entity self() {
        return this;
    }
}
