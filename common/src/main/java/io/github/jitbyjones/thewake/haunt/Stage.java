package io.github.jitbyjones.thewake.haunt;

/**
 * Acts of the haunting, keyed by clarity: how complete the Wake's picture of the player is.
 *
 * <ul>
 * <li>NIGHT_SHIFT (Act I): chores while you sleep. Nothing to see.</li>
 * <li>WATCHING (Act II): far off and enormous, on the horizon after you lose time.</li>
 * <li>SIGHTINGS (Act III): close enough to see properly; it moves while you are in menus, but
 * keeps its distance.</li>
 * <li>ROUTINE: it comes all the way to the foot of your bed.</li>
 * <li>HUNT (Act IV): if it reaches you while you are not watching, it attacks.</li>
 * <li>HUSH (Act V): silence, then the head turn.</li>
 * <li>STOPPED: the finale.</li>
 * </ul>
 */
public enum Stage {
    QUIET(0.0F),
    NIGHT_SHIFT(0.03F),
    WATCHING(0.10F),
    SIGHTINGS(0.28F),
    ROUTINE(0.45F),
    HUNT(0.62F),
    HUSH(0.78F),
    STOPPED(0.95F);

    public final float threshold;

    Stage(float threshold) {
        this.threshold = threshold;
    }

    public static Stage of(float clarity) {
        Stage result = QUIET;
        for (Stage stage : values()) {
            if (clarity >= stage.threshold) {
                result = stage;
            }
        }
        return result;
    }

    public boolean atLeast(Stage other) {
        return this.ordinal() >= other.ordinal();
    }
}
