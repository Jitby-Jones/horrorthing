package io.github.jitbyjones.thewake.fabric;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.client.WakeClient;
import io.github.jitbyjones.thewake.client.render.WakeShaders;
import io.github.jitbyjones.thewake.net.AttackPayload;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.PhantomPayload;
import io.github.jitbyjones.thewake.net.StoppedPayload;
import io.github.jitbyjones.thewake.net.SyncPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public final class TheWakeFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        WakeClient.init();

        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, ctx) -> WakeNetwork.handleClient(payload));
        ClientPlayNetworking.registerGlobalReceiver(FxPayload.TYPE, (payload, ctx) -> WakeNetwork.handleClient(payload));
        ClientPlayNetworking.registerGlobalReceiver(StoppedPayload.TYPE, (payload, ctx) -> WakeNetwork.handleClient(payload));
        ClientPlayNetworking.registerGlobalReceiver(PhantomPayload.TYPE, (payload, ctx) -> WakeNetwork.handleClient(payload));
        ClientPlayNetworking.registerGlobalReceiver(AttackPayload.TYPE, (payload, ctx) -> WakeNetwork.handleClient(payload));
        ClientPlayNetworking.registerGlobalReceiver(io.github.jitbyjones.thewake.net.WakeFxPayload.TYPE, (payload, ctx) -> WakeNetwork.handleClient(payload));
        ClientPlayNetworking.registerGlobalReceiver(io.github.jitbyjones.thewake.net.PossessPayload.TYPE, (payload, ctx) -> WakeNetwork.handleClient(payload));

        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(io.github.jitbyjones.thewake.registry.WakeContent.WAKE.get(),
                io.github.jitbyjones.thewake.client.entity.WakeEntityRenderer::new);
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(io.github.jitbyjones.thewake.registry.WakeContent.FLUNG_BLOCK.get(),
                io.github.jitbyjones.thewake.client.entity.FlungBlockRenderer::new);
        net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlocks(net.minecraft.client.renderer.RenderType.cutout(),
                io.github.jitbyjones.thewake.registry.WakeContent.UNMARKED_DOOR.get(), io.github.jitbyjones.thewake.registry.WakeContent.EXIT_DOOR.get(),
                io.github.jitbyjones.thewake.registry.WakeContent.SPIKE_MARK.get(), io.github.jitbyjones.thewake.registry.WakeContent.HANGING_STRING.get(),
                io.github.jitbyjones.thewake.registry.WakeContent.SHED_TAPE.get());

        CoreShaderRegistrationCallback.EVENT.register(context -> {
            context.register(WakeShaders.RIBBON, DefaultVertexFormat.NEW_ENTITY, shader -> WakeShaders.ribbon = shader);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> WakeClient.tick());
        WorldRenderEvents.AFTER_ENTITIES.register(context ->
                WakeClient.renderLevel(context.camera(), context.tickCounter().getGameTimeDeltaPartialTick(false)));
        HudRenderCallback.EVENT.register((graphics, delta) -> WakeClient.renderHud(graphics, delta.getGameTimeDeltaPartialTick(false)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> WakeClient.disconnect());
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return TheWake.id("client_reload");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                WakeClient.resourcesReloaded();
            }
        });
    }
}
