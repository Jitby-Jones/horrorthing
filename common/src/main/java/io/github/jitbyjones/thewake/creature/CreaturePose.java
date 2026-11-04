package io.github.jitbyjones.thewake.creature;

import java.util.Locale;
import net.minecraft.util.Mth;

/**
 * The creature's poses, one per pose in the exported model (tools/creature/build_creature.py).
 * Heights are at scale 1, in blocks, without the pins and strings (those may touch a ceiling),
 * and decide where it fits: under a three-block ceiling it stoops; through two blocks it crawls.
 */
public enum CreaturePose {
    STAND(3.00F),
    STOOP(2.70F),
    CROUCH(2.20F),
    CRAWL_A(1.48F),
    CRAWL_B(1.48F),
    PEEK_RIGHT(3.00F),
    PEEK_LEFT(3.00F),
    REACH(2.86F),
    BEDSIDE(2.42F),
    LUNGE(3.16F),
    LIE(0.34F),
    WALK_A(2.64F),
    WALK_B(2.64F);

    private static final CreaturePose[] VALUES = values();

    public final float height;
    private final String modelName;

    CreaturePose(float height) {
        this.height = height;
        this.modelName = name().toLowerCase(Locale.ROOT);
    }

    public String modelName() {
        return modelName;
    }

    public static CreaturePose byId(int id) {
        return VALUES[Mth.clamp(id, 0, VALUES.length - 1)];
    }

    public boolean isWalkFrame() {
        return this == WALK_A || this == WALK_B;
    }

    public boolean isCrawlFrame() {
        return this == CRAWL_A || this == CRAWL_B;
    }
}
