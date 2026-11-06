package io.github.jitbyjones.thewake.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jitbyjones.thewake.registry.WakeContent;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * Its places in your world, out where nobody built them: a lookout tower with a camera at the top;
 * a video shop's front, closed, with nothing behind it but a counter; a waiting room in a field,
 * every chair hung with string; a bedroom with three walls in the middle of the woods; a hallway
 * of doors standing on its own, with one more door at the end.
 */
public class LiminalStructure extends Structure {
    public enum Kind implements StringRepresentable {
        WATCHTOWER(7, 19, 7),
        VIDEO_STORE(9, 7, 8),
        WAITING_ROOM(11, 6, 10),
        BEDROOM(7, 5, 7),
        HALLWAY(5, 6, 26);

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        public final int width;
        public final int height;
        public final int depth;

        Kind(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final MapCodec<LiminalStructure> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(settingsCodec(i), Kind.CODEC.fieldOf("kind").forGetter(s -> s.kind)).apply(i, LiminalStructure::new));

    private final Kind kind;

    public LiminalStructure(StructureSettings settings, Kind kind) {
        super(settings);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        ChunkPos chunk = context.chunkPos();
        ChunkGenerator generator = context.chunkGenerator();
        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(context.random());
        BlockPos corner = new BlockPos(chunk.getMinBlockX() + 2, 0, chunk.getMinBlockZ() + 2);
        // Where it will actually stand: turned east or west, it is long the other way.
        BoundingBox foot = LiminalPiece.footprint(kind, corner, facing);
        int[][] points = {
                {foot.minX(), foot.minZ()}, {foot.maxX(), foot.minZ()}, {foot.minX(), foot.maxZ()}, {foot.maxX(), foot.maxZ()},
                {(foot.minX() + foot.maxX()) / 2, (foot.minZ() + foot.maxZ()) / 2}};
        // Level enough (its corners and middle within a few blocks of each other), and dry.
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int[] p : points) {
            int ground = generator.getFirstOccupiedHeight(p[0], p[1], Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
            int top = generator.getFirstOccupiedHeight(p[0], p[1], Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
            if (top != ground) {
                // Water over that part.
                return Optional.empty();
            }
            min = Math.min(min, ground);
            max = Math.max(max, ground);
        }
        if (max - min > 6 || min <= generator.getSeaLevel()) {
            return Optional.empty();
        }
        BlockPos origin = new BlockPos(corner.getX(), min, corner.getZ());
        return Optional.of(new GenerationStub(origin, builder -> builder.addPiece(new LiminalPiece(kind, origin, facing))));
    }

    @Override
    public StructureType<?> type() {
        return WakeContent.LIMINAL.get();
    }
}
