package io.github.jitbyjones.thewake.client.creature;

import io.github.jitbyjones.thewake.TheWake;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;

/**
 * The creature as exported by {@code tools/creature/build_creature.py} (format v3, documented in
 * {@code tools/creature/export_v3.py}): a skeleton, a skinned body, rigid parts riding on joints,
 * named attachment points, and baked animation clips. Model space is blocks, +Y up, facing +Z,
 * feet at the origin.
 */
public final class CreatureModel {
    public static final ResourceLocation LOCATION = TheWake.id("models/creature/creature.bin.gz");

    public final String[] jointNames;
    public final int[] parents;
    /** Rest positions, 3 per joint. */
    public final float[] rest;
    /** For each joint, every joint below it (itself first), in parent-first order. */
    final int[][] subtrees;
    final Map<String, Integer> jointIndex = new HashMap<>();
    final Map<String, Attachment> attachments = new HashMap<>();
    final Group[] groups;
    final Map<String, Clip> clips = new HashMap<>();

    public record Attachment(String name, int joint, boolean direction, Vector3f value) {}

    public static final class Group {
        public final String name;
        public final ResourceLocation texture;
        public final boolean rigid;
        public final int vertexCount;
        public final float[] uv;
        public final int[] indices;
        public final float[] restPositions;
        public final float[] restNormals;
        /** Rigid groups: the joint they ride on. */
        public int joint = -1;
        /** Skinned groups: four joints and four weights (0..255, summing to 255) per vertex. */
        public byte[] joints;
        public byte[] weights;

        Group(String name, ResourceLocation texture, boolean rigid, int vertexCount, float[] uv, int[] indices,
                float[] restPositions, float[] restNormals) {
            this.name = name;
            this.texture = texture;
            this.rigid = rigid;
            this.vertexCount = vertexCount;
            this.uv = uv;
            this.indices = indices;
            this.restPositions = restPositions;
            this.restNormals = restNormals;
        }
    }

    /** A baked clip: per frame, per joint, a rotation quaternion and a translation (rest to posed). */
    public static final class Clip {
        public final String name;
        public final float fps;
        public final boolean loop;
        public final int frames;
        public final float[] heights;
        /** frame * joints * 7: qx, qy, qz, qw, tx, ty, tz. */
        final float[] data;

        Clip(String name, float fps, boolean loop, int frames, float[] heights, float[] data) {
            this.name = name;
            this.fps = fps;
            this.loop = loop;
            this.frames = frames;
            this.heights = heights;
            this.data = data;
        }
    }

    private CreatureModel(String[] jointNames, int[] parents, float[] rest, Group[] groups) {
        this.jointNames = jointNames;
        this.parents = parents;
        this.rest = rest;
        this.groups = groups;
        for (int i = 0; i < jointNames.length; i++) {
            jointIndex.put(jointNames[i], i);
        }
        subtrees = new int[jointNames.length][];
        for (int j = 0; j < jointNames.length; j++) {
            List<Integer> below = new ArrayList<>();
            below.add(j);
            for (int k = j + 1; k < jointNames.length; k++) {
                for (int p = parents[k]; p >= 0; p = parents[p]) {
                    if (p == j) {
                        below.add(k);
                        break;
                    }
                }
            }
            subtrees[j] = below.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    public int joint(String name) {
        Integer i = jointIndex.get(name);
        return i == null ? -1 : i;
    }

    @Nullable
    public Attachment attachment(String name) {
        return attachments.get(name);
    }

    @Nullable
    public Clip clip(String name) {
        return clips.get(name);
    }

    public int jointCount() {
        return jointNames.length;
    }

    public static CreatureModel load(ResourceManager resources) throws IOException {
        try (InputStream raw = resources.open(LOCATION);
                DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 16))) {
            return read(in);
        }
    }

