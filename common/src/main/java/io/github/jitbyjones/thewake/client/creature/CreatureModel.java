package io.github.jitbyjones.thewake.client.creature;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The creature's mesh: every pose, as exported by {@code tools/creature/build_creature.py}
 * (format documented in its {@code write_bin}). Model space is blocks, +Y up, facing +Z, feet at
 * the origin. The body is stored per pose; the head and jaw are rigid, stored once with a transform
 * per pose, so the renderer can turn the head and open the jaw on top of any pose.
 */
public final class CreatureModel {
    public static final ResourceLocation LOCATION = TheWake.id("models/creature/creature.bin.gz");

    public static final int NECK = 0;
    public static final int HEAD = 3;
    public static final int HEAD_RIGHT = 6;
    public static final int HEAD_UP = 9;
    public static final int HEAD_FORWARD = 12;
    public static final int JAW_HINGE = 15;
    public static final int EYE_LEFT = 18;
    public static final int EYE_RIGHT = 21;
    public static final int MOUTH = 24;
    public static final int HEIGHT = 27;
    private static final int PIVOT_FLOATS = 28;

    public static final class Group {
        public final String name;
        public final ResourceLocation texture;
        public final boolean rigid;
        public final int vertexCount;
        public final float[] uv;
        public final int[] indices;
        /** Rigid groups only: rest positions and normals. */
        float[] restPositions;
        float[] restNormals;

        Group(String name, ResourceLocation texture, boolean rigid, int vertexCount, float[] uv, int[] indices) {
            this.name = name;
            this.texture = texture;
            this.rigid = rigid;
            this.vertexCount = vertexCount;
            this.uv = uv;
            this.indices = indices;
        }
    }

    public static final class Pose {
        public final CreaturePose id;
        public final float[] pivots;
        /** Per group: positions/normals for deformed groups, null for rigid ones. */
        final float[][] positions;
        final float[][] normals;
        /** Per group: the rigid transform for rigid groups, null for deformed ones. */
        final Matrix4f[] transforms;

        Pose(CreaturePose id, float[] pivots, int groups) {
            this.id = id;
            this.pivots = pivots;
            this.positions = new float[groups][];
            this.normals = new float[groups][];
            this.transforms = new Matrix4f[groups];
        }

        public Vector3f pivot(int offset) {
            return new Vector3f(pivots[offset], pivots[offset + 1], pivots[offset + 2]);
        }

        public float height() {
            return pivots[HEIGHT];
        }
    }

    final Group[] groups;
    final Map<CreaturePose, Pose> poses;

    private CreatureModel(Group[] groups, Map<CreaturePose, Pose> poses) {
        this.groups = groups;
        this.poses = poses;
    }

    public Pose pose(CreaturePose pose) {
        Pose p = poses.get(pose);
        return p != null ? p : poses.get(CreaturePose.STAND);
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
        if (!"WAKEMESH".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IOException("not a creature mesh");
        }
        int version = in.readInt();
        if (version != 2) {
            throw new IOException("unsupported creature mesh version " + version);
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
            in.skipNBytes(n); // part ids: baked into the texture already
            int[] indices = new int[indexCount];
            boolean shorts = n < 65536;
            for (int i = 0; i < indexCount; i++) {
                indices[i] = shorts ? in.readUnsignedShort() : in.readInt();
                if (indices[i] >= n) {
                    throw new IOException("index out of range in group " + name);
                }
            }
            Group group = new Group(name, texture, rigid, n, uv, indices);
            if (rigid) {
                float[][] pn = readPositions(in, n);
                group.restPositions = pn[0];
                group.restNormals = pn[1];
            }
            groups[g] = group;
        }
        int poseCount = in.readInt();
        Map<String, CreaturePose> byName = new HashMap<>();
        for (CreaturePose p : CreaturePose.values()) {
            byName.put(p.modelName(), p);
        }
        Map<CreaturePose, Pose> poses = new EnumMap<>(CreaturePose.class);
        for (int p = 0; p < poseCount; p++) {
            String name = in.readUTF();
            float[] pivots = new float[PIVOT_FLOATS];
            for (int i = 0; i < PIVOT_FLOATS; i++) {
                pivots[i] = in.readFloat();
            }
            CreaturePose id = byName.get(name);
            Pose pose = new Pose(id, pivots, groupCount);
            for (int g = 0; g < groupCount; g++) {
                if (groups[g].rigid) {
                    float[] m = new float[12];
                    for (int i = 0; i < 12; i++) {
                        m[i] = in.readFloat();
                    }
                    // Row-major 3x4 -> JOML (column-major setters take m[col][row]).
                    pose.transforms[g] = new Matrix4f(
                            m[0], m[4], m[8], 0.0F,
                            m[1], m[5], m[9], 0.0F,
                            m[2], m[6], m[10], 0.0F,
                            m[3], m[7], m[11], 1.0F);
                } else {
                    float[][] pn = readPositions(in, groups[g].vertexCount);
                    pose.positions[g] = pn[0];
                    pose.normals[g] = pn[1];
                }
            }
            if (id != null) {
                poses.put(id, pose);
            } else {
                TheWake.LOG.warn("Creature mesh has an unknown pose '{}'", name);
            }
        }
        if (!poses.containsKey(CreaturePose.STAND)) {
            throw new IOException("creature mesh has no stand pose");
        }
        return new CreatureModel(groups, poses);
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
