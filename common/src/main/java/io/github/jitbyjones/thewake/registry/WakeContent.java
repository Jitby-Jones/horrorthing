package io.github.jitbyjones.thewake.registry;

import com.mojang.serialization.MapCodec;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.block.FacingBlock;
import io.github.jitbyjones.thewake.block.FluorescentLightBlock;
import io.github.jitbyjones.thewake.block.PassageDoorBlock;
import io.github.jitbyjones.thewake.block.ReturnsSlotBlock;
import io.github.jitbyjones.thewake.block.VcrBlock;
import io.github.jitbyjones.thewake.entity.FlungBlock;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import io.github.jitbyjones.thewake.item.OldCameraItem;
import io.github.jitbyjones.thewake.item.TapeItem;
import io.github.jitbyjones.thewake.world.ArchiveGenerator;
import io.github.jitbyjones.thewake.world.LiminalPiece;
import io.github.jitbyjones.thewake.world.LiminalStructure;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * The mod's things: its body, the door it throws, the camera you fight it with, your tape and its
 * pins, the blocks its places are built from, and the code that builds them.
 */
public final class WakeContent {
    // ---- entities -------------------------------------------------------------------------
    public static Supplier<EntityType<WakeEntity>> WAKE;
    public static Supplier<EntityType<FlungBlock>> FLUNG_BLOCK;

    // ---- items ----------------------------------------------------------------------------
    /** Its flash blinds it for a moment. */
    public static Supplier<Item> OLD_CAMERA;
    public static Supplier<Item> FLASH_BULB;
    /** One of the four pins that hold its face on. */
    public static Supplier<Item> PIN;
    /** A tape with your name on it: every night you slept through. */
    public static Supplier<Item> TAPE;
    /** Its face, when it came off. */
    public static Supplier<Item> CARD;

    // ---- blocks ---------------------------------------------------------------------------
    public static Supplier<Block> VCR;
    public static Supplier<Block> FLUORESCENT_LIGHT;
    public static Supplier<Block> WALLPAPER;
    public static Supplier<Block> STAINED_WALLPAPER;
    public static Supplier<Block> CEILING_TILE;
    public static Supplier<Block> TAPE_SHELF;
    public static Supplier<Block> RETURNS_SLOT;
    public static Supplier<Block> UNMARKED_DOOR;
    public static Supplier<Block> EXIT_DOOR;
    public static Supplier<Block> PLYWOOD;

    // ---- worldgen -------------------------------------------------------------------------
    public static Supplier<MapCodec<ArchiveGenerator>> ARCHIVE_GENERATOR;
    public static Supplier<StructureType<LiminalStructure>> LIMINAL;
    public static Supplier<StructurePieceType> LIMINAL_PIECE;

    private WakeContent() {}

    static void register() {
        WAKE = TheWake.platform().register(BuiltInRegistries.ENTITY_TYPE, "wake",
                () -> EntityType.Builder.<WakeEntity>of(WakeEntity::new, MobCategory.MISC)
                        .sized(0.7F, 1.95F).eyeHeight(1.85F).clientTrackingRange(10).updateInterval(1)
                        .fireImmune().noSave().noSummon().build(TheWake.id("wake").toString()));
        FLUNG_BLOCK = TheWake.platform().register(BuiltInRegistries.ENTITY_TYPE, "flung_block",
                () -> EntityType.Builder.<FlungBlock>of(FlungBlock::new, MobCategory.MISC)
                        .sized(0.9F, 0.9F).clientTrackingRange(10).updateInterval(1).fireImmune().noSave().noSummon()
                        .build(TheWake.id("flung_block").toString()));
        TheWake.platform().registerAttributes(WAKE, WakeEntity::createAttributes);

        OLD_CAMERA = item("old_camera", () -> new OldCameraItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
        FLASH_BULB = item("flash_bulb", () -> new Item(new Item.Properties().stacksTo(16)));
        PIN = item("pin", () -> new Item(new Item.Properties().stacksTo(4).rarity(Rarity.UNCOMMON)));
        TAPE = item("tape", () -> new TapeItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
        CARD = item("card", () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

        VCR = block("vcr", () -> new VcrBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.5F, 6.0F)
                .sound(SoundType.METAL).noOcclusion()), true);
        FLUORESCENT_LIGHT = block("fluorescent_light", () -> new FluorescentLightBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.QUARTZ).strength(0.6F).sound(SoundType.GLASS).randomTicks()
                .lightLevel(s -> s.getValue(FluorescentLightBlock.LIT) ? 14 : 0)), true);
        WALLPAPER = block("wallpaper", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(1.2F)
                .instrument(NoteBlockInstrument.BASS).sound(SoundType.WOOD)), true);
        STAINED_WALLPAPER = block("stained_wallpaper", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_YELLOW)
                .strength(1.2F).instrument(NoteBlockInstrument.BASS).sound(SoundType.WOOD)), true);
        CEILING_TILE = block("ceiling_tile", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(0.8F)
                .sound(SoundType.CALCITE)), true);
        TAPE_SHELF = block("tape_shelf", () -> new FacingBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.5F)
                .sound(SoundType.WOOD).instrument(NoteBlockInstrument.BASS)), true);
        RETURNS_SLOT = block("returns_slot", () -> new ReturnsSlotBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                .strength(-1.0F, 3600000.0F).sound(SoundType.METAL).noLootTable()), false);
        UNMARKED_DOOR = block("unmarked_door", () -> new PassageDoorBlock(BlockSetType.DARK_OAK, PassageDoorBlock.Leads.ARCHIVE,
                BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(-1.0F, 3600000.0F).noOcclusion().noLootTable()
                        .pushReaction(PushReaction.BLOCK)), false);
        EXIT_DOOR = block("exit_door", () -> new PassageDoorBlock(BlockSetType.IRON, PassageDoorBlock.Leads.HOME,
                BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1.0F, 3600000.0F).noOcclusion().noLootTable()
                        .pushReaction(PushReaction.BLOCK)), false);
        PLYWOOD = block("plywood", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(1.0F)
                .instrument(NoteBlockInstrument.BASS).sound(SoundType.WOOD).ignitedByLava()), true);

        ARCHIVE_GENERATOR = TheWake.platform().register(BuiltInRegistries.CHUNK_GENERATOR, "archive", () -> ArchiveGenerator.CODEC);
        LIMINAL = TheWake.platform().register(BuiltInRegistries.STRUCTURE_TYPE, "liminal", () -> () -> LiminalStructure.CODEC);
        LIMINAL_PIECE = TheWake.platform().register(BuiltInRegistries.STRUCTURE_PIECE, "liminal",
                () -> (StructurePieceType.ContextlessType) LiminalPiece::new);
    }

    private static Supplier<Item> item(String name, Supplier<Item> factory) {
        return TheWake.platform().register(BuiltInRegistries.ITEM, name, factory);
    }

    private static Supplier<Block> block(String name, Supplier<Block> factory, boolean withItem) {
        Supplier<Block> block = TheWake.platform().register(BuiltInRegistries.BLOCK, name, factory);
        if (withItem) {
            TheWake.platform().register(BuiltInRegistries.ITEM, name, () -> new BlockItem(block.get(), new Item.Properties()));
        }
        return block;
    }

    /** For generic helpers. */
    static <T> Function<T, T> identity() {
        return t -> t;
    }
}
