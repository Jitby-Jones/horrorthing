package io.github.jitbyjones.thewake.neoforge;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.WakeCommands;
import io.github.jitbyjones.thewake.WakeHooks;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.HitchPayload;
import io.github.jitbyjones.thewake.net.PausePayload;
import io.github.jitbyjones.thewake.net.PhantomPayload;
import io.github.jitbyjones.thewake.net.StoppedPayload;
import io.github.jitbyjones.thewake.net.SyncPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(TheWake.MOD_ID)
public final class TheWakeNeoForge {
    public TheWakeNeoForge(IEventBus modBus) {
        NeoForgePlatform platform = new NeoForgePlatform();
        TheWake.init(platform);
        platform.registerAll(modBus);
        modBus.addListener(TheWakeNeoForge::registerPayloads);

        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> WakeHooks.onServerTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> WakeHooks.onServerStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> WakeHooks.onServerStopped());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                WakeHooks.onJoin(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                WakeHooks.onLeave(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((CanPlayerSleepEvent event) -> {
            if (event.getProblem() == null) {
                Player.BedSleepingProblem problem = WakeHooks.sleepProblem(event.getEntity(), event.getPos());
                if (problem != null) {
                    event.setProblem(problem);
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            if (event.getEntity() instanceof ServerPlayer player && WakeHooks.onUseBlock(player, event.getPos())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent event) -> {
            if (event.getPlayer() instanceof ServerPlayer player && WakeHooks.onBreakBlock(player, event.getPos(), event.getState())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((AttackEntityEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player && WakeHooks.onAttack(player)) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                WakeHooks.onDeath(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> WakeCommands.register(event.getDispatcher()));

        if (FMLEnvironment.dist.isClient()) {
            TheWakeNeoForgeClient.init(modBus);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(SyncPayload.TYPE, SyncPayload.CODEC, (payload, ctx) -> WakeNetwork.handleClient(payload));
        registrar.playToClient(FxPayload.TYPE, FxPayload.CODEC, (payload, ctx) -> WakeNetwork.handleClient(payload));
        registrar.playToClient(StoppedPayload.TYPE, StoppedPayload.CODEC, (payload, ctx) -> WakeNetwork.handleClient(payload));
        registrar.playToClient(PhantomPayload.TYPE, PhantomPayload.CODEC, (payload, ctx) -> WakeNetwork.handleClient(payload));
        registrar.playToServer(PausePayload.TYPE, PausePayload.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                WakeNetwork.handleServer(player, payload);
            }
        });
        registrar.playToServer(HitchPayload.TYPE, HitchPayload.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                WakeNetwork.handleServer(player, payload);
            }
        });
    }
}
