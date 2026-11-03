package io.github.jitbyjones.thewake.mixin.client;

import io.github.jitbyjones.thewake.client.fx.PauseFigure;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right after the menu blur is drawn: the place for the one thing that is not blurred. */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void thewake$afterBlur(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        PauseFigure.afterBackground((Screen) (Object) this);
    }
}
