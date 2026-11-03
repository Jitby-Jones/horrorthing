package io.github.jitbyjones.thewake.mixin.client;

import io.github.jitbyjones.thewake.client.fx.StoppedClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rain hangs in the air and clouds stop: the level renderer's own tick (weather, clouds) is held. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void thewake$holdWeather(CallbackInfo ci) {
        if (StoppedClient.active()) {
            ci.cancel();
        }
    }
}
