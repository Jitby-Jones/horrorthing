package io.github.jitbyjones.thewake.fabric;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.platform.WakePlatform;
import java.nio.file.Path;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

final class FabricPlatform implements WakePlatform {
    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isPhysicalClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public <T, R extends T> Supplier<R> register(Registry<T> registry, String path, Supplier<R> factory) {
        R value = Registry.register(registry, TheWake.id(path), factory.get());
        return () -> value;
    }

    @Override
    public <T extends net.minecraft.world.entity.LivingEntity> void registerAttributes(Supplier<net.minecraft.world.entity.EntityType<T>> type,
            Supplier<net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder> attributes) {
        net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(type.get(), attributes.get());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        if (ClientPlayNetworking.canSend(payload.type())) {
            ClientPlayNetworking.send(payload);
        }
    }

    @Override
    public int burnTicks(ItemStack fuel) {
        // Fabric's FuelRegistry feeds this same vanilla map.
        return AbstractFurnaceBlockEntity.getFuel().getOrDefault(fuel.getItem(), 0);
    }
}
