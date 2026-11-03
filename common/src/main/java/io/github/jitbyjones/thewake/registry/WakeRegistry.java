package io.github.jitbyjones.thewake.registry;

import io.github.jitbyjones.thewake.TheWake;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Deliberately tiny. The mod adds no blocks, items, entities or statistics: only original sounds
 * (and one damage type, as data). Everything else is a vanilla thing behaving wrongly, or the
 * creature, which is not an entity at all.
 */
public final class WakeRegistry {
    /** A 3 a.m. room: mains hum, a far clock, slow breathing. Near it, very quiet. */
    public static Supplier<SoundEvent> ROOM_TONE;
    /** One slow breath, close. */
    public static Supplier<SoundEvent> BREATH;
    /** The Stopped: the faint ringing of a room with no sound in it. */
    public static Supplier<SoundEvent> STILLNESS;
    /** A bare, heavy footstep that is not yours. */
    public static Supplier<SoundEvent> STEP;
    /** Joints cracking: knuckles, a neck. */
    public static Supplier<SoundEvent> CRACK;
    /** Wet breathing through a cheap microphone. */
    public static Supplier<SoundEvent> WET_BREATH;
    /** The tape motor: a thin warble under everything while it moves. */
    public static Supplier<SoundEvent> WARBLE;
    /** A burst of tape static. */
    public static Supplier<SoundEvent> STATIC;
    /** A VCR's PAUSE / PLAY key. */
    public static Supplier<SoundEvent> CLICK;
    /** Short, distorted, very close. */
    public static Supplier<SoundEvent> SCREAM;

    private WakeRegistry() {}

    public static void register() {
        ROOM_TONE = sound("room_tone");
        BREATH = sound("breath");
        STILLNESS = sound("stillness");
        STEP = sound("creature.step");
        CRACK = sound("creature.crack");
        WET_BREATH = sound("creature.breath");
        WARBLE = sound("tape.warble");
        STATIC = sound("tape.static");
        CLICK = sound("tape.click");
        SCREAM = sound("creature.scream");
    }

    private static Supplier<SoundEvent> sound(String name) {
        ResourceLocation id = TheWake.id(name);
        return TheWake.platform().register(BuiltInRegistries.SOUND_EVENT, name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
