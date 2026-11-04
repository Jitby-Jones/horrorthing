package io.github.jitbyjones.thewake.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * A ceiling panel with a tube behind it. It hums, and now and then it goes out for a moment and
 * comes back. Near it, the lights go out one after another, and stay out a while.
 */
public class FluorescentLightBlock extends Block {
    public static final MapCodec<FluorescentLightBlock> CODEC = simpleCodec(FluorescentLightBlock::new);
    public static final BooleanProperty LIT = BooleanProperty.create("lit");

    public FluorescentLightBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LIT, true));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState();
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(LIT)) {
            if (random.nextInt(5) == 0) {
                flicker(level, pos, 2 + random.nextInt(8));
            }
        } else if (random.nextInt(3) == 0) {
            level.setBlock(pos, state.setValue(LIT, true), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) {
            level.setBlock(pos, state.setValue(LIT, true), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.COPPER_BULB_TURN_ON, SoundSource.BLOCKS, 0.25F, 1.6F);
        }
    }

    /** Off for a few ticks, then back on by itself. */
    public static void flicker(Level level, BlockPos pos, int ticks) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FluorescentLightBlock && state.getValue(LIT)) {
            level.setBlock(pos, state.setValue(LIT, false), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.COPPER_BULB_TURN_OFF, SoundSource.BLOCKS, 0.25F, 1.7F);
            level.scheduleTick(pos, state.getBlock(), ticks);
        }
    }

    /** Out, and it stays out until something turns it back on. */
    public static void kill(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FluorescentLightBlock && state.getValue(LIT)) {
            level.setBlock(pos, state.setValue(LIT, false), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.COPPER_BULB_TURN_OFF, SoundSource.BLOCKS, 0.5F, 0.6F);
        }
    }
}
