package io.github.jitbyjones.thewake.mixin.client;

import io.github.jitbyjones.thewake.client.fx.AttackClient;
import io.github.jitbyjones.thewake.client.fx.TapeEffect;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** The tape look, after the level and hand, before the HUD. */
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V", shift = At.Shift.AFTER))
    private void thewake$tape(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        TapeEffect.process(deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    /** Every frame, before anything is drawn (the attack takes your view). */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void thewake$beforeLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        io.github.jitbyjones.thewake.client.WakeClient.beforeLevel(deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    /** The finished world's depth, for menus that draw the creature again over themselves. */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            shift = At.Shift.AFTER))
    private void thewake$afterLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        io.github.jitbyjones.thewake.client.render.WorldRender.afterLevel();
    }

    /** When it attacks, its face is drawn over the finished world, where nothing can be in front of it. */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/Camera;FLorg/joml/Matrix4f;)V"))
    private void thewake$overWorld(DeltaTracker deltaTracker, CallbackInfo ci) {
        AttackClient.renderOverWorld(deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    /** ...and your hand is not in the way. */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void thewake$noHand(Camera camera, float partialTick, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (AttackClient.active()) {
            ci.cancel();
        }
    }
}
