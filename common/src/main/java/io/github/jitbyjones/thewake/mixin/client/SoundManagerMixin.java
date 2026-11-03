package io.github.jitbyjones.thewake.mixin.client;

import io.github.jitbyjones.thewake.client.fx.StoppedClient;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Stopped is silent: only your steps, its steps, the blocks you touch and the menu. */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void thewake$silence(SoundInstance sound, CallbackInfo ci) {
        if (StoppedClient.active() && !StoppedClient.allows(sound)) {
            ci.cancel();
        }
    }
}
