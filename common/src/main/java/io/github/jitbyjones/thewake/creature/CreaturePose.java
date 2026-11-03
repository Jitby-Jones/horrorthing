package io.github.jitbyjones.thewake.creature;

import java.util.Locale;
import net.minecraft.util.Mth;

/**
 * The creature's poses, one per pose in the exported model (tools/creature/build_creature.py).
 * Heights are at scale 1, in blocks, and decide where it fits: under a three-block ceiling it
 * cannot stand, so it stoops; through a two-block gap it crawls.
 */
public enum CreaturePose {
    STAND(2.95F),
    STOOP(2.66F),
    CROUCH(2.15F),
    CRAWL_A(1.40F),
    CRAWL_B(1.40F),
    PEEK_RIGHT(2.88F),
    PEEK_LEFT(2.88F),
    REACH(2.88F),
    BEDSIDE(2.28F),
    LUNGE(3.10F),
    LIE(0.40F),
    WALK_A(2.86F),
    WALK_B(2.86F);

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
