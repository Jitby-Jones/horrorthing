package io.github.jitbyjones.thewake.client.creature;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.lwjgl.system.MemoryUtil;

/**
 * Puts the creature's vertices into GPU buffers: rigid parts once, the skinned body whenever its
 * pose changes (dual-quaternion skinning on the CPU, four joints per vertex). Uploads are indexed,
 * in vanilla's entity vertex format, so the vanilla entity shader draws them.
 */
final class CreatureSkin {
    private static final int STRIDE = 36;

    private CreatureSkin() {}

    /** Skins a group with the skeleton's current pose and uploads it. */
    static void uploadSkinned(VertexBuffer buffer, CreatureModel.Group g, CreatureSkeleton s, int light) {
        int n = g.vertexCount;
        int joints = s.n;
        // Per joint dual quaternion: real (qx, qy, qz, qw) and dual = 0.5 * (t, 0) * q.
        float[] dq = new float[joints * 8];
        for (int j = 0; j < joints; j++) {
            float qx = s.q[j * 4], qy = s.q[j * 4 + 1], qz = s.q[j * 4 + 2], qw = s.q[j * 4 + 3];
            float tx = s.t[j * 3], ty = s.t[j * 3 + 1], tz = s.t[j * 3 + 2];
            dq[j * 8] = qx;
            dq[j * 8 + 1] = qy;
            dq[j * 8 + 2] = qz;
            dq[j * 8 + 3] = qw;
            dq[j * 8 + 4] = 0.5F * (tx * qw + ty * qz - tz * qy);
            dq[j * 8 + 5] = 0.5F * (-tx * qz + ty * qw + tz * qx);
            dq[j * 8 + 6] = 0.5F * (tx * qy - ty * qx + tz * qw);
            dq[j * 8 + 7] = -0.5F * (tx * qx + ty * qy + tz * qz);
        }
        float[] pos = new float[n * 3];
        float[] nor = new float[n * 3];
        float[] rp = g.restPositions;
        float[] rn = g.restNormals;
        byte[] gj = g.joints;
        byte[] gw = g.weights;
        for (int i = 0; i < n; i++) {
            int j0 = (gj[i * 4] & 0xFF) * 8;
            float r0x = 0, r0y = 0, r0z = 0, r0w = 0, d0x = 0, d0y = 0, d0z = 0, d0w = 0;
            for (int k = 0; k < 4; k++) {
                int w8 = gw[i * 4 + k] & 0xFF;
                if (w8 == 0) {
                    continue;
                }
                int j = (gj[i * 4 + k] & 0xFF) * 8;
                float w = w8 / 255.0F;
                // Keep every influence in the same hemisphere as the first.
                if (dq[j] * dq[j0] + dq[j + 1] * dq[j0 + 1] + dq[j + 2] * dq[j0 + 2] + dq[j + 3] * dq[j0 + 3] < 0.0F) {
                    w = -w;
                }
                r0x += w * dq[j];
                r0y += w * dq[j + 1];
                r0z += w * dq[j + 2];
                r0w += w * dq[j + 3];
                d0x += w * dq[j + 4];
                d0y += w * dq[j + 5];
                d0z += w * dq[j + 6];
                d0w += w * dq[j + 7];
            }
            float len = (float) Math.sqrt(r0x * r0x + r0y * r0y + r0z * r0z + r0w * r0w);
            if (len < 1.0E-6F) {
                r0x = 0;
                r0y = 0;
                r0z = 0;
                r0w = 1;
                d0x = d0y = d0z = d0w = 0;
                len = 1.0F;
            }
            float inv = 1.0F / len;
            r0x *= inv;
            r0y *= inv;
            r0z *= inv;
            r0w *= inv;
            d0x *= inv;
            d0y *= inv;
            d0z *= inv;
            d0w *= inv;
            float px = rp[i * 3], py = rp[i * 3 + 1], pz = rp[i * 3 + 2];
            // rotate: p + 2 v x (v x p + w p)
            float cx = r0y * pz - r0z * py + r0w * px;
            float cy = r0z * px - r0x * pz + r0w * py;
            float cz = r0x * py - r0y * px + r0w * pz;
            float ox = px + 2.0F * (r0y * cz - r0z * cy);
            float oy = py + 2.0F * (r0z * cx - r0x * cz);
            float oz = pz + 2.0F * (r0x * cy - r0y * cx);
            // translate: 2 (w d - d.w v + v x d)
            ox += 2.0F * (r0w * d0x - d0w * r0x + r0y * d0z - r0z * d0y);
            oy += 2.0F * (r0w * d0y - d0w * r0y + r0z * d0x - r0x * d0z);
            oz += 2.0F * (r0w * d0z - d0w * r0z + r0x * d0y - r0y * d0x);
            pos[i * 3] = ox;
            pos[i * 3 + 1] = oy;
            pos[i * 3 + 2] = oz;
            float nx = rn[i * 3], ny = rn[i * 3 + 1], nz = rn[i * 3 + 2];
            cx = r0y * nz - r0z * ny + r0w * nx;
            cy = r0z * nx - r0x * nz + r0w * ny;
            cz = r0x * ny - r0y * nx + r0w * nz;
            nor[i * 3] = nx + 2.0F * (r0y * cz - r0z * cy);
            nor[i * 3 + 1] = ny + 2.0F * (r0z * cx - r0x * cz);
            nor[i * 3 + 2] = nz + 2.0F * (r0x * cy - r0y * cx);
        }
        upload(buffer, g, pos, nor, light);
    }

