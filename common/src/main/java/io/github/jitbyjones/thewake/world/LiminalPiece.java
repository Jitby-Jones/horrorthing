package io.github.jitbyjones.thewake.world;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.block.FacingBlock;
import io.github.jitbyjones.thewake.block.FluorescentLightBlock;
import io.github.jitbyjones.thewake.registry.WakeContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * One of its places, built block by block. Local coordinates: x across, y up, z from the front
 * (where you come in) to the back. In local terms +z is north and +x is east; the piece's
 * orientation turns everything, block states included.
 */
public class LiminalPiece extends StructurePiece {
    private final LiminalStructure.Kind kind;

    public LiminalPiece(LiminalStructure.Kind kind, BlockPos origin, Direction facing) {
        super(WakeContent.LIMINAL_PIECE.get(), 0, makeBoundingBox(origin.getX(), origin.getY(), origin.getZ(), facing, kind.width, kind.height, kind.depth));
        this.kind = kind;
        setOrientation(facing);
    }

    public LiminalPiece(CompoundTag tag) {
        super(WakeContent.LIMINAL_PIECE.get(), tag);
        LiminalStructure.Kind k;
        try {
            k = LiminalStructure.Kind.valueOf(tag.getString("Kind"));
        } catch (IllegalArgumentException e) {
            k = LiminalStructure.Kind.BEDROOM;
        }
        this.kind = k;
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString("Kind", kind.name());
    }

    public LiminalStructure.Kind kind() {
        return kind;
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random, BoundingBox box,
            ChunkPos chunkPos, BlockPos pivot) {
        switch (kind) {
            case WATCHTOWER -> watchtower(level, box, random);
            case VIDEO_STORE -> videoStore(level, box, random);
            case WAITING_ROOM -> waitingRoom(level, box, random);
            case BEDROOM -> bedroom(level, box, random);
            case HALLWAY -> hallway(level, box, random);
        }
    }

    // ---- the lookout ------------------------------------------------------------------------

