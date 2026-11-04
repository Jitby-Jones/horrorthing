package io.github.jitbyjones.thewake.neoforge;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.client.WakeClient;
import io.github.jitbyjones.thewake.client.render.WakeShaders;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only wiring. Only ever loaded on a physical client. */
final class TheWakeNeoForgeClient {
    private TheWakeNeoForgeClient() {}

    static void init(IEventBus modBus) {
        WakeClient.init();
        modBus.addListener(TheWakeNeoForgeClient::registerShaders);
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) -> {
            event.registerEntityRenderer(io.github.jitbyjones.thewake.registry.WakeContent.WAKE.get(),
                    io.github.jitbyjones.thewake.client.entity.WakeEntityRenderer::new);
            event.registerEntityRenderer(io.github.jitbyjones.thewake.registry.WakeContent.FLUNG_BLOCK.get(),
                    io.github.jitbyjones.thewake.client.entity.FlungBlockRenderer::new);
        });
        modBus.addListener((RegisterClientReloadListenersEvent event) ->
                event.registerReloadListener((ResourceManagerReloadListener) manager -> WakeClient.resourcesReloaded()));

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> WakeClient.tick());
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                WakeClient.renderLevel(event.getCamera(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
            }
        });
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) ->
                WakeClient.renderHud(event.getGuiGraphics(), event.getPartialTick().getGameTimeDeltaPartialTick(false)));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> WakeClient.disconnect());
    }

    private static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), WakeShaders.RIBBON, DefaultVertexFormat.NEW_ENTITY),
                    shader -> WakeShaders.ribbon = shader);
        } catch (IOException e) {
            TheWake.LOG.error("Could not load the finale's ground shader; falling back to a texture", e);
        }
    }
}
