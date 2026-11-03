package io.github.jitbyjones.thewake.neoforge;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.platform.WakePlatform;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;

final class NeoForgePlatform implements WakePlatform {
    private final Map<ResourceKey<?>, DeferredRegister<?>> registers = new LinkedHashMap<>();

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isPhysicalClient() {
        return FMLEnvironment.dist.isClient();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T, R extends T> Supplier<R> register(Registry<T> registry, String path, Supplier<R> factory) {
        DeferredRegister<T> deferred = (DeferredRegister<T>) registers.computeIfAbsent(registry.key(),
                key -> DeferredRegister.create(registry, TheWake.MOD_ID));
        return deferred.register(path, factory);
    }

    void registerAll(IEventBus modBus) {
        for (DeferredRegister<?> register : registers.values()) {
            register.register(modBus);
        }
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public int burnTicks(ItemStack fuel) {
        return fuel.getBurnTime(null);
    }
}
