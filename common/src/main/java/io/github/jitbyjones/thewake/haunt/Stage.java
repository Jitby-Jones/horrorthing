package io.github.jitbyjones.thewake.haunt;

/**
 * Acts of the haunting, keyed by clarity: how complete the Wake's picture of the player is.
 * Clarity is also what the player sees: exposures get sharper as it rises.
 */
public enum Stage {
    QUIET(0.0F),
    NIGHT_SHIFT(0.03F),
    EXPOSURE(0.10F),
    SIGHTINGS(0.28F),
    ROUTINE(0.45F),
    THEFT(0.62F),
    LIKENESS(0.78F),
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
