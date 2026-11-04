package io.github.jitbyjones.thewake.client.creature;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One posed skeleton: for every joint, the rigid transform that carries rest-pose points to where
 * they are now (p' = R p + t). Loaded from a clip frame, then bent by procedural layers (the head
 * turning to you, the jaw, twitches) by rotating whole subtrees about a joint's posed position.
 */
public final class CreatureSkeleton {
    final CreatureModel model;
    final int n;
    final float[] q;
    final float[] t;

    public CreatureSkeleton(CreatureModel model) {
        this.model = model;
        this.n = model.jointCount();
        this.q = new float[n * 4];
        this.t = new float[n * 3];
        setRest();
    }

    public void setRest() {
        for (int j = 0; j < n; j++) {
            q[j * 4] = 0.0F;
            q[j * 4 + 1] = 0.0F;
            q[j * 4 + 2] = 0.0F;
            q[j * 4 + 3] = 1.0F;
            t[j * 3] = 0.0F;
            t[j * 3 + 1] = 0.0F;
            t[j * 3 + 2] = 0.0F;
        }
    }

    public void setFrame(CreatureModel.Clip clip, int frame) {
        int f = Math.floorMod(frame, clip.frames);
        int o = f * n * 7;
        for (int j = 0; j < n; j++, o += 7) {
            q[j * 4] = clip.data[o];
            q[j * 4 + 1] = clip.data[o + 1];
            q[j * 4 + 2] = clip.data[o + 2];
            q[j * 4 + 3] = clip.data[o + 3];
            t[j * 3] = clip.data[o + 4];
            t[j * 3 + 1] = clip.data[o + 5];
            t[j * 3 + 2] = clip.data[o + 6];
        }
    }

    /** Where joint j's rest-space point p is now. */
    public Vector3f apply(int j, Vector3f p, Vector3f out) {
        float qx = q[j * 4], qy = q[j * 4 + 1], qz = q[j * 4 + 2], qw = q[j * 4 + 3];
        // p + 2 v x (v x p + w p)
        float cx = qy * p.z - qz * p.y + qw * p.x;
        float cy = qz * p.x - qx * p.z + qw * p.y;
        float cz = qx * p.y - qy * p.x + qw * p.z;
        float rx = p.x + 2.0F * (qy * cz - qz * cy);
        float ry = p.y + 2.0F * (qz * cx - qx * cz);
        float rz = p.z + 2.0F * (qx * cy - qy * cx);
        return out.set(rx + t[j * 3], ry + t[j * 3 + 1], rz + t[j * 3 + 2]);
    }

    /** A direction carried by joint j (no translation). */
    public Vector3f applyDirection(int j, Vector3f d, Vector3f out) {
        new Quaternionf(q[j * 4], q[j * 4 + 1], q[j * 4 + 2], q[j * 4 + 3]).transform(d, out);
        return out;
    }

    /** Where joint j itself is now. */
    public Vector3f joint(int j, Vector3f out) {
        Vector3f rest = new Vector3f(model.rest[j * 3], model.rest[j * 3 + 1], model.rest[j * 3 + 2]);
        return apply(j, rest, out);
    }

    public Vector3f attachment(CreatureModel.Attachment a, Vector3f out) {
        return a.direction() ? applyDirection(a.joint(), a.value(), out).normalize() : apply(a.joint(), a.value(), out);
    }

    /** Joint j's transform as a matrix (rest space to posed model space). */
    public Matrix4f matrix(int j, Matrix4f out) {
        return out.translation(t[j * 3], t[j * 3 + 1], t[j * 3 + 2])
                .rotate(new Quaternionf(q[j * 4], q[j * 4 + 1], q[j * 4 + 2], q[j * 4 + 3]));
    }

    /** Turns joint j and everything below it by r, about where joint j is now. */
    public void rotate(int j, Quaternionf r) {
        if (j < 0) {
            return;
        }
        Vector3f pivot = joint(j, new Vector3f());
        Quaternionf tmp = new Quaternionf();
        Vector3f v = new Vector3f();
        for (int k : model.subtrees[j]) {
            tmp.set(q[k * 4], q[k * 4 + 1], q[k * 4 + 2], q[k * 4 + 3]);
            r.mul(tmp, tmp);
            tmp.normalize();
            q[k * 4] = tmp.x;
            q[k * 4 + 1] = tmp.y;
            q[k * 4 + 2] = tmp.z;
            q[k * 4 + 3] = tmp.w;
            v.set(t[k * 3] - pivot.x, t[k * 3 + 1] - pivot.y, t[k * 3 + 2] - pivot.z);
            r.transform(v);
            t[k * 3] = v.x + pivot.x;
            t[k * 3 + 1] = v.y + pivot.y;
            t[k * 3 + 2] = v.z + pivot.z;
        }
    }

    /** Turns joint j by an angle about an axis given in model space. */
    public void rotate(int j, float radians, Vector3f axis) {
        if (j >= 0 && radians != 0.0F) {
            rotate(j, new Quaternionf().rotationAxis(radians, new Vector3f(axis).normalize()));
        }
    }

    /** Moves joint j and everything below it. */
    public void translate(int j, Vector3f by) {
        if (j < 0) {
            return;
        }
        for (int k : model.subtrees[j]) {
            t[k * 3] += by.x;
            t[k * 3 + 1] += by.y;
            t[k * 3 + 2] += by.z;
        }
    }

    /** A cheap fingerprint of the whole pose, for caching skinned buffers. */
    public long fingerprint() {
        long h = 1125899906842597L;
        for (int i = 0; i < q.length; i++) {
            h = 31 * h + Math.round(q[i] * 4096.0F);
        }
        for (int i = 0; i < t.length; i++) {
            h = 31 * h + Math.round(t[i] * 4096.0F);
        }
        return h;
    }
}
