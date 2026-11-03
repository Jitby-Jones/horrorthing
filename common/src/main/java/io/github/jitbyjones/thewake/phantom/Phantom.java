package io.github.jitbyjones.thewake.phantom;

import io.github.jitbyjones.thewake.creature.CreaturePose;
import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One appearance of the creature, as the server tracks it. Only its owner is ever told it exists,
 * and only their client draws it: no entity, no radar dot, nothing for anyone else to see or hit.
 *
 * <p>It never moves while its owner is watching the world. Everything that changes it happens in
 * a lapse, and the client shows each change as a cut, not a motion: stop-motion.
 */
public final class Phantom {
    public enum Mode { STILL, WALKER, SLEEPER }

    /** Lit as if by a flash, whatever the light around it. */
    public static final int FLAG_LIT = 1;
    /** The server is turning the head; the client must not add its own looking or twitching. */
    public static final int FLAG_SCRIPTED_HEAD = 2;
    /** Never twitches, even in the corner of the eye. */
    public static final int FLAG_NO_TWITCH = 4;

    final int id;
    Vec3 pos;
    float yaw;
    Mode mode;
    CreaturePose pose;
    float headYaw;
    float headPitch;
    float jaw;
    float scale = 1.0F;
    int flags;
    int bedDirection = -1;
    net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;

    int life = -1;
    int lapseGeneration = -1;
    double touchRadius = 1.0;
    boolean touchCostsTime = true;
    @Nullable
    PhantomBehavior behavior;

    boolean dirty = true;
    boolean added;
    boolean removed;
    int dissolveTicks = -1;

    Phantom(int id, Vec3 pos, float yaw, Mode mode, CreaturePose pose) {
        this.id = id;
        this.pos = pos;
        this.yaw = yaw;
        this.mode = mode;
        this.pose = pose;
    }

    public int id() { return id; }
    public Vec3 pos() { return pos; }
    public float yaw() { return yaw; }
    public Mode mode() { return mode; }
    public CreaturePose pose() { return pose; }
    public float scale() { return scale; }
    public float headYaw() { return headYaw; }
    public boolean isRemoved() { return removed; }
    public boolean isDissolving() { return dissolveTicks >= 0; }

    public Phantom moveTo(Vec3 pos, float yaw) {
        this.pos = pos;
        this.yaw = yaw;
        dirty = true;
        return this;
    }

    public Phantom setYaw(float yaw) {
        if (Math.abs(Mth.wrapDegrees(yaw - this.yaw)) > 0.5F) {
            this.yaw = yaw;
            dirty = true;
        }
        return this;
    }

    public Phantom setMode(Mode mode) {
        this.mode = mode;
        dirty = true;
        return this;
    }

    public Phantom setPose(CreaturePose pose) {
        if (this.pose != pose) {
            this.pose = pose;
            dirty = true;
        }
        return this;
    }

    /** Degrees relative to the body. Past about 75 degrees no neck should go; this one does. */
    public Phantom setHead(float yawOffset, float pitch) {
        this.headYaw = yawOffset;
        this.headPitch = pitch;
        dirty = true;
        return this;
    }

    /** Extra jaw opening in degrees, on top of the pose's own. */
    public Phantom setJaw(float degrees) {
        this.jaw = degrees;
        dirty = true;
        return this;
    }

    public Phantom setScale(float scale) {
        this.scale = scale;
        dirty = true;
        return this;
    }

    public Phantom setFlags(int flags) {
        this.flags = flags;
        dirty = true;
        return this;
    }

    public Phantom setBedDirection(int direction2d) {
        this.bedDirection = direction2d;
        dirty = true;
        return this;
    }

    public Phantom setLife(int ticks) {
        this.life = ticks;
        return this;
    }

    /** Survives until the owner's lapse counter passes {@code generation}; -1 = forever. */
    public Phantom setLapseGeneration(int generation) {
        this.lapseGeneration = generation;
        return this;
    }

    public Phantom setTouch(double radius, boolean costsTime) {
        this.touchRadius = radius;
        this.touchCostsTime = costsTime;
        return this;
    }

    public Phantom setBehavior(@Nullable PhantomBehavior behavior) {
        this.behavior = behavior;
        return this;
    }
}
