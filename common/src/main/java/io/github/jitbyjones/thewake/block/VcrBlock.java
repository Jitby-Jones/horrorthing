package io.github.jitbyjones.thewake.block;

import com.mojang.serialization.MapCodec;
import io.github.jitbyjones.thewake.haunt.LastNight;
import io.github.jitbyjones.thewake.item.TapeItem;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A video cassette recorder. Put your tape in it at night and it plays; that is the last night.
 * Use it again, at the end, to eject.
 */
public class VcrBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<VcrBlock> CODEC = simpleCodec(VcrBlock::new);
    public static final BooleanProperty TAPE = BooleanProperty.create("tape");
    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 2.0, 15.0, 5.0, 14.0);

    public VcrBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TAPE, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TAPE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (!stack.is(WakeContent.TAPE.get()) || state.getValue(TAPE)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return ItemInteractionResult.FAIL;
        }
        level.playSound(null, pos, WakeRegistry.CLICK.get(), SoundSource.BLOCKS, 1.0F, 1.1F);
        if (!TapeItem.isTheirs(stack, serverPlayer) || TapeItem.isBlank(stack)) {
            // Somebody else's nights, or none at all. It plays static.
            level.playSound(null, pos, WakeRegistry.STATIC.get(), SoundSource.BLOCKS, 0.6F, 1.0F);
            return ItemInteractionResult.CONSUME;
        }
        level.playSound(null, pos, WakeRegistry.TAPE_EJECT.get(), SoundSource.BLOCKS, 0.9F, 1.0F);
        if (LastNight.begin(serverPlayer, pos)) {
            level.playSound(null, pos, WakeRegistry.TAPE_REWIND.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
            setTape(level, pos, true);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !state.getValue(TAPE)) {
            return InteractionResult.PASS;
        }
        level.playSound(null, pos, WakeRegistry.CLICK.get(), SoundSource.BLOCKS, 1.0F, 0.8F);
        if (LastNight.eject(serverPlayer, pos)) {
            return InteractionResult.CONSUME;
        }
        if (!LastNight.active(serverPlayer)) {
            // Not tonight: the tape comes back out.
            level.playSound(null, pos, WakeRegistry.TAPE_EJECT.get(), SoundSource.BLOCKS, 0.9F, 1.0F);
            setTape(level, pos, false);
            ItemStack tape = TapeItem.stamped(serverPlayer);
            if (!player.getInventory().add(tape)) {
                player.drop(tape, false);
            }
        }
        return InteractionResult.CONSUME;
    }

    public static void setTape(Level level, BlockPos pos, boolean in) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof VcrBlock && state.getValue(TAPE) != in) {
            level.setBlock(pos, state.setValue(TAPE, in), Block.UPDATE_ALL);
        }
    }
}
