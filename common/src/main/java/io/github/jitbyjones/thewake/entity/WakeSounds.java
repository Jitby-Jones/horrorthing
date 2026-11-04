package io.github.jitbyjones.thewake.entity;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.haunt.Household;
import io.github.jitbyjones.thewake.net.WakeFxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Its voice and its body's noises: sent to its owner alone. */
public final class WakeSounds {
    private WakeSounds() {}

    public static void at(ServerPlayer owner, SoundEvent sound, Vec3 at, float volume, float pitch) {
        Household.send(owner, sound, SoundSource.HOSTILE, at, volume * WakeConfig.get().soundVolume, pitch);
    }

    /**
     * A scream: positional and far-carrying, and when it is close a second, flat layer right in
     * your ears, as loud as the config allows.
     */
    public static void scream(ServerPlayer owner, SoundEvent sound, Vec3 at, float pitch) {
        float volume = WakeConfig.get().screamVolume;
        if (volume <= 0.0F) {
            return;
        }
        Household.send(owner, sound, SoundSource.HOSTILE, at, 5.0F * volume, pitch);
        double d = at.distanceTo(owner.getEyePosition());
        if (d < 14.0) {
            float near = (float) (1.0 - d / 14.0);
            WakeNetwork.send(owner, new WakeFxPayload(WakeFxPayload.SCREAM_NEAR, at.x, at.y, at.z, near * volume, pitch));
        }
    }
}
