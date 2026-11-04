package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.client.creature.Gaze;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.net.PhantomPayload;
import io.github.jitbyjones.thewake.phantom.Phantom;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Client copy of one appearance. Position, facing and pose never interpolate: every change is a
 * cut, like a frame of stop-motion. Only a head the server is turning moves smoothly.
 */
public final class ClientPhantom {
    public final int id;
    public Phantom.Mode mode = Phantom.Mode.STILL;
    public CreaturePose pose = CreaturePose.STAND;
    public int frame;
    public int bedDirection = -1;
    public int flags;

    public Vec3 pos;
    public float yaw;
    public float scale = 1.0F;

    public float headYaw;
    public float prevHeadYaw;
    private float targetHeadYaw;
    public float headPitch;
    public float prevHeadPitch;
    private float targetHeadPitch;
    public float jaw;
    public float prevJaw;
    private float targetJaw;

    /** What the client adds on its own: looking at you when you are not looking, twitching at the edge of sight. */
    public final Gaze gaze = new Gaze();

    public boolean dissolving;
    public int dissolveAge;
    public int age;
    /** Client ticks since the last cut (a jump in position or pose). */
    public int sinceCut;
    private boolean fresh = true;

    ClientPhantom(int id, Vec3 pos) {
        this.id = id;
        this.pos = pos;
    }

    void apply(PhantomPayload p) {
        Vec3 newPos = new Vec3(p.x(), p.y(), p.z());
        CreaturePose newPose = CreaturePose.byId(p.pose());
        if (!fresh && (newPos.distanceToSqr(pos) > 1.0E-4 || newPose != pose || p.frame() != frame
                || Math.abs(Mth.wrapDegrees(p.yaw() - yaw)) > 0.5F)) {
            sinceCut = 0;
            gaze.cut();
        }
        mode = Phantom.Mode.values()[Mth.clamp(p.mode(), 0, Phantom.Mode.values().length - 1)];
        pose = newPose;
        frame = p.frame();
        bedDirection = p.bedDirection();
        flags = p.flags();
        pos = newPos;
        yaw = p.yaw();
        scale = p.scale();
        targetHeadYaw = p.headYaw();
        targetHeadPitch = p.headPitch();
        targetJaw = p.jaw();
        if (fresh || (flags & Phantom.FLAG_SCRIPTED_HEAD) == 0) {
            headYaw = prevHeadYaw = targetHeadYaw;
            headPitch = prevHeadPitch = targetHeadPitch;
            jaw = prevJaw = targetJaw;
        }
        fresh = false;
    }

    void dissolve() {
        dissolving = true;
    }

    public boolean scriptedHead() {
        return (flags & Phantom.FLAG_SCRIPTED_HEAD) != 0;
    }

    /** False once it should be dropped. */
    boolean tick() {
        age++;
        sinceCut++;
        prevHeadYaw = headYaw;
        prevHeadPitch = headPitch;
        prevJaw = jaw;
        headYaw += Mth.wrapDegrees(targetHeadYaw - headYaw) * 0.45F;
        headPitch += (targetHeadPitch - headPitch) * 0.45F;
        jaw += (targetJaw - jaw) * 0.5F;
        if (dissolving) {
            dissolveAge++;
            return dissolveAge < 7;
        }
        return true;
    }

    public float lerpHeadYaw(float partial) {
        return prevHeadYaw + Mth.wrapDegrees(headYaw - prevHeadYaw) * partial;
    }

    public float lerpHeadPitch(float partial) {
        return Mth.lerp(partial, prevHeadPitch, headPitch);
    }

    public float lerpJaw(float partial) {
        return Mth.lerp(partial, prevJaw, jaw);
    }

    /** Height of the top of its head above its feet, in blocks. */
    public float height() {
        return pose.height(frame) * scale;
    }
}
