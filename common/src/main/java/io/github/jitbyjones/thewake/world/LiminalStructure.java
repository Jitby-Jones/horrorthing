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
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.levelgen.Heightmap;
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
        int x = chunk.getMinBlockX() + 2;
        int z = chunk.getMinBlockZ() + 2;
        // Level enough: the four corners within a few blocks of each other, and dry.
        int[] heights = new int[4];
        int[][] corners = {{0, 0}, {kind.width, 0}, {0, kind.depth}, {kind.width, kind.depth}};
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            heights[i] = context.chunkGenerator().getFirstOccupiedHeight(x + corners[i][0], z + corners[i][1], Heightmap.Types.WORLD_SURFACE_WG,
                    context.heightAccessor(), context.randomState());
            min = Math.min(min, heights[i]);
            max = Math.max(max, heights[i]);
        }
        if (max - min > 6) {
            return Optional.empty();
        }
        int y = min;
        NoiseColumn column = context.chunkGenerator().getBaseColumn(x + kind.width / 2, z + kind.depth / 2, context.heightAccessor(), context.randomState());
        if (!column.getBlock(y - 1).getFluidState().isEmpty() || y <= context.chunkGenerator().getSeaLevel()) {
            return Optional.empty();
        }
        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(context.random());
        BlockPos origin = new BlockPos(x, y, z);
        return Optional.of(new GenerationStub(origin, builder -> builder.addPiece(new LiminalPiece(kind, origin, facing))));
    }

    @Override
    public StructureType<?> type() {
        return WakeContent.LIMINAL.get();
    }
}