    private static CreatureModel read(DataInputStream in) throws IOException {
        byte[] magic = new byte[8];
        in.readFully(magic);
        if (!"WAKEMESH".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("not a creature mesh");
        }
        int version = in.readInt();
        if (version != 3) {
            throw new IOException("unsupported creature mesh version " + version);
        }
        int jointCount = in.readInt();
        String[] names = new String[jointCount];
        int[] parents = new int[jointCount];
        float[] rest = new float[jointCount * 3];
        for (int j = 0; j < jointCount; j++) {
            names[j] = in.readUTF();
            parents[j] = in.readInt();
            if (parents[j] >= j) {
                throw new IOException("joints are not in parent-first order at " + names[j]);
            }
            for (int k = 0; k < 3; k++) {
                rest[j * 3 + k] = in.readFloat();
            }
        }
        List<Attachment> attachments = new ArrayList<>();
        int attachmentCount = in.readInt();
        for (int a = 0; a < attachmentCount; a++) {
            String name = in.readUTF();
            int joint = in.readInt();
            boolean direction = in.readByte() != 0;
            Vector3f value = new Vector3f(in.readFloat(), in.readFloat(), in.readFloat());
            attachments.add(new Attachment(name, joint, direction, value));
        }
        int groupCount = in.readInt();
        Group[] groups = new Group[groupCount];
        for (int g = 0; g < groupCount; g++) {
            String name = in.readUTF();
            ResourceLocation texture = ResourceLocation.parse(in.readUTF());
            boolean rigid = in.readByte() != 0;
            int n = in.readInt();
            int indexCount = in.readInt();
            float[] uv = new float[n * 2];
            for (int i = 0; i < uv.length; i++) {
                uv[i] = in.readFloat();
            }
            int[] indices = new int[indexCount];
            boolean shorts = n < 65536;
            for (int i = 0; i < indexCount; i++) {
                indices[i] = shorts ? in.readUnsignedShort() : in.readInt();
                if (indices[i] >= n) {
                    throw new IOException("index out of range in group " + name);
                }
            }
            float[][] pn = readPositions(in, n);
            Group group = new Group(name, texture, rigid, n, uv, indices, pn[0], pn[1]);
            if (rigid) {
                group.joint = in.readInt();
                if (group.joint < 0 || group.joint >= jointCount) {
                    throw new IOException("rigid group " + name + " rides on a missing joint");
                }
            } else {
                group.joints = new byte[n * 4];
                group.weights = new byte[n * 4];
                in.readFully(group.joints);
                in.readFully(group.weights);
                for (byte b : group.joints) {
                    if ((b & 0xFF) >= jointCount) {
                        throw new IOException("weight on a missing joint in group " + name);
                    }
                }
            }
            groups[g] = group;
        }
        CreatureModel model = new CreatureModel(names, parents, rest, groups);
        for (Attachment a : attachments) {
            model.attachments.put(a.name(), a);
        }
        int clipCount = in.readInt();
        for (int c = 0; c < clipCount; c++) {
            String name = in.readUTF();
            float fps = in.readFloat();
            boolean loop = (in.readByte() & 1) != 0;
            int frames = in.readInt();
            float[] heights = new float[frames];
            float[] data = new float[frames * jointCount * 7];
            int o = 0;
            for (int f = 0; f < frames; f++) {
                heights[f] = in.readFloat();
                for (int j = 0; j < jointCount; j++) {
                    float qx = in.readShort() / 32767.0F;
                    float qy = in.readShort() / 32767.0F;
                    float qz = in.readShort() / 32767.0F;
                    float qw = in.readShort() / 32767.0F;
                    float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
                    data[o++] = qx / len;
                    data[o++] = qy / len;
                    data[o++] = qz / len;
                    data[o++] = qw / len;
                    data[o++] = in.readFloat();
                    data[o++] = in.readFloat();
                    data[o++] = in.readFloat();
                }
            }
            model.clips.put(name, new Clip(name, fps, loop, frames, heights, data));
        }
        if (model.clips.isEmpty()) {
            throw new IOException("creature mesh has no clips");
        }
        return model;
    }

    private static float[][] readPositions(DataInputStream in, int n) throws IOException {
        float[] lo = {in.readFloat(), in.readFloat(), in.readFloat()};
        float[] hi = {in.readFloat(), in.readFloat(), in.readFloat()};
        float[] pos = new float[n * 3];
        for (int i = 0; i < n * 3; i++) {
            int axis = i % 3;
            pos[i] = lo[axis] + in.readUnsignedShort() / 65535.0F * (hi[axis] - lo[axis]);
        }
        float[] nor = new float[n * 3];
        for (int i = 0; i < n * 3; i++) {
            nor[i] = in.readByte() / 127.0F;
        }
        return new float[][] {pos, nor};
    }
}
