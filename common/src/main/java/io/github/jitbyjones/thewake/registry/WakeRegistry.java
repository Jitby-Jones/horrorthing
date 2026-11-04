package io.github.jitbyjones.thewake.registry;

import io.github.jitbyjones.thewake.TheWake;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Sounds. The original set (the room, the breath, the tape) are the mod's own; the rest come from
 * sound libraries under CC0 and CC-BY (see CREDITS.md), processed down to mono and leveled. Content
 * (the creature's body, its places, the things you find) is in {@link WakeContent}.
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

    // ---- the chase ------------------------------------------------------------------------
    /** The scream it lets out when it comes for you, and again while it runs. Very loud. */
    public static Supplier<SoundEvent> CHASE_SCREAM;
    /** The same scream a long way off. */
    public static Supplier<SoundEvent> DISTANT_SCREAM;
    /** Its feet, running. */
    public static Supplier<SoundEvent> RUN;
    /** Fingers and feet on a wall, climbing. */
    public static Supplier<SoundEvent> CLIMB;
    /** Iron tearing out of a frame. */
    public static Supplier<SoundEvent> METAL_RIP;
    /** A door landing, flat, on stone. */
    public static Supplier<SoundEvent> METAL_IMPACT;
    /** Something hitting a wall to get through it. */
    public static Supplier<SoundEvent> BREAK;
    /** Into the water after you. */
    public static Supplier<SoundEvent> SPLASH;
    /** Under the water, coming. */
    public static Supplier<SoundEvent> UNDERWATER_ROAR;
    /** It has hold of you. */
    public static Supplier<SoundEvent> GRAB;
    /** A low drone under a chase. */
    public static Supplier<SoundEvent> CHASE_DRONE;
    /** Your heart. */
    public static Supplier<SoundEvent> HEARTBEAT;

    // ---- nothing to see -------------------------------------------------------------------
    /** Breathing on the far side of a wall. */
    public static Supplier<SoundEvent> BREATH_WALL;
    /** Breathing a hand's width from your face. */
    public static Supplier<SoundEvent> BREATH_CLOSE;
    /** Knuckles on the outside of the wall. */
    public static Supplier<SoundEvent> KNOCK;
    /** Stone dragged over stone, further into the cave. */
    public static Supplier<SoundEvent> SCRAPE;
    /** A door opening by itself, slowly. */
    public static Supplier<SoundEvent> DOOR_CREAK;
    /** Something heavy on your roof. */
    public static Supplier<SoundEvent> ROOF_THUD;
    /** Words you cannot make out, behind your head. */
    public static Supplier<SoundEvent> WHISPER;
    /** A flashbulb and a shutter. */
    public static Supplier<SoundEvent> CAMERA_FLASH;
    /** Its places: fluorescent hum and a far, flat drone. */
    public static Supplier<SoundEvent> LIMINAL_DRONE;
    /** Somebody in shoes, behind you, keeping your pace. */
    public static Supplier<SoundEvent> STEPS;
    /** A music box, inside a wall. */
    public static Supplier<SoundEvent> MUSIC_BOX;
    /** Someone crying, a long way off. */
    public static Supplier<SoundEvent> CRYING;
    /** Chains, further in. */
    public static Supplier<SoundEvent> CHAINS;
    /** A television between channels. */
    public static Supplier<SoundEvent> RADIO;
    /** A tape rewinding. */
    public static Supplier<SoundEvent> TAPE_REWIND;
    /** A machine stopping. */
    public static Supplier<SoundEvent> TAPE_STOP;
    /** The cassette door. */
    public static Supplier<SoundEvent> TAPE_EJECT;

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

        CHASE_SCREAM = sound("chase.scream");
        DISTANT_SCREAM = sound("chase.distant_scream");
        RUN = sound("creature.run");
        CLIMB = sound("creature.climb");
        METAL_RIP = sound("creature.metal_rip");
        METAL_IMPACT = sound("creature.metal_impact");
        BREAK = sound("creature.break");
        SPLASH = sound("creature.splash");
        UNDERWATER_ROAR = sound("creature.underwater");
        GRAB = sound("creature.grab");
        CHASE_DRONE = sound("chase.drone");
        HEARTBEAT = sound("chase.heartbeat");

        BREATH_WALL = sound("event.breath_wall");
        BREATH_CLOSE = sound("event.breath_close");
        KNOCK = sound("event.knock");
        SCRAPE = sound("event.scrape");
        DOOR_CREAK = sound("event.door_creak");
        ROOF_THUD = sound("event.roof_thud");
        WHISPER = sound("event.whisper");
        CAMERA_FLASH = sound("item.camera_flash");
        LIMINAL_DRONE = sound("ambient.liminal");
        STEPS = sound("event.steps");
        MUSIC_BOX = sound("event.music_box");
        CRYING = sound("event.crying");
        CHAINS = sound("event.chains");
        RADIO = sound("event.radio");
        TAPE_REWIND = sound("tape.rewind");
        TAPE_STOP = sound("tape.stop");
        TAPE_EJECT = sound("tape.eject");

        WakeContent.register();
    }

    private static Supplier<SoundEvent> sound(String name) {
        ResourceLocation id = TheWake.id(name);
        return TheWake.platform().register(BuiltInRegistries.SOUND_EVENT, name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
