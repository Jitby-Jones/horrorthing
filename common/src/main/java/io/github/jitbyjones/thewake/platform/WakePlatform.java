package io.github.jitbyjones.thewake.platform;

import java.nio.file.Path;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The small surface that differs between NeoForge and Fabric. Everything else lives in common code.
 */
public interface WakePlatform {
    String loaderName();

    Path configDir();

    boolean isPhysicalClient();

    /**
     * Registers a value. Fabric registers immediately; NeoForge defers to a DeferredRegister.
     * The returned supplier must not be queried before registration has happened.
     */
    <T, R extends T> Supplier<R> register(Registry<T> registry, String path, Supplier<R> factory);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Client side only. */
    void sendToServer(CustomPacketPayload payload);

    /** Furnace burn time of a fuel stack, in ticks (loaders store fuel values differently). */
    int burnTicks(ItemStack fuel);
}
