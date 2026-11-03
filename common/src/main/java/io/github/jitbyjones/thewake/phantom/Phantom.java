package io.github.jitbyjones.thewake.phantom;

import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One image of the Wake, as the server tracks it. Only its owner is ever told it exists, and only
 * their client draws it: no entity, no radar dot, nothing for anyone else to see or kill.
 *
 * <p>Three photographic signals with three meanings: {@link #opacity} is how long it stood there,
 * {@link #smear} is that it was moving, {@link #focus} is how well it knows you.
 */
public final class Phantom {
    public enum Mode { EXPOSURE, WALKER, SLEEPER }

    public enum Stance { STAND, LOOK_DOWN, WALK, LIE, FACE_AWAY }

    final int id;
    Vec3 pos;
    float yaw;
    Mode mode;
    Stance stance;
    float opacity;
    float smear;
    float focus;
    float headYaw;
    float headPitch;
    float headSmear;
    int bedDirection = -1;
    float fadeSpeed = 1.0F;

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

    Phantom(int id, Vec3 pos, float yaw, Mode mode, Stance stance) {
        this.id = id;
        this.pos = pos;
        this.yaw = yaw;
        this.mode = mode;
        this.stance = stance;
    }

    public int id() { return id; }
    public Vec3 pos() { return pos; }
    public float yaw() { return yaw; }
    public Mode mode() { return mode; }
    public Stance stance() { return stance; }
    public float opacity() { return opacity; }
    public float smear() { return smear; }
    public float focus() { return focus; }
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

    public Phantom setStance(Stance stance) {
        this.stance = stance;
        dirty = true;
        return this;
    }

    public Phantom setOpacity(float opacity) {
        this.opacity = Mth.clamp(opacity, 0.0F, 1.0F);
        dirty = true;
        return this;
    }

    public Phantom setSmear(float smear) {
        this.smear = Mth.clamp(smear, 0.0F, 1.0F);
        dirty = true;
        return this;
    }

    public Phantom setFocus(float focus) {
        this.focus = Mth.clamp(focus, 0.0F, 1.0F);
        dirty = true;
        return this;
    }

    /** Degrees relative to the body. Past about 75 degrees no neck should go. */
    public Phantom setHead(float yawOffset, float pitch, float smear) {
        this.headYaw = yawOffset;
        this.headPitch = pitch;
        this.headSmear = Mth.clamp(smear, 0.0F, 1.0F);
        dirty = true;
        return this;
    }

    public Phantom setBedDirection(int direction2d) {
        this.bedDirection = direction2d;
        dirty = true;
        return this;
    }

    /** Fraction of the remaining opacity difference the client closes per tick. */
    public Phantom setFadeSpeed(float perTick) {
        this.fadeSpeed = Mth.clamp(perTick, 0.002F, 1.0F);
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
