package io.github.jitbyjones.thewake.fabric;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.WakeCommands;
import io.github.jitbyjones.thewake.WakeHooks;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.AttackPayload;
import io.github.jitbyjones.thewake.net.HitchPayload;
import io.github.jitbyjones.thewake.net.PausePayload;
import io.github.jitbyjones.thewake.net.ScreenPayload;
import io.github.jitbyjones.thewake.net.PhantomPayload;
import io.github.jitbyjones.thewake.net.StoppedPayload;
import io.github.jitbyjones.thewake.net.SyncPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

public final class TheWakeFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        TheWake.init(new FabricPlatform());

        PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FxPayload.TYPE, FxPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StoppedPayload.TYPE, StoppedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PhantomPayload.TYPE, PhantomPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AttackPayload.TYPE, AttackPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.jitbyjones.thewake.net.WakeFxPayload.TYPE, io.github.jitbyjones.thewake.net.WakeFxPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.jitbyjones.thewake.net.PossessPayload.TYPE, io.github.jitbyjones.thewake.net.PossessPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ScreenPayload.TYPE, ScreenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PausePayload.TYPE, PausePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HitchPayload.TYPE, HitchPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PausePayload.TYPE, (payload, ctx) -> WakeNetwork.handleServer(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(HitchPayload.TYPE, (payload, ctx) -> WakeNetwork.handleServer(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ScreenPayload.TYPE, (payload, ctx) -> WakeNetwork.handleServer(ctx.player(), payload));

        ServerTickEvents.END_SERVER_TICK.register(WakeHooks::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(WakeHooks::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> WakeHooks.onServerStopped());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> WakeHooks.onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WakeHooks.onLeave(handler.getPlayer()));

        EntitySleepEvents.ALLOW_SLEEPING.register((player, pos) ->
                player instanceof ServerPlayer serverPlayer ? WakeHooks.sleepProblem(serverPlayer, pos) : null);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer && WakeHooks.onUseBlock(serverPlayer, hit.getBlockPos())) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !(player instanceof ServerPlayer serverPlayer && WakeHooks.onBreakBlock(serverPlayer, pos, state)));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                player instanceof ServerPlayer serverPlayer && WakeHooks.onAttack(serverPlayer) ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer serverPlayer) {
                WakeHooks.onDeath(serverPlayer);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> WakeCommands.register(dispatcher));
    }
}
