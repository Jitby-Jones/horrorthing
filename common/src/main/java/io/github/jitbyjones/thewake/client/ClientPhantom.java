package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.net.PhantomPayload;
import io.github.jitbyjones.thewake.phantom.Phantom;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Client copy of one image, with interpolation. */
public final class ClientPhantom {
    public final int id;
    public Phantom.Mode mode = Phantom.Mode.EXPOSURE;
    public Phantom.Stance stance = Phantom.Stance.STAND;
    public int bedDirection = -1;

    public Vec3 pos;
    public Vec3 prevPos;
    private Vec3 targetPos;
    public float yaw;
    public float prevYaw;
    private float targetYaw;

    public float opacity;
    public float prevOpacity;
    private float targetOpacity;
    private float fadeSpeed = 1.0F;
    public boolean dissolving;

    public float smear;
    public float focus;
    public float headYaw;
    public float prevHeadYaw;
    private float targetHeadYaw;
    public float headPitch;
    public float prevHeadPitch;
    private float targetHeadPitch;
    public float headSmear;

    public float walkPos;
    public float prevWalkPos;
    public float walkSpeed;
    /** Recent motion, for the direction of the smear. */
    public Vec3 motion = Vec3.ZERO;
    public int age;
    private boolean fresh = true;

    ClientPhantom(int id, Vec3 pos) {
        this.id = id;
        this.pos = pos;
        this.prevPos = pos;
        this.targetPos = pos;
    }

    void apply(PhantomPayload p) {
        Vec3 newPos = new Vec3(p.x(), p.y(), p.z());
        mode = Phantom.Mode.values()[Mth.clamp(p.mode(), 0, Phantom.Mode.values().length - 1)];
        stance = Phantom.Stance.values()[Mth.clamp(p.stance(), 0, Phantom.Stance.values().length - 1)];
        bedDirection = p.bedDirection();
        smear = p.smear();
        focus = p.focus();
        headSmear = p.headSmear();
        targetHeadYaw = p.headYaw();
        targetHeadPitch = p.headPitch();
        targetOpacity = p.opacity();
        fadeSpeed = p.fadeSpeed();
        targetYaw = p.yaw();
        if (fresh) {
            fresh = false;
            pos = prevPos = targetPos = newPos;
            yaw = prevYaw = targetYaw;
            headYaw = prevHeadYaw = targetHeadYaw;
            headPitch = prevHeadPitch = targetHeadPitch;
            if (fadeSpeed >= 1.0F) {
                opacity = prevOpacity = targetOpacity;
            }
        } else {
            // A time-lapse jump (the Stopped's "getting up") is a teleport, not a walk.
            if (newPos.distanceToSqr(targetPos) > 9.0) {
                pos = prevPos = newPos;
            }
            targetPos = newPos;
        }
    }

    void dissolve() {
        dissolving = true;
        targetOpacity = 0.0F;
        fadeSpeed = 0.25F;
    }

    boolean tick() {
        age++;
        prevPos = pos;
        prevYaw = yaw;
        prevOpacity = opacity;
        prevHeadYaw = headYaw;
        prevHeadPitch = headPitch;
        prevWalkPos = walkPos;

        Vec3 before = pos;
        pos = pos.add(targetPos.subtract(pos).scale(0.5));
        yaw += Mth.wrapDegrees(targetYaw - yaw) * 0.5F;
        opacity += (targetOpacity - opacity) * fadeSpeed;
        if (Math.abs(targetOpacity - opacity) < 0.003F) {
            opacity = targetOpacity;
        }
        headYaw += (targetHeadYaw - headYaw) * 0.35F;
        headPitch += (targetHeadPitch - headPitch) * 0.35F;

        Vec3 delta = pos.subtract(before);
        motion = motion.scale(0.8).add(delta.scale(0.2));
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        walkSpeed += ((float) Math.min(horizontal * 4.0, 1.0) - walkSpeed) * 0.4F;
        walkPos += walkSpeed;
        return !(dissolving && opacity <= 0.004F);
    }

    public Vec3 lerpPos(float partial) {
        return new Vec3(Mth.lerp(partial, prevPos.x, pos.x), Mth.lerp(partial, prevPos.y, pos.y), Mth.lerp(partial, prevPos.z, pos.z));
    }

    public float lerpYaw(float partial) {
        return prevYaw + Mth.wrapDegrees(yaw - prevYaw) * partial;
    }

    public float lerpOpacity(float partial) {
        return Mth.lerp(partial, prevOpacity, opacity);
    }
}
