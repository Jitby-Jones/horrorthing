package io.github.jitbyjones.thewake.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jitbyjones.thewake.block.FacingBlock;
import io.github.jitbyjones.thewake.block.FluorescentLightBlock;
import io.github.jitbyjones.thewake.haunt.WakeDimensions;
import io.github.jitbyjones.thewake.registry.WakeContent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * The Archive. The back room of a video rental shop, after hours, that does not end: low ceilings
 * of stained tiles, fluorescent panels (some out, some going), worn carpet, walls of yellowed
 * wallpaper, and shelf after shelf of tapes, every spine a night somebody slept through.
 *
 * <p>Laid out on a grid of seven-block rooms. Each wall between two rooms is solid, a doorway, or
 * missing, by a hash of where it is, so the whole thing is one endless, irregular warren that is
 * the same every time. Now and then a room is a returns desk (your tape is in one of them) or has
 * a door marked out.
 */
public class ArchiveGenerator extends ChunkGenerator {
    public static final MapCodec<ArchiveGenerator> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)).apply(i, i.stable(ArchiveGenerator::new)));

    public static final int CELL = 7;
    private static final int FLOOR = WakeDimensions.ARCHIVE_FLOOR;
    private static final int CEILING = FLOOR + 5;

    public enum Room { STACKS, AISLE, EMPTY, DESK, EXIT, DARK }

    public ArchiveGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ---- the plan -------------------------------------------------------------------------

    private static long hash(int x, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    private static double unit(int x, int z, int salt) {
        return (hash(x, z, salt) >>> 11) * 0x1.0p-53;
    }

    public static Room room(int cx, int cz) {
        double r = unit(cx, cz, 7);
        if (r < 0.022) {
            return Room.DESK;
        }
        if (r < 0.036) {
            return Room.EXIT;
        }
        if (r < 0.09) {
            return Room.DARK;
        }
        if (r < 0.5) {
            return Room.STACKS;
        }
        if (r < 0.72) {
            return Room.AISLE;
        }
        return Room.EMPTY;
    }

    /** The wall on the west (x) or north (z) side of a room: 0 solid, 1 doorway, 2 none. */
    private static int wall(int cx, int cz, boolean west) {
        double r = unit(cx, cz, west ? 11 : 13);
        if (r < 0.18) {
            return 2;
        }
        if (r < 0.62) {
            return 1;
        }
        return 0;
    }

    /** Where in its wall the doorway is (offset 1..3 of the six cells). */
    private static int doorAt(int cx, int cz, boolean west) {
        return 1 + (int) (unit(cx, cz, west ? 17 : 19) * 4.0);
    }

    // ---- building -------------------------------------------------------------------------

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structures, ChunkAccess chunk) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockState wallpaper = WakeContent.WALLPAPER.get().defaultBlockState();
        BlockState stained = WakeContent.STAINED_WALLPAPER.get().defaultBlockState();
        BlockState tile = WakeContent.CEILING_TILE.get().defaultBlockState();
        BlockState lit = WakeContent.FLUORESCENT_LIGHT.get().defaultBlockState();
        BlockState dead = lit.setValue(FluorescentLightBlock.LIT, false);
        BlockState carpet = Blocks.BROWN_WOOL.defaultBlockState();
        BlockState worn = Blocks.GRAY_WOOL.defaultBlockState();
        BlockState under = Blocks.SMOOTH_STONE.defaultBlockState();
        BlockState shelf = WakeContent.TAPE_SHELF.get().defaultBlockState();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                int cx = Math.floorDiv(x, CELL);
                int cz = Math.floorDiv(z, CELL);
                int ix = Math.floorMod(x, CELL);
                int iz = Math.floorMod(z, CELL);
                Room room = room(cx, cz);
                // Floor and the slab under it.
                for (int y = FLOOR - 4; y < FLOOR; y++) {
                    set(chunk, m, lx, y, lz, under, ocean, surface);
                }
                set(chunk, m, lx, FLOOR, lz, unit(x, z, 23) < 0.12 ? worn : carpet, ocean, surface);
                // Ceiling and above it.
                boolean centre = ix == 3 && iz == 3;
                BlockState ceiling = tile;
                if (centre && room != Room.DARK) {
                    ceiling = unit(cx, cz, 29) < 0.22 ? dead : lit;
                } else if ((ix == 3 && (iz == 1 || iz == 5) || iz == 3 && (ix == 1 || ix == 5)) && room == Room.AISLE && unit(x, z, 31) < 0.3) {
                    ceiling = lit;
                }
                set(chunk, m, lx, CEILING, lz, ceiling, ocean, surface);
                for (int y = CEILING + 1; y <= CEILING + 3; y++) {
                    set(chunk, m, lx, y, lz, under, ocean, surface);
                }
                // Walls.
                BlockState paper = unit(x, z, 37) < 0.18 || room == Room.DARK ? stained : wallpaper;
                boolean wallHere = false;
                if (ix == 0 && iz == 0) {
                    wallHere = true;
                } else if (ix == 0) {
                    int w = wall(cx, cz, true);
                    int d = doorAt(cx, cz, true);
                    wallHere = w == 0 || w == 1 && (iz < d || iz > d + 1);
                } else if (iz == 0) {
                    int w = wall(cx, cz, false);
                    int d = doorAt(cx, cz, false);
                    wallHere = w == 0 || w == 1 && (ix < d || ix > d + 1);
                }
                if (wallHere) {
                    for (int y = FLOOR + 1; y < CEILING; y++) {
                        set(chunk, m, lx, y, lz, paper, ocean, surface);
                    }
                    continue;
                }
                // What is in the room.
                BlockState thing = contents(room, cx, cz, ix, iz, shelf);
                if (thing != null) {
                    int top = thing.is(WakeContent.TAPE_SHELF.get()) ? CEILING - 1 : FLOOR + 1;
                    for (int y = FLOOR + 1; y <= top; y++) {
                        set(chunk, m, lx, y, lz, thing, ocean, surface);
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * Shelves along the walls (not across doorways), a free-standing row down the middle of an
     * aisle, the desk and its slot, the marked door.
     */
    private static BlockState contents(Room room, int cx, int cz, int ix, int iz, BlockState shelf) {
        switch (room) {
            case STACKS -> {
                boolean westOpen = wall(cx, cz, true) != 0;
                boolean northOpen = wall(cx, cz, false) != 0;
                if (ix == 1 && iz >= 1 && iz <= 5 && !(westOpen && iz >= doorAt(cx, cz, true) - 1 && iz <= doorAt(cx, cz, true) + 2)) {
                    return shelf.setValue(FacingBlock.FACING, Direction.EAST);
                }
                if (iz == 1 && ix >= 2 && ix <= 5 && !(northOpen && ix >= doorAt(cx, cz, false) - 1 && ix <= doorAt(cx, cz, false) + 2)) {
                    return shelf.setValue(FacingBlock.FACING, Direction.SOUTH);
                }
                return null;
            }
            case AISLE -> {
                if (ix == 3 && iz >= 2 && iz <= 4) {
                    return shelf.setValue(FacingBlock.FACING, (iz & 1) == 0 ? Direction.EAST : Direction.WEST);
                }
                return null;
            }
            case DESK -> {
                // A counter across the room with the slot in it.
                if (iz == 4 && ix >= 2 && ix <= 5) {
                    return ix == 3 ? WakeContent.RETURNS_SLOT.get().defaultBlockState().setValue(FacingBlock.FACING, Direction.NORTH)
                            : Blocks.DARK_OAK_PLANKS.defaultBlockState();
                }
                if (iz == 5 && ix == 4) {
                    return WakeContent.VCR.get().defaultBlockState();
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static void set(ChunkAccess chunk, BlockPos.MutableBlockPos m, int lx, int y, int lz, BlockState state, Heightmap a, Heightmap b) {
        chunk.setBlockState(m.set(lx, y, lz), state, false);
        a.update(lx, y, lz, state);
        b.update(lx, y, lz, state);
    }

    /**
     * After the terrain: the marked doors (two blocks tall, which a chunk can hold, but placed here
     * so the halves agree).
     */
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int cx = Math.floorDiv(baseX, CELL); cx <= Math.floorDiv(baseX + 15, CELL); cx++) {
            for (int cz = Math.floorDiv(baseZ, CELL); cz <= Math.floorDiv(baseZ + 15, CELL); cz++) {
                if (room(cx, cz) != Room.EXIT || wall(cx, cz, false) != 0) {
                    continue;
                }
                // In the middle of the room's north wall, opening into the room.
                int x = cx * CELL + 3;
                int z = cz * CELL;
                if (x < baseX || x > baseX + 15 || z < baseZ || z > baseZ + 15) {
                    continue;
                }
                BlockPos lower = new BlockPos(x, FLOOR + 1, z);
                BlockState door = WakeContent.EXIT_DOOR.get().defaultBlockState()
                        .setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.SOUTH);
                level.setBlock(lower, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
                level.setBlock(lower.above(), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
            }
        }
    }

    /** A free spot on the floor near {@code x, z}: the middle of a room that is not a wall. */
    public static BlockPos entry(int x, int z) {
        int cx = Math.floorDiv(x, CELL);
        int cz = Math.floorDiv(z, CELL);
        for (int r = 0; r < 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    Room room = room(cx + dx, cz + dz);
                    if (room == Room.EMPTY || room == Room.STACKS || room == Room.DARK) {
                        return new BlockPos((cx + dx) * CELL + 4, FLOOR + 1, (cz + dz) * CELL + 4);
                    }
                }
            }
        }
        return new BlockPos(cx * CELL + 4, FLOOR + 1, cz * CELL + 4);
    }

    // ---- the rest of the interface ----------------------------------------------------------

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures,
            ChunkAccess chunk, GenerationStep.Carving step) {
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public int getGenDepth() {
        return 64;
    }

    @Override
    public int getSeaLevel() {
        return 0;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        return CEILING + 4;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        BlockState[] states = new BlockState[height.getHeight()];
        for (int i = 0; i < states.length; i++) {
            int y = height.getMinBuildHeight() + i;
            states[i] = y >= FLOOR - 4 && y <= FLOOR || y >= CEILING && y <= CEILING + 3 ? Blocks.SMOOTH_STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }
        return new NoiseColumn(height.getMinBuildHeight(), states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        int cx = Math.floorDiv(pos.getX(), CELL);
        int cz = Math.floorDiv(pos.getZ(), CELL);
        info.add("Archive room " + cx + "," + cz + ": " + room(cx, cz).name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Unused helper: distance in rooms. */
    static int rooms(int ax, int az, int bx, int bz) {
        return Mth.abs(Math.floorDiv(ax, CELL) - Math.floorDiv(bx, CELL)) + Mth.abs(Math.floorDiv(az, CELL) - Math.floorDiv(bz, CELL));
    }
}