    static void uploadRigid(VertexBuffer buffer, CreatureModel.Group g, int light) {
        upload(buffer, g, g.restPositions, g.restNormals, light);
    }

    private static void upload(VertexBuffer buffer, CreatureModel.Group g, float[] pos, float[] nor, int light) {
        int n = g.vertexCount;
        int[] indices = g.indices;
        VertexFormat.IndexType type = VertexFormat.IndexType.least(n);
        ByteBufferBuilder vertices = new ByteBufferBuilder(n * STRIDE);
        ByteBufferBuilder index = new ByteBufferBuilder(indices.length * type.bytes);
        try {
            long a = vertices.reserve(n * STRIDE);
            int overlay = OverlayTexture.NO_OVERLAY;
            float[] uv = g.uv;
            for (int i = 0; i < n; i++, a += STRIDE) {
                MemoryUtil.memPutFloat(a, pos[i * 3]);
                MemoryUtil.memPutFloat(a + 4, pos[i * 3 + 1]);
                MemoryUtil.memPutFloat(a + 8, pos[i * 3 + 2]);
                MemoryUtil.memPutInt(a + 12, 0xFFFFFFFF);
                MemoryUtil.memPutFloat(a + 16, uv[i * 2]);
                MemoryUtil.memPutFloat(a + 20, 1.0F - uv[i * 2 + 1]);
                MemoryUtil.memPutShort(a + 24, (short) (overlay & 0xFFFF));
                MemoryUtil.memPutShort(a + 26, (short) (overlay >>> 16 & 0xFFFF));
                MemoryUtil.memPutShort(a + 28, (short) (light & 0xFFFF));
                MemoryUtil.memPutShort(a + 30, (short) (light >>> 16 & 0xFFFF));
                MemoryUtil.memPutByte(a + 32, normalByte(nor[i * 3]));
                MemoryUtil.memPutByte(a + 33, normalByte(nor[i * 3 + 1]));
                MemoryUtil.memPutByte(a + 34, normalByte(nor[i * 3 + 2]));
                MemoryUtil.memPutByte(a + 35, (byte) 0);
            }
            long b = index.reserve(indices.length * type.bytes);
            if (type == VertexFormat.IndexType.SHORT) {
                for (int i = 0; i < indices.length; i++) {
                    MemoryUtil.memPutShort(b + i * 2L, (short) indices[i]);
                }
            } else {
                for (int i = 0; i < indices.length; i++) {
                    MemoryUtil.memPutInt(b + i * 4L, indices[i]);
                }
            }
            MeshData mesh = new MeshData(vertices.build(),
                    new MeshData.DrawState(DefaultVertexFormat.NEW_ENTITY, n, indices.length, VertexFormat.Mode.TRIANGLES, type));
            buffer.bind();
            buffer.upload(mesh);
            buffer.uploadIndexBuffer(index.build());
            VertexBuffer.unbind();
        } finally {
            vertices.close();
            index.close();
        }
    }

    private static byte normalByte(float v) {
        float c = Math.max(-1.0F, Math.min(1.0F, v));
        return (byte) ((int) (c * 127.0F) & 0xFF);
    }
}
