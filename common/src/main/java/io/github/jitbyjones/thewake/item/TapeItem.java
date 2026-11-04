package io.github.jitbyjones.thewake.item;

import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.haunt.Temper;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

/**
 * A VHS cassette with a handwritten label: your name, and how many nights. In the machine it plays
 * every one of them at once.
 */
public class TapeItem extends Item {
    public TapeItem(Properties properties) {
        super(properties);
    }

    /** A tape for this player, labelled. */
    public static ItemStack stamped(ServerPlayer player) {
        HauntState state = Haunt.state(player);
        ItemStack stack = new ItemStack(WakeContent.TAPE.get());
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Owner", player.getUUID());
        tag.putString("Name", player.getGameProfile().getName());
        tag.putInt("Nights", Math.max(1, state.nightsSkipped()));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.thewake.tape.label", player.getGameProfile().getName())
                .withStyle(s -> s.withItalic(false)));
        return stack;
    }

    public static boolean isTheirs(ItemStack stack, ServerPlayer player) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.hasUUID("Owner")) {
            return false;
        }
        UUID owner = tag.getUUID("Owner");
        return owner.equals(player.getUUID());
    }

    public static boolean isBlank(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null || !data.copyTag().hasUUID("Owner");
    }

    /** The returns slot: the first time you reach in, your tape is in the tray. */
    public static void fromReturns(ServerPlayer player, BlockPos slot) {
        HauntState state = Haunt.state(player);
        player.level().playSound(null, slot, WakeRegistry.CLICK.get(), SoundSource.BLOCKS, 0.9F, 0.7F);
        if (state.reputation().tapeTaken() || Temper.removed(state)) {
            return;
        }
        Temper.setTapeTaken(state);
        ItemStack tape = stamped(player);
        if (!player.getInventory().add(tape)) {
            player.drop(tape, false);
        }
        Temper.provoke(player, state, Temper.Provocation.TRESPASS, 2.0F);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().contains("Nights")) {
            lines.add(Component.translatable("item.thewake.tape.nights", data.copyTag().getInt("Nights"))
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        } else {
            lines.add(Component.translatable("item.thewake.tape.empty").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }
}
