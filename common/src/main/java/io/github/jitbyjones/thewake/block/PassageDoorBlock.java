package io.github.jitbyjones.thewake.block;

import com.mojang.serialization.MapCodec;
import io.github.jitbyjones.thewake.world.Passages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A door that does not open onto the other side of its wall. The unmarked one at the end of the
 * hallway opens onto the Archive; the exit doors in its places open onto where you came from.
 */
public class PassageDoorBlock extends DoorBlock {
    public enum Leads { ARCHIVE, HOME }

    public static final MapCodec<PassageDoorBlock> CODEC = simpleCodec(p -> new PassageDoorBlock(BlockSetType.DARK_OAK, Leads.ARCHIVE, p));

    private final Leads leads;

    public PassageDoorBlock(BlockSetType type, Leads leads, Properties properties) {
        super(type, properties);
        this.leads = leads;
    }

    @Override
    public MapCodec<? extends DoorBlock> codec() {
        return CODEC;
    }

    public Leads leads() {
        return leads;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            Passages.through(serverPlayer, leads, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        // Redstone does not open it.
    }

    @Override
    protected void onExplosionHit(BlockState state, Level level, BlockPos pos, Explosion explosion,
            java.util.function.BiConsumer<net.minecraft.world.item.ItemStack, BlockPos> drops) {
        // Nothing opens it but a hand.
    }
}
