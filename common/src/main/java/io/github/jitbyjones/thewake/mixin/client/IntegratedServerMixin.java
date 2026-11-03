package io.github.jitbyjones.thewake.mixin.client;

import io.github.jitbyjones.thewake.WakeHooks;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Singleplayer pauses stop the integrated server's world ticks, but the pause menu is a lapse like
 * any other: this is the one place to let the creature take its steps while the world stands still.
 * Runs on the server thread, every paused tick.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "tickPaused", at = @At("TAIL"))
    private void thewake$pausedTick(CallbackInfo ci) {
        WakeHooks.onPausedTick((IntegratedServer) (Object) this);
    }
}
