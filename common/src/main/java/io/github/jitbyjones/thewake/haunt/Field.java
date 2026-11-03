package io.github.jitbyjones.thewake.haunt;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.function.LongPredicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every way to the player through the blocks around them: a breadth-first tree rooted at their
 * feet. Following a cell's parent always leads one step closer. Built when a lapse begins, and
 * again only if they have moved.
 *
 * <p>It goes where a crawling thing two blocks tall can go. Doors do not stop it: it opens them
 * while you are not looking, and closes them again.
 */
final class Field {
    private static final int[][] STEPS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    final BlockPos root;
    private final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap distance = new Long2IntOpenHashMap();

    private Field(BlockPos root) {
        this.root = root;
        distance.defaultReturnValue(-1);
    }

    static Field build(ServerLevel level, BlockPos root, int radius, int maxNodes) {
        Field field = new Field(root.immutable());
        BlockPos start = walkable(level, root) ? root : nearbyWalkable(level, root);
        if (start == null) {
            return field;
        }
        long s = start.asLong();
        field.distance.put(s, 0);
        field.parent.put(s, s);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(s);
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        int radiusSq = radius * radius;
        // Each cell is asked about from up to eight neighbours: remember the answers.
        Long2ByteOpenHashMap known = new Long2ByteOpenHashMap();
        while (!queue.isEmpty() && field.distance.size() < maxNodes) {
            long cell = queue.dequeueLong();
            int d = field.distance.get(cell);
            int x = BlockPos.getX(cell);
            int y = BlockPos.getY(cell);
            int z = BlockPos.getZ(cell);
            for (int[] step : STEPS) {
                boolean diagonal = step[0] != 0 && step[1] != 0;
                for (int dy = diagonal ? 0 : -1; dy <= (diagonal ? 0 : 1); dy++) {
                    int nx = x + step[0];
                    int ny = y + dy;
                    int nz = z + step[1];
                    int ox = nx - root.getX();
                    int oz = nz - root.getZ();
                    if (ox * ox + oz * oz > radiusSq || Math.abs(ny - root.getY()) > 12) {
                        continue;
                    }
                    long n = BlockPos.asLong(nx, ny, nz);
                    if (field.distance.containsKey(n)) {
                        continue;
                    }
                    if (!cachedWalkable(level, known, next, nx, ny, nz)) {
                        continue;
                    }
                    if (dy > 0 && !passable(level, next.set(x, y + 2, z))) {
                        continue; // no headroom to climb
                    }
                    if (diagonal && (!cachedWalkable(level, known, next, x + step[0], ny, z)
                            || !cachedWalkable(level, known, next, x, ny, z + step[1]))) {
                        continue; // no squeezing between two corners
                    }
                    field.distance.put(n, d + 1);
                    field.parent.put(n, cell);
                    queue.enqueue(n);
                }
            }
        }
        return field;
    }

    boolean contains(BlockPos cell) {
        return distance.containsKey(cell.asLong());
    }

    /** Steps to the player, or -1 if it cannot get there from here. */
    int distance(BlockPos cell) {
        return distance.get(cell.asLong());
    }

    /** One step toward the player. */
    @Nullable
    BlockPos toward(BlockPos cell) {
        long key = cell.asLong();
        if (!parent.containsKey(key)) {
            return null;
        }
        return BlockPos.of(parent.get(key));
    }

    /** One step away from the player: any neighbour whose way home leads through here. */
    @Nullable
    BlockPos away(BlockPos cell) {
        long key = cell.asLong();
        int d = distance.get(key);
        if (d < 0) {
            return null;
        }
        for (Long2LongOpenHashMap.Entry e : parent.long2LongEntrySet()) {
            if (e.getLongValue() == key && e.getLongKey() != key) {
                return BlockPos.of(e.getLongKey());
            }
        }
        return null;
    }

    /** The reachable cell nearest {@code near} (within {@code within} blocks), or null. */
    @Nullable
    BlockPos nearest(BlockPos near, double within) {
        BlockPos best = null;
        double bestSq = within * within;
        for (long cell : distance.keySet()) {
            double dx = BlockPos.getX(cell) - near.getX();
            double dy = BlockPos.getY(cell) - near.getY();
            double dz = BlockPos.getZ(cell) - near.getZ();
            double sq = dx * dx + dy * dy * 4.0 + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = BlockPos.of(cell);
            }
        }
        return best;
    }

    /** Cells between {@code min} and {@code max} steps away, matching {@code test}; a random one. */
    @Nullable
    BlockPos pick(int min, int max, LongPredicate test, net.minecraft.util.RandomSource random) {
        long chosen = Long.MIN_VALUE;
        int seen = 0;
        for (Long2IntOpenHashMap.Entry e : distance.long2IntEntrySet()) {
            int d = e.getIntValue();
            if (d < min || d > max || !test.test(e.getLongKey())) {
                continue;
            }
            seen++;
            if (random.nextInt(seen) == 0) {
                chosen = e.getLongKey();
            }
        }
        return chosen == Long.MIN_VALUE ? null : BlockPos.of(chosen);
    }

    int size() {
        return distance.size();
    }

    // ---- what it can walk through ---------------------------------------------------------

    private static boolean cachedWalkable(ServerLevel level, Long2ByteOpenHashMap known, BlockPos.MutableBlockPos scratch, int x, int y,
            int z) {
        long key = BlockPos.asLong(x, y, z);
        byte v = known.getOrDefault(key, (byte) -1);
        if (v < 0) {
            v = walkable(level, scratch.set(x, y, z)) ? (byte) 1 : (byte) 0;
            known.put(key, v);
        }
        return v == 1;
    }

    static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof TrapDoorBlock) {
            return true;
        }
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** Floor below, two blocks of room. */
    static boolean walkable(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet)) {
            return false;
        }
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (ground.getCollisionShape(level, below).isEmpty() || ground.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            return false;
        }
        return passable(level, feet) && passable(level, feet.above());
    }

    /** Blocks of room above the floor at {@code feet}, up to {@code max}. */
    static int clearance(ServerLevel level, BlockPos feet, int max) {
        BlockPos.MutableBlockPos p = feet.mutable();
        int room = 0;
        while (room < max && passable(level, p)) {
            room++;
            p.move(0, 1, 0);
        }
        return room;
    }

    @Nullable
    private static BlockPos nearbyWalkable(ServerLevel level, BlockPos root) {
        for (int dy = 0; dy >= -2; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = root.offset(dx, dy, dz);
                    if (walkable(level, p)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }
}
