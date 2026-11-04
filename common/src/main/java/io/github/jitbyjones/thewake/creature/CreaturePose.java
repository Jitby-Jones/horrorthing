package io.github.jitbyjones.thewake.creature;

import java.util.Locale;
import net.minecraft.util.Mth;

/**
 * The creature's poses and stop-motion clips, one per clip in the exported model
 * (tools/creature/build_creature.py). A pose is a one-frame clip. Heights are at scale 1, in
 * blocks, the tallest frame, without anything thin that may touch a ceiling, and decide where it
 * fits: under a three-block ceiling it stoops; through two blocks it crawls.
 */
public enum CreaturePose {
    STAND(1, 3.00F),
    STOOP(1, 2.71F),
    CROUCH(1, 1.92F),
    PEEK_RIGHT(1, 2.95F),
    PEEK_LEFT(1, 2.95F),
    REACH(1, 2.87F),
    BEDSIDE(1, 2.15F),
    LIE(1, 0.31F),
    /** A stride on long legs, eight frames, a cycle every two blocks. */
    WALK(8, 2.78F),
    /** Bent double under a low ceiling, eight frames. */
    STOOP_WALK(8, 2.60F),
    /** On all fours through a doorway, eight frames. */
    CRAWL(8, 1.68F),
    /** Reaching you: ten frames, the last held. */
    LUNGE(10, 3.27F),
    /** Sitting up out of a bed and standing, twelve frames. */
    RISE(12, 3.01F),
    /** Leaning out from behind a wall edge, six frames, the last held. */
    LEAN_RIGHT(6, 3.04F),
    LEAN_LEFT(6, 3.04F),
    /** Running at you, pitched forward, arms trailing, knees too high: eight frames, three blocks. */
    RUN(8, 3.06F),
    /** Up a wall, spread flat against it like something with too many joints, hand over hand. */
    CLIMB(8, 3.88F),
    /** Off the ground and at you: six frames, the last held until it lands. */
    LEAP(6, 3.01F),
    /** Holding you up in front of its face. */
    HOLD(1, 3.29F),
    /** Throwing something aside: a door, or you. */
    THROW(6, 3.34F),
    /** Head back, the chin piece all the way down. */
    SCREAM(6, 3.28F),
    /** Blinded: the hands come up over the card. */
    STUNNED(6, 3.19F),
    /** Hanging off a ledge by its fingers, the face just over the edge. */
    LEDGE(1, 0.42F),
    /** Under water, coming at you, long and flat. */
    SWIM(8, 1.25F);

    private static final CreaturePose[] VALUES = values();

    public final int frames;
    public final float height;
    private final String clipName;

    CreaturePose(int frames, float height) {
        this.frames = frames;
        this.height = height;
        this.clipName = name().toLowerCase(Locale.ROOT);
    }

    /** The clip's name in the model. */
    public String clipName() {
        return clipName;
    }

    public float height(int frame) {
        return height;
    }

    public static CreaturePose byId(int id) {
        return VALUES[Mth.clamp(id, 0, VALUES.length - 1)];
    }

    /** A clip it travels in, a frame per step. */
    public boolean isMoving() {
        return this == WALK || this == STOOP_WALK || this == CRAWL || this == RUN || this == CLIMB || this == SWIM;
    }

    /** Blocks one full cycle of a moving clip carries it. */
    public double stride() {
        return switch (this) {
            case CRAWL -> 1.25;
            case STOOP_WALK -> 1.6;
            case RUN -> 3.0;
            case CLIMB -> 1.4;
            case SWIM -> 2.2;
            default -> 2.0;
        };
    }

    public boolean isCrawl() {
        return this == CRAWL;
    }
}