    /**
     * A wooden fire lookout, gone grey, with a ladder up the middle. At the top, a chest: an old
     * camera and its bulbs, and a note. Down one of the legs, long scratches, all the way up.
     */
    private void watchtower(WorldGenLevel level, BoundingBox box, RandomSource random) {
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState scratched = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        BlockState slab = Blocks.SPRUCE_SLAB.defaultBlockState();
        air(level, box, 0, 0, 0, 6, 18, 6);
        int deck = 12;
        int[][] legs = {{1, 1}, {5, 1}, {1, 5}, {5, 5}};
        for (int[] leg : legs) {
            for (int y = 0; y < deck; y++) {
                // The back leg, scratched from the bottom to the top.
                boolean marks = leg[0] == 5 && leg[1] == 5 && random.nextInt(3) > 0;
                placeBlock(level, marks ? scratched : log, leg[0], y, leg[1], box);
            }
            fillColumnDown(level, log, leg[0], -1, leg[1], box);
            for (int y = deck + 1; y <= deck + 3; y++) {
                placeBlock(level, fence, leg[0], y, leg[1], box);
            }
        }
        // Braces.
        for (int y : new int[] {4, 8}) {
            for (int i = 2; i <= 4; i++) {
                placeBlock(level, fence, i, y, 1, box);
                placeBlock(level, fence, i, y, 5, box);
                placeBlock(level, fence, 1, y, i, box);
                placeBlock(level, fence, 5, y, i, box);
            }
        }
        // The ladder, up a board in the middle.
        for (int y = 0; y < deck; y++) {
            placeBlock(level, planks, 3, y, 2, box);
            placeBlock(level, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH), 3, y, 1, box);
        }
        fillColumnDown(level, planks, 3, -1, 2, box);
        // The deck, with a hole for the ladder.
        fill(level, box, 0, deck, 0, 6, deck, 6, planks);
        placeBlock(level, Blocks.AIR.defaultBlockState(), 3, deck, 1, box);
        placeBlock(level, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH), 3, deck, 1, box);
        for (int i = 0; i <= 6; i++) {
            if (i != 3) {
                placeBlock(level, fence, i, deck + 1, 0, box);
            }
            placeBlock(level, fence, i, deck + 1, 6, box);
            placeBlock(level, fence, 0, deck + 1, i, box);
            placeBlock(level, fence, 6, deck + 1, i, box);
        }
        fill(level, box, 0, deck + 4, 0, 6, deck + 4, 6, slab);
        createChest(level, box, random, 5, deck + 1, 4, loot("watchtower"));
        // Somebody counted. There are more marks than there were nights.
        sign(level, box, 1, deck + 1, 4, Direction.EAST, "|||| |||| ||||", "|||| |||| ||||", "|||| ||||", "it doesn't stop");
    }

    // ---- the video shop ---------------------------------------------------------------------

    /**
     * The front of a video rental shop, closed, standing by itself. Through the glass: shelves of
     * tapes, a counter, a machine under a dead television. The sign says to return all tapes.
     */
    private void videoStore(WorldGenLevel level, BoundingBox box, RandomSource random) {
        BlockState wall = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState trim = Blocks.RED_CONCRETE.defaultBlockState();
        BlockState floor = Blocks.POLISHED_ANDESITE.defaultBlockState();
        BlockState glass = Blocks.GLASS_PANE.defaultBlockState();
        air(level, box, 0, 1, 0, 8, 6, 7);
        fill(level, box, 0, 0, 0, 8, 0, 7, floor);
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 7; z++) {
                fillColumnDown(level, floor, x, -1, z, box);
            }
        }
        // Walls, a red band under the roof.
        fill(level, box, 0, 1, 0, 8, 4, 0, wall);
        fill(level, box, 0, 1, 7, 8, 4, 7, wall);
        fill(level, box, 0, 1, 0, 0, 4, 7, wall);
        fill(level, box, 8, 1, 0, 8, 4, 7, wall);
        fill(level, box, 0, 5, 0, 8, 5, 7, trim);
        fill(level, box, 1, 6, 1, 7, 6, 6, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        // The windows and the door.
        fill(level, box, 1, 2, 0, 3, 3, 0, glass);
        fill(level, box, 5, 2, 0, 7, 3, 0, glass);
        door(level, box, Blocks.DARK_OAK_DOOR.defaultBlockState(), 4, 1, 0, Direction.SOUTH);
        // After-hours returns, by the door: a slot, and the sign.
        placeBlock(level, Blocks.IRON_TRAPDOOR.defaultBlockState(), 3, 1, -0 + 0, box);
        sign(level, box, 5, 4, 0, Direction.SOUTH, "AFTER HOURS", "RETURNS", "", "please rewind");
        // Inside: the shelves, the counter, the machine.
        BlockState shelf = WakeContent.TAPE_SHELF.get().defaultBlockState();
        for (int x = 1; x <= 7; x++) {
            for (int y = 1; y <= 3; y++) {
                placeBlock(level, shelf.setValue(FacingBlock.FACING, Direction.SOUTH), x, y, 6, box);
            }
        }
        for (int z = 2; z <= 4; z++) {
            for (int y = 1; y <= 2; y++) {
                placeBlock(level, shelf.setValue(FacingBlock.FACING, Direction.EAST), 1, y, z, box);
            }
        }
        fill(level, box, 5, 1, 3, 7, 1, 3, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        placeBlock(level, WakeContent.VCR.get().defaultBlockState().setValue(FacingBlock.FACING, Direction.SOUTH), 6, 2, 3, box);
        placeBlock(level, Blocks.BLACK_CONCRETE.defaultBlockState(), 6, 3, 4, box);
        createChest(level, box, random, 7, 1, 4, loot("video_store"));
        placeBlock(level, Blocks.GRAY_CARPET.defaultBlockState(), 3, 1, 3, box);
        placeBlock(level, Blocks.GRAY_CARPET.defaultBlockState(), 4, 1, 3, box);
        placeBlock(level, Blocks.GRAY_CARPET.defaultBlockState(), 4, 1, 2, box);
        light(level, box, 4, 5, 4, random.nextBoolean());
    }

    // ---- the waiting room -------------------------------------------------------------------

    /**
     * A waiting room in a field: rows of chairs facing a dead screen, every chair with a string
     * hanging over it from the ceiling, as if for a hand to pick it up by. At the desk, a bell, and
     * a book open on a stand: please wait.
     */
    private void waitingRoom(WorldGenLevel level, BoundingBox box, RandomSource random) {
        BlockState paper = WakeContent.WALLPAPER.get().defaultBlockState();
        BlockState stained = WakeContent.STAINED_WALLPAPER.get().defaultBlockState();
        BlockState tile = WakeContent.CEILING_TILE.get().defaultBlockState();
        air(level, box, 0, 1, 0, 10, 5, 9);
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 9; z++) {
                BlockState f = ((x + z) & 1) == 0 ? Blocks.WHITE_CONCRETE.defaultBlockState() : Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
                placeBlock(level, f, x, 0, z, box);
                fillColumnDown(level, Blocks.STONE.defaultBlockState(), x, -1, z, box);
            }
        }
        for (int y = 1; y <= 4; y++) {
            for (int i = 0; i <= 10; i++) {
                placeBlock(level, random.nextInt(6) == 0 ? stained : paper, i, y, 0, box);
                placeBlock(level, random.nextInt(6) == 0 ? stained : paper, i, y, 9, box);
            }
            for (int i = 0; i <= 9; i++) {
                placeBlock(level, random.nextInt(6) == 0 ? stained : paper, 0, y, i, box);
                placeBlock(level, random.nextInt(6) == 0 ? stained : paper, 10, y, i, box);
            }
        }
        fill(level, box, 0, 5, 0, 10, 5, 9, tile);
        door(level, box, Blocks.OAK_DOOR.defaultBlockState(), 5, 1, 0, Direction.SOUTH);
        // The screen on the back wall.
        fill(level, box, 4, 2, 8, 6, 3, 8, Blocks.BLACK_CONCRETE.defaultBlockState());
        // Chairs facing it, and the strings.
        BlockState chair = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        for (int z : new int[] {3, 5, 7}) {
            for (int x = 2; x <= 8; x++) {
                if (x == 5) {
                    continue;
                }
                placeBlock(level, chair, x, 1, z, box);
                placeBlock(level, Blocks.CHAIN.defaultBlockState(), x, 4, z, box);
                if (random.nextInt(3) == 0) {
                    placeBlock(level, Blocks.CHAIN.defaultBlockState(), x, 3, z, box);
                }
            }
        }
        // The desk.
        fill(level, box, 1, 1, 1, 3, 1, 1, Blocks.BIRCH_PLANKS.defaultBlockState());
        placeBlock(level, Blocks.BELL.defaultBlockState(), 1, 2, 1, box);
        placeBlock(level, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.NORTH).setValue(LecternBlock.HAS_BOOK, true),
                3, 1, 2, box);
        lectern(level, box, 3, 1, 2, book("Please Wait",
                "Please wait.\n\nYour number will be called.\n\nNOW SERVING:\n\n40",
                "It moved every time I looked away. A menu, a blink, a minute asleep. Never while I watched.",
                "It wears a face. Somebody's face, on a card, cut out round the eyes, held on at the corners with four pins.",
                "A thing with a face can be looked at. Pull the pins and there is nobody left there to look at.",
                "But it has to be still. It has to be blind. Something bright, right in its eyes.",
                "Then it will make you look away. It knows how. Walk backwards. Keep your eyes on it."));
        sign(level, box, 9, 3, 1, Direction.WEST, "TAKE A", "NUMBER", "", "");
        light(level, box, 3, 5, 4, true);
        light(level, box, 7, 5, 6, false);
    }

    // ---- the bedroom ------------------------------------------------------------------------

    /**
     * A bedroom with three walls, in the woods: wallpaper, a rug, a made bed, a candle that was
     * blown out. Lie down in it at night and you do not wake up anywhere you know.
     */
    private void bedroom(WorldGenLevel level, BoundingBox box, RandomSource random) {
        BlockState paper = WakeContent.WALLPAPER.get().defaultBlockState();
        BlockState stained = WakeContent.STAINED_WALLPAPER.get().defaultBlockState();
        air(level, box, 0, 1, 0, 6, 4, 6);
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                placeBlock(level, Blocks.OAK_PLANKS.defaultBlockState(), x, 0, z, box);
                fillColumnDown(level, Blocks.DIRT.defaultBlockState(), x, -1, z, box);
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int i = 0; i <= 6; i++) {
                placeBlock(level, random.nextInt(5) == 0 ? stained : paper, i, y, 6, box);
                placeBlock(level, random.nextInt(5) == 0 ? stained : paper, 0, y, i, box);
                placeBlock(level, random.nextInt(5) == 0 ? stained : paper, 6, y, i, box);
            }
        }
        fill(level, box, 0, 4, 1, 6, 4, 6, WakeContent.CEILING_TILE.get().defaultBlockState());
        placeBlock(level, Blocks.GLASS_PANE.defaultBlockState(), 0, 2, 3, box);
        fill(level, box, 2, 1, 2, 4, 1, 4, Blocks.RED_CARPET.defaultBlockState());
        // The bed, its head to the back wall.
        BlockState bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        placeBlock(level, bed.setValue(BedBlock.PART, BedPart.FOOT), 3, 1, 4, box);
        placeBlock(level, bed.setValue(BedBlock.PART, BedPart.HEAD), 3, 1, 5, box);
        placeBlock(level, Blocks.SPRUCE_PLANKS.defaultBlockState(), 2, 1, 5, box);
        placeBlock(level, Blocks.WHITE_CANDLE.defaultBlockState().setValue(CandleBlock.LIT, false), 2, 2, 5, box);
        createChest(level, box, random, 5, 1, 5, loot("bedroom"));
        // A wardrobe door in the wall that opens onto the wall.
        door(level, box, Blocks.SPRUCE_DOOR.defaultBlockState(), 6, 1, 3, Direction.WEST);
    }

    // ---- the hallway ------------------------------------------------------------------------

    /**
     * A hallway with nothing round it, standing in a field: carpet, wallpaper, doors down both
     * sides that open onto wallpaper. At the far end, one door with no handle and no number.
     */
    private void hallway(WorldGenLevel level, BoundingBox box, RandomSource random) {
        BlockState paper = WakeContent.WALLPAPER.get().defaultBlockState();
        BlockState stained = WakeContent.STAINED_WALLPAPER.get().defaultBlockState();
        BlockState tile = WakeContent.CEILING_TILE.get().defaultBlockState();
        int end = kind.depth - 1;
        air(level, box, 1, 1, 0, 3, 4, end);
        for (int z = 0; z <= end; z++) {
            for (int x = 0; x <= 4; x++) {
                placeBlock(level, x >= 1 && x <= 3 ? Blocks.RED_WOOL.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState(), x, 0, z, box);
                fillColumnDown(level, Blocks.DARK_OAK_PLANKS.defaultBlockState(), x, -1, z, box);
                placeBlock(level, tile, x, 5, z, box);
            }
            for (int y = 1; y <= 4; y++) {
                placeBlock(level, random.nextInt(7) == 0 ? stained : paper, 0, y, z, box);
                placeBlock(level, random.nextInt(7) == 0 ? stained : paper, 4, y, z, box);
            }
        }
        // Doors down both sides, onto the wall behind them.
        for (int z = 3; z < end - 1; z += 4) {
            door(level, box, Blocks.OAK_DOOR.defaultBlockState(), 0, 1, z, Direction.EAST);
            door(level, box, Blocks.OAK_DOOR.defaultBlockState(), 4, 1, z + 2 < end ? z + 2 : z, Direction.WEST);
        }
        for (int z = 2; z < end; z += 6) {
            light(level, box, 2, 5, z, random.nextInt(4) > 0);
        }
        // The end wall, and the door in it.
        for (int x = 0; x <= 4; x++) {
            for (int y = 1; y <= 4; y++) {
                placeBlock(level, paper, x, y, end, box);
            }
        }
        door(level, box, WakeContent.UNMARKED_DOOR.get().defaultBlockState(), 2, 1, end, Direction.SOUTH);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void fill(WorldGenLevel level, BoundingBox box, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        generateBox(level, box, x0, y0, z0, x1, y1, z1, state, state, false);
    }

    private void air(WorldGenLevel level, BoundingBox box, int x0, int y0, int z0, int x1, int y1, int z1) {
        generateBox(level, box, x0, y0, z0, x1, y1, z1, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
    }

    private void door(WorldGenLevel level, BoundingBox box, BlockState door, int x, int y, int z, Direction facing) {
        BlockState d = door.setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT).setValue(DoorBlock.OPEN, false);
        placeBlock(level, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), x, y, z, box);
        placeBlock(level, d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), x, y + 1, z, box);
    }

    private void light(WorldGenLevel level, BoundingBox box, int x, int y, int z, boolean on) {
        placeBlock(level, WakeContent.FLUORESCENT_LIGHT.get().defaultBlockState().setValue(FluorescentLightBlock.LIT, on), x, y, z, box);
    }

    private void sign(WorldGenLevel level, BoundingBox box, int x, int y, int z, Direction facing, String... lines) {
        BlockPos pos = getWorldPos(x, y, z);
        if (!box.isInside(pos)) {
            return;
        }
        placeBlock(level, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing), x, y, z, box);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            for (int i = 0; i < Math.min(4, lines.length); i++) {
                text = text.setMessage(i, Component.literal(lines[i]));
            }
            sign.setText(text, true);
        }
    }

    private void lectern(WorldGenLevel level, BoundingBox box, int x, int y, int z, ItemStack book) {
        BlockPos pos = getWorldPos(x, y, z);
        if (box.isInside(pos) && level.getBlockEntity(pos) instanceof LecternBlockEntity lectern) {
            lectern.setBook(book);
        }
    }

    static ItemStack book(String title, String... pages) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> list = new ArrayList<>();
        for (String page : pages) {
            list.add(Filterable.passThrough(Component.literal(page)));
        }
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), "", 0, list, true));
        return stack;
    }

    private static ResourceKey<LootTable> loot(String name) {
        return ResourceKey.create(Registries.LOOT_TABLE, TheWake.id("chests/" + name));
    }
}
