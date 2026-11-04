package io.github.jitbyjones.thewake.item;

import io.github.jitbyjones.thewake.haunt.Flash;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * An old flash camera, from the top of a watchtower. Each picture burns a bulb. Whatever is in
 * front of the lens when it goes off cannot see for a few seconds, if its eyes were open.
 */
public class OldCameraItem extends Item {
    public OldCameraItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }
        if (!takeBulb(serverPlayer)) {
            level.playSound(null, player.blockPosition(), WakeRegistry.CLICK.get(), SoundSource.PLAYERS, 0.8F, 1.6F);
            player.getCooldowns().addCooldown(this, 10);
            return InteractionResultHolder.fail(stack);
        }
        Flash.fire(serverPlayer);
        player.getCooldowns().addCooldown(this, 50);
        return InteractionResultHolder.consume(stack);
    }

    private static boolean takeBulb(ServerPlayer player) {
        if (player.getAbilities().instabuild) {
            return true;
        }
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(WakeContent.FLASH_BULB.get())) {
                s.shrink(1);
                player.level().playSound(null, player.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.15F, 2.0F);
                return true;
            }
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.thewake.old_camera.desc").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
