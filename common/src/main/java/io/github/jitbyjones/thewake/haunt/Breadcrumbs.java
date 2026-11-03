package io.github.jitbyjones.thewake.haunt;

import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/**
 * Every block position the player has stood on (sampled), most recent last. This is what the Wake
 * knows of the world: in the Stopped it can only walk where you have walked.
 */
public final class Breadcrumbs {
    private static final int MAX = 8192;
    private static final int CELL = 8;

    private final Long2IntLinkedOpenHashMap visits = new Long2IntLinkedOpenHashMap();
    @Nullable
    private Long2ObjectOpenHashMap<LongArrayList> index;

    public void add(BlockPos feet) {
        long key = feet.asLong();
        int previous = visits.getAndMoveToLast(key);
        visits.put(key, Math.min(previous + 1, 1_000_000));
        if (visits.size() > MAX) {
            visits.removeFirstInt();
        }
        index = null;
    }

    public int size() {
        return visits.size();
    }

    public boolean isEmpty() {
        return visits.isEmpty();
    }

    public void clear() {
        visits.clear();
        index = null;
    }

    public boolean contains(BlockPos pos) {
        return visits.containsKey(pos.asLong());
    }

    /** True if any crumb lies within {@code radius} blocks (horizontal), up to 4 blocks vertically. */
    public boolean isNear(BlockPos pos, int radius) {
        return nearest(Vec3.atBottomCenterOf(pos), radius) != null;
    }

    @Nullable
    public BlockPos nearest(Vec3 pos, double maxDistance) {
        ensureIndex();
        int minCellX = cell(Math.floor(pos.x - maxDistance));
        int maxCellX = cell(Math.floor(pos.x + maxDistance));
        int minCellZ = cell(Math.floor(pos.z - maxDistance));
        int maxCellZ = cell(Math.floor(pos.z + maxDistance));
        BlockPos best = null;
        double bestDist = maxDistance * maxDistance;
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                LongArrayList list = index.get(cellKey(cx, cz));
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    BlockPos p = BlockPos.of(list.getLong(i));
                    double dy = Math.abs(p.getY() - pos.y);
                    if (dy > 4.0) {
                        continue;
                    }
                    double dx = p.getX() + 0.5 - pos.x;
                    double dz = p.getZ() + 0.5 - pos.z;
                    double d = dx * dx + dz * dz + dy * dy * 0.25;
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /** Crumbs within {@code radius} of {@code center}: the graph edges the Wake can follow. */
    public List<BlockPos> neighbours(BlockPos center, double radius) {
        ensureIndex();
        List<BlockPos> out = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        double r2 = radius * radius;
        for (int cx = cell(center.getX() - r); cx <= cell(center.getX() + r); cx++) {
            for (int cz = cell(center.getZ() - r); cz <= cell(center.getZ() + r); cz++) {
                LongArrayList list = index.get(cellKey(cx, cz));
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    long key = list.getLong(i);
                    BlockPos p = BlockPos.of(key);
                    if (p.equals(center) || Math.abs(p.getY() - center.getY()) > 1) {
                        continue;
                    }
                    if (p.distSqr(center) <= r2) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    private void ensureIndex() {
        if (index != null) {
            return;
        }
        index = new Long2ObjectOpenHashMap<>();
        LongIterator it = visits.keySet().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            BlockPos p = BlockPos.of(key);
            index.computeIfAbsent(cellKey(cell(p.getX()), cell(p.getZ())), k -> new LongArrayList()).add(key);
        }
    }

    private static int cell(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CELL);
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Every crumb with how worn it is (visit count, capped at 255), for drawing the ribbons. */
    public void export(it.unimi.dsi.fastutil.longs.LongArrayList positions, it.unimi.dsi.fastutil.bytes.ByteArrayList wear) {
        for (var e : visits.long2IntEntrySet()) {
            positions.add(e.getLongKey());
            wear.add((byte) Math.min(255, e.getIntValue()));
        }
    }

    public void save(CompoundTag tag) {
        long[] keys = new long[visits.size()];
        byte[] wear = new byte[visits.size()];
        int i = 0;
        for (var e : visits.long2IntEntrySet()) {
            keys[i] = e.getLongKey();
            wear[i] = (byte) Math.min(255, e.getIntValue());
            i++;
        }
        tag.put("Crumbs", new LongArrayTag(keys));
        tag.putByteArray("Wear", wear);
    }

    public void load(CompoundTag tag) {
        visits.clear();
        index = null;
        if (tag.contains("Crumbs", Tag.TAG_LONG_ARRAY)) {
            long[] keys = tag.getLongArray("Crumbs");
            byte[] wear = tag.getByteArray("Wear");
            for (int i = 0; i < keys.length; i++) {
                visits.put(keys[i], i < wear.length ? Math.max(1, wear[i] & 0xFF) : 1);
            }
        }
    }
}
