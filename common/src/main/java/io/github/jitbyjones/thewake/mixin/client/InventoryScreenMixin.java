package io.github.jitbyjones.thewake.mixin.client;

import io.github.jitbyjones.thewake.client.fx.Paperdoll;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Before your doll is drawn in the inventory: whatever is behind you is behind it too. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
    @Inject(method = "renderEntityInInventoryFollowsMouse", at = @At("HEAD"))
    private static void thewake$behindYou(GuiGraphics graphics, int x1, int y1, int x2, int y2, int scale, float yOffset, float mouseX,
            float mouseY, LivingEntity entity, CallbackInfo ci) {
        Paperdoll.render(graphics, x1, y1, x2, y2, scale, entity);
    }
}
