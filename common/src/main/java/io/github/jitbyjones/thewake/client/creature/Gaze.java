package io.github.jitbyjones.thewake.client.creature;

import io.github.jitbyjones.thewake.client.ClientPhantom;
import io.github.jitbyjones.thewake.phantom.Phantom;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The part of the creature that is purely about being looked at, run on the client every tick.
 *
 * <ul>
 * <li>Looked at directly, it is perfectly still.</li>
 * <li>At the edge of sight, it is not: the head jerks and tilts, the jaw works.</li>
 * <li>Out of sight, its head turns to face you, so that is how you find it when you look back.</li>
 * </ul>
 */
public final class Gaze {
    /** Inside this many degrees of the centre of view, it is being watched: nothing moves. */
    public static final float WATCHED = 22.0F;
    /** Beyond this, it is in the corner of your eye. */
    public static final float PERIPHERY = 30.0F;

    public float lookYaw;
    public float lookPitch;
    public boolean looking;

    public float twitchYaw;
    public float twitchPitch;
    public float twitchRoll;
    public float twitchJaw;
    private float targetYaw;
    private float targetPitch;
    private float targetRoll;
    private float targetJaw;
    private int nextTwitch;

    /** Degrees between the centre of view and its head. */
    public float angle = 180.0F;
    public boolean inView;
    public boolean visible;
    public int unseenTicks = 1000;
    public int seenTicks;
    public double distance = 1000.0;
    private int clipCooldown;
    private boolean occluded;
    /** Turned to face you since you last saw it. */
    private boolean turned;

    public void tick(ClientPhantom p, Camera camera, RandomSource random) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = camera.getPosition();
        Vec3 head = p.pos.add(0.0, p.height() * 0.9, 0.0);
        Vec3 to = head.subtract(eye);
        distance = to.length();
        if (distance < 1.0E-3) {
            return;
        }
        Vector3f forward = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        double z = to.x * forward.x() + to.y * forward.y() + to.z * forward.z();
        double x = to.x * left.x() + to.y * left.y() + to.z * left.z();
        double y = to.x * up.x() + to.y * up.y() + to.z * up.z();
        angle = (float) Math.toDegrees(Math.acos(Mth.clamp(z / distance, -1.0, 1.0)));
        float vHalf = (float) Math.toRadians(mc.options.fov().get() * 0.5 + 4.0);
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double hHalf = Math.atan(Math.tan(vHalf) * aspect);
        // Part of it counts: a tall body is in view well before its head is.
        double margin = Math.atan2(p.height() * 0.5, Math.max(1.0, distance));
        inView = z > 0.0 && Math.abs(Math.atan2(x, z)) < hHalf + margin * 0.3 && Math.abs(Math.atan2(y, z)) < vHalf + margin;
        if (--clipCooldown <= 0 && mc.level != null && mc.player != null) {
            clipCooldown = 4;
            occluded = occluded(mc, eye, head) && occluded(mc, eye, p.pos.add(0.0, p.height() * 0.5, 0.0));
        }
        visible = inView && !occluded;
        if (visible) {
            unseenTicks = 0;
            seenTicks++;
            turned = false;
        } else {
            unseenTicks++;
            seenTicks = 0;
        }

        boolean scripted = p.scriptedHead() || (p.flags & Phantom.FLAG_NO_TWITCH) != 0 || p.mode != Phantom.Mode.STILL;
        if (scripted) {
            looking = false;
            twitchYaw = twitchPitch = twitchRoll = twitchJaw = 0.0F;
            return;
        }
        if (unseenTicks >= 8 && !turned) {
            turned = true;
            // Out of sight: it turns to you. And it does not hold its head the way a person would.
            faceCamera(p, eye);
            looking = true;
            targetRoll = twitchRoll = (random.nextFloat() - 0.5F) * 50.0F;
            targetYaw = twitchYaw = 0.0F;
            targetPitch = twitchPitch = (random.nextFloat() - 0.5F) * 10.0F;
            targetJaw = twitchJaw = random.nextFloat() < 0.3F ? 6.0F + random.nextFloat() * 10.0F : 0.0F;
        }
        if (visible && angle > PERIPHERY && distance < 40.0 * p.scale) {
            if (--nextTwitch <= 0) {
                boolean big = random.nextFloat() < 0.12F;
                targetYaw = (random.nextFloat() - 0.5F) * (big ? 40.0F : 16.0F);
                targetPitch = (random.nextFloat() - 0.5F) * (big ? 24.0F : 10.0F);
                targetRoll = (random.nextFloat() - 0.5F) * (big ? 70.0F : 22.0F);
                targetJaw = random.nextFloat() < 0.35F ? random.nextFloat() * (big ? 28.0F : 9.0F) : 0.0F;
                nextTwitch = big ? 14 + random.nextInt(16) : 2 + random.nextInt(9);
            }
            // Jerky, not smooth: most of the way there in one tick.
            twitchYaw += (targetYaw - twitchYaw) * 0.7F;
            twitchPitch += (targetPitch - twitchPitch) * 0.7F;
            twitchRoll += (targetRoll - twitchRoll) * 0.7F;
            twitchJaw += (targetJaw - twitchJaw) * 0.7F;
        }
        // Watched directly (angle < WATCHED) or in between: hold exactly as it is.
    }

    private static boolean occluded(Minecraft mc, Vec3 eye, Vec3 target) {
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceToSqr(eye) < target.distanceToSqr(eye) - 0.25;
    }

    /** It moved (a cut): whatever its head was doing belongs to the old frame. */
    public void cut() {
        looking = false;
        turned = false;
        twitchYaw = twitchPitch = twitchRoll = twitchJaw = 0.0F;
        targetYaw = targetPitch = targetRoll = targetJaw = 0.0F;
    }

    /** Head angles (relative to the pose) that point its face at the camera. */
    private void faceCamera(ClientPhantom p, Vec3 eye) {
        CreatureModel model = CreatureRenderer.model();
        if (model == null) {
            return;
        }
        CreatureModel.Pose pose = model.pose(p.pose);
        Vector3f fwd = pose.pivot(CreatureModel.HEAD_FORWARD);
        Vector3f headLocal = pose.pivot(CreatureModel.HEAD);
        // Model -> world: rotate by the body's yaw.
        float yawRad = (float) Math.toRadians(-p.yaw);
        float cos = Mth.cos(yawRad);
        float sin = Mth.sin(yawRad);
        double fx = fwd.x() * cos + fwd.z() * sin;
        double fz = -fwd.x() * sin + fwd.z() * cos;
        double hx = (headLocal.x() * cos + headLocal.z() * sin) * p.scale;
        double hz = (-headLocal.x() * sin + headLocal.z() * cos) * p.scale;
        Vec3 headWorld = p.pos.add(hx, headLocal.y() * p.scale, hz);
        Vec3 d = eye.subtract(headWorld);
        float wantYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float haveYaw = (float) Math.toDegrees(Math.atan2(-fx, fz));
        lookYaw = Mth.wrapDegrees(wantYaw - haveYaw);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float wantElevation = (float) Math.toDegrees(Math.atan2(d.y, horiz));
        float haveElevation = (float) Math.toDegrees(Math.atan2(fwd.y(), Math.sqrt(fx * fx + fz * fz)));
        lookPitch = Mth.clamp(-(wantElevation - haveElevation), -70.0F, 70.0F);
    }
}
