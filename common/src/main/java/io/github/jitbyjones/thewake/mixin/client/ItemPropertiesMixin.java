package io.github.jitbyjones.thewake.mixin.client;

import io.github.jitbyjones.thewake.client.ClockOverride;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Wraps the vanilla clock's "time" property so clocks can stop. Registration is private in vanilla. */
@Mixin(ItemProperties.class)
public abstract class ItemPropertiesMixin {
    @Unique
    private static final ResourceLocation THEWAKE$TIME = ResourceLocation.withDefaultNamespace("time");

    @Inject(method = "getProperty", at = @At("RETURN"), cancellable = true)
    private static void thewake$stopClocks(ItemStack stack, ResourceLocation location, CallbackInfoReturnable<ItemPropertyFunction> cir) {
        ItemPropertyFunction original = cir.getReturnValue();
        if (original != null && stack.is(Items.CLOCK) && THEWAKE$TIME.equals(location)) {
            cir.setReturnValue(ClockOverride.wrap(original));
        }
    }
}
