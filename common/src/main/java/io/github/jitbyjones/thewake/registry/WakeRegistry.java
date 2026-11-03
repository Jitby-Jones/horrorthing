package io.github.jitbyjones.thewake.registry;

import io.github.jitbyjones.thewake.TheWake;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Deliberately tiny. The mod adds no blocks, items, entities or statistics: everything the player
 * sees is a vanilla thing behaving wrongly, plus four original sounds.
 */
public final class WakeRegistry {
    /** A 3 a.m. room: mains hum, a far clock, slow breathing. Near images, very quiet. */
    public static Supplier<SoundEvent> ROOM_TONE;
    /** A soft leaf-shutter click as an image closes. */
    public static Supplier<SoundEvent> SHUTTER;
    /** One slow breath, close. */
    public static Supplier<SoundEvent> BREATH;
    /** The Stopped: the faint ringing of a room with no sound in it. */
    public static Supplier<SoundEvent> STILLNESS;

    private WakeRegistry() {}

    public static void register() {
        ROOM_TONE = sound("room_tone");
        SHUTTER = sound("shutter");
        BREATH = sound("breath");
        STILLNESS = sound("stillness");
    }

    private static Supplier<SoundEvent> sound(String name) {
        ResourceLocation id = TheWake.id(name);
        return TheWake.platform().register(BuiltInRegistries.SOUND_EVENT, name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
