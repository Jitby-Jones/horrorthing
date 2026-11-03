package io.github.jitbyjones.thewake.mixin;

import io.github.jitbyjones.thewake.haunt.Lapses;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The moment Minecraft deletes the night. Neither loader has an event for "the night was skipped,
 * sleepers still in bed", so this hooks the private method vanilla calls right after the skip.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Unique
    private long thewake$dayTimeAtTickStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void thewake$rememberDayTime(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        thewake$dayTimeAtTickStart = ((ServerLevel) (Object) this).getDayTime();
    }

    @Inject(method = "wakeUpAllPlayers", at = @At("HEAD"))
    private void thewake$nightSkipped(CallbackInfo ci) {
        Lapses.onNightSkipped((ServerLevel) (Object) this, thewake$dayTimeAtTickStart);
    }
}
