package io.github.jitbyjones.thewake.haunt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Act I. The night shift: small chores done in the night you skipped. Furnaces finish, crops
 * ripen, doors close, a torch appears where you stumbled. None of it can happen in vanilla,
 * because a skipped night does not tick. It only ever adds; it never takes anything away.
 */
final class Kindness {
    private static final int RADIUS = 12;

    private Kindness() {}

    static void run(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, Direction facing) {
        RandomSource random = player.getRandom();
        List<Runnable> chores = new ArrayList<>();
        if (hasLitFurnace(level, head)) {
            chores.add(() -> finishFurnaces(level, head));
        }
        chores.add(() -> ripenCrops(level, head, random));
        chores.add(() -> closeDoors(level, head));
        chores.add(() -> tidyItems(level, head));
        if (!state.hasFlag("torch") || random.nextInt(4) == 0) {
            chores.add(() -> {
                if (placeTorch(level, head, random)) {
                    state.setFlag("torch");
                }
            });
        }
        // One or two things, never everything: it should be easy to miss at first.
        int count = 1 + (random.nextFloat() < 0.35F ? 1 : 0);
        for (int i = 0; i < count && !chores.isEmpty(); i++) {
            chores.remove(random.nextInt(chores.size())).run();
        }
        // Somebody used you to do it. You wake up hungrier.
        player.getFoodData().addExhaustion(8.0F);
    }

