package io.github.jitbyjones.thewake.haunt;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

/** Time-ordered samples of where the player went near home today: the routine it replays at night. */
public final class RouteLog {
    private static final int MAX = 240;
    private final LongArrayList points = new LongArrayList();

    public void add(BlockPos pos) {
        long key = pos.asLong();
        if (!points.isEmpty() && points.getLong(points.size() - 1) == key) {
            return;
        }
        points.add(key);
        if (points.size() > MAX) {
            points.removeLong(0);
        }
    }

    public List<BlockPos> points() {
        List<BlockPos> out = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            out.add(BlockPos.of(points.getLong(i)));
        }
        return out;
    }

    public int size() {
        return points.size();
    }

    public void clear() {
        points.clear();
    }

    public void copyFrom(RouteLog other) {
        points.clear();
        points.addAll(other.points);
    }

    public void save(CompoundTag tag, String key) {
        tag.put(key, new LongArrayTag(points.toLongArray()));
    }

    public void load(CompoundTag tag, String key) {
        points.clear();
        if (tag.contains(key, Tag.TAG_LONG_ARRAY)) {
            for (long l : tag.getLongArray(key)) {
                points.add(l);
            }
        }
    }
}
