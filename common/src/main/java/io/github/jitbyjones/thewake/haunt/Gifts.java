package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Some mornings, from the third act, there is a map on your bed, or in the chest beside it, that
 * you did not draw: the country round you, and a red cross. Each one is to another of its places,
 * in the order you will want them: where to watch from, where the tapes are returned, the hallway,
 * the bed in the woods, the waiting room.
 */
public final class Gifts {
    private static final String[] ORDER = {"watchtower", "video_store", "hallway", "bedroom", "waiting_room"};

    private Gifts() {}

    @Nullable
    private static String next(HauntState state) {
        for (String kind : ORDER) {
            if (!state.hasFlag("map_" + kind)) {
                return kind;
            }
        }
        return null;
    }

    static boolean due(HauntState state, ServerLevel level) {
        return state.peakStage().atLeast(Stage.SIGHTINGS) && !Temper.removed(state) && next(state) != null
                && state.ready("map_gift", level.getGameTime());
    }

    /** Performed the morning after: the map is where you will find it. */
    static void leave(ServerPlayer player, HauntState state, ServerLevel level, BlockPos bed) {
        String kind = next(state);
        if (kind == null) {
            return;
        }
        state.cooldown("map_gift", level.getGameTime(), 24000L * 2);
        TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE, TheWake.id(kind));
        BlockPos found = level.findNearestMapStructure(tag, bed, 64, false);
        state.setFlag("map_" + kind);
        if (found == null) {
            // Not in this world (a flat world, a world without them). The next one, another morning.
            return;
        }
        ItemStack map = MapItem.create(level, found.getX(), found.getZ(), (byte) 2, true, true);
        MapItem.renderBiomePreviewMap(level, map);
        MapItemSavedData.addTargetDecoration(map, found, "+", MapDecorationTypes.RED_X);
        map.set(DataComponents.ITEM_NAME, Component.translatable("item.thewake.map." + kind));
        if (!intoChestNear(level, bed, map)) {
            ItemEntity item = new ItemEntity(level, bed.getX() + 0.5, bed.getY() + 0.7, bed.getZ() + 0.5, map);
            item.setDeltaMovement(0.0, 0.0, 0.0);
            item.setUnlimitedLifetime();
            level.addFreshEntity(item);
        }
        level.playSound(null, bed, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.6F, 0.8F);
        TheWake.LOG.debug("[{}] a map to the {}", player.getGameProfile().getName(), kind);
    }

    private static boolean intoChestNear(ServerLevel level, BlockPos bed, ItemStack stack) {
        for (BlockPos p : BlockPos.betweenClosed(bed.offset(-4, -1, -4), bed.offset(4, 2, 4))) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof Container container && !(be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
                    && !(be instanceof net.minecraft.world.level.block.entity.HopperBlockEntity)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    if (container.getItem(i).isEmpty()) {
                        container.setItem(i, stack);
                        container.setChanged();
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