    private static boolean hasLitFurnace(ServerLevel level, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -3, -RADIUS), center.offset(RADIUS, 3, RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof AbstractFurnaceBlock && state.getValue(AbstractFurnaceBlock.LIT)) {
                return true;
            }
        }
        return false;
    }

    private static void finishFurnaces(ServerLevel level, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -3, -RADIUS), center.offset(RADIUS, 3, RADIUS))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof AbstractFurnaceBlockEntity furnace) {
                finishFurnace(level, pos.immutable(), furnace);
            }
        }
    }

    private static void finishFurnace(ServerLevel level, BlockPos pos, AbstractFurnaceBlockEntity furnace) {
        ItemStack input = furnace.getItem(0);
        ItemStack fuel = furnace.getItem(1);
        ItemStack output = furnace.getItem(2);
        if (input.isEmpty()) {
            return;
        }
        RecipeType<? extends AbstractCookingRecipe> type = furnace instanceof BlastFurnaceBlockEntity ? RecipeType.BLASTING
                : furnace instanceof SmokerBlockEntity ? RecipeType.SMOKING : RecipeType.SMELTING;
        Optional<? extends RecipeHolder<? extends AbstractCookingRecipe>> recipe = findRecipe(level, type, input);
        if (recipe.isEmpty()) {
            return;
        }
        AbstractCookingRecipe cooking = recipe.get().value();
        ItemStack result = cooking.getResultItem(level.registryAccess());
        if (result.isEmpty() || (!output.isEmpty() && !ItemStack.isSameItemSameComponents(output, result))) {
            return;
        }
        int cookTime = Math.max(1, cooking.getCookingTime());
        int perFuel = fuel.isEmpty() ? 0 : Math.max(0, io.github.jitbyjones.thewake.TheWake.platform().burnTicks(fuel) / cookTime);
        int space = (result.getMaxStackSize() - output.getCount()) / Math.max(1, result.getCount());
        // The fire was already going when you went to bed: that much is free.
        int budget = 1 + perFuel * fuel.getCount();
        int cooked = Math.min(Math.min(input.getCount(), space), budget);
        if (cooked <= 0) {
            return;
        }
        int fuelUsed = perFuel == 0 ? 0 : Math.min(fuel.getCount(), (int) Math.ceil(Math.max(0, cooked - 1) / (double) perFuel));
        ItemStack newOutput = output.isEmpty() ? result.copyWithCount(result.getCount() * cooked) : output.copyWithCount(output.getCount() + result.getCount() * cooked);
        input.shrink(cooked);
        furnace.setItem(0, input);
        if (fuelUsed > 0) {
            ItemStack remainder = fuel.getItem().hasCraftingRemainingItem() ? new ItemStack(fuel.getItem().getCraftingRemainingItem()) : ItemStack.EMPTY;
            fuel.shrink(fuelUsed);
            furnace.setItem(1, fuel.isEmpty() ? remainder : fuel);
        }
        // The output turns up in your chest, the way you would have put it away.
        ItemStack leftover = putAway(level, pos, newOutput);
        furnace.setItem(2, leftover);
        furnace.setChanged();
        BlockState state = level.getBlockState(pos);
        if (input.isEmpty() && state.hasProperty(AbstractFurnaceBlock.LIT)) {
            level.setBlock(pos, state.setValue(AbstractFurnaceBlock.LIT, false), 3);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<? extends RecipeHolder<? extends AbstractCookingRecipe>> findRecipe(ServerLevel level,
            RecipeType<? extends AbstractCookingRecipe> type, ItemStack input) {
        return level.getRecipeManager().getRecipeFor((RecipeType) type, new SingleRecipeInput(input), level);
    }

    private static void ripenCrops(ServerLevel level, BlockPos center, RandomSource random) {
        int grown = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -3, -RADIUS), center.offset(RADIUS, 3, RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                int age = Math.min(crop.getMaxAge(), crop.getAge(state) + 1 + random.nextInt(2));
                level.setBlock(pos, crop.getStateForAge(age), 2);
                if (++grown > 96) {
                    return;
                }
            }
        }
    }

    private static void closeDoors(ServerLevel level, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -3, -RADIUS), center.offset(RADIUS, 3, RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand() && state.getValue(DoorBlock.OPEN)
                    && state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                door.setOpen(null, level, state, pos.immutable(), false);
            }
        }
    }

    /** Things you dropped near the bed are put away in the nearest chest with room. */
    private static void tidyItems(ServerLevel level, BlockPos center) {
        AABB box = new AABB(center).inflate(RADIUS, 3, RADIUS);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && e.getAge() > 100 && !e.hasPickUpDelay());
        if (items.isEmpty()) {
            return;
        }
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -3, -RADIUS), center.offset(RADIUS, 3, RADIUS))) {
            if (!(level.getBlockEntity(pos) instanceof Container container) || level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity) {
                continue;
            }
            for (ItemEntity item : items) {
                if (!item.isAlive()) {
                    continue;
                }
                ItemStack rest = insert(container, item.getItem().copy());
                if (rest.isEmpty()) {
                    item.discard();
                } else {
                    item.setItem(rest);
                }
            }
            container.setChanged();
            return;
        }
    }

    private static ItemStack putAway(ServerLevel level, BlockPos from, ItemStack stack) {
        for (BlockPos pos : BlockPos.betweenClosed(from.offset(-6, -2, -6), from.offset(6, 2, 6))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity) {
                stack = insert((Container) be, stack);
                ((Container) be).setChanged();
                if (stack.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return stack;
    }

    private static ItemStack insert(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, stack);
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(move);
                stack.shrink(move);
            }
        }
        return stack;
    }

    /** A torch in the darkest place near the bed you can stand in. */
    private static boolean placeTorch(ServerLevel level, BlockPos center, RandomSource random) {
        BlockPos best = null;
        int bestLight = 5;
        for (int i = 0; i < 60; i++) {
            BlockPos candidate = center.offset(random.nextInt(RADIUS * 2 + 1) - RADIUS, random.nextInt(5) - 2, random.nextInt(RADIUS * 2 + 1) - RADIUS);
            if (!Spots.standable(level, candidate) || level.canSeeSky(candidate)) {
                continue;
            }
            int light = level.getBrightness(LightLayer.BLOCK, candidate);
            if (light < bestLight && Blocks.TORCH.defaultBlockState().canSurvive(level, candidate)) {
                bestLight = light;
                best = candidate;
            }
        }
        if (best == null) {
            return false;
        }
        level.setBlock(best, Blocks.TORCH.defaultBlockState(), 3);
        return true;
    }

    static boolean isWoodenDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock door && door.type() != BlockSetType.IRON && door.type().canOpenByHand();
    }
}
