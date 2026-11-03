package io.github.jitbyjones.thewake.mixin.client;

import io.github.jitbyjones.thewake.client.fx.PauseTape;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right after a menu has blurred (pause, options) or dimmed (inventory, chests, signs) the world:
 * the place to draw the one thing that menus do not hide.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void thewake$afterBlur(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        PauseTape.drawOverBackground((Screen) (Object) this, graphics);
    }

    @Inject(method = "renderTransparentBackground", at = @At("TAIL"))
    private void thewake$afterDim(GuiGraphics graphics, CallbackInfo ci) {
        PauseTape.drawOverBackground((Screen) (Object) this, graphics);
    }
}
