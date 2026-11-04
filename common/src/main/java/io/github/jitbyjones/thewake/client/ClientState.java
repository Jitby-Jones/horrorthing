package io.github.jitbyjones.thewake.client;

/** What this client knows about its own haunting. Reset on disconnect. */
public final class ClientState {
    public static float clarity;
    public static int stage;
    /** It is moving right now (the player is in a lapse and it is coming). */
    public static boolean hunting;
    public static boolean ended;
    /** Day time the player last lay down: where clocks stop near it. -1 if never. */
    public static long lastLayDown = -1L;
    /** Its temper, as a form: 0 watcher .. 3 furious. */
    public static int form;
    /** How afraid the director thinks you are. */
    public static float tension;
    /** Its body is out after you. */
    public static boolean chasing;
    /** Pins pulled from its card. */
    public static int pins;

    /** Effects, as client tick counts at which they end. */
    public static long blackoutStart;
    public static long blackoutEnd;
    public static long wakeTintStart;
    public static long wakeTintEnd;
    public static long clockStopEnd;
    public static long fallAsleepStart;
    public static long fallAsleepEnd;

    private ClientState() {}

    public static void reset() {
        clarity = 0.0F;
        stage = 0;
        hunting = false;
        ended = false;
        lastLayDown = -1L;
        form = 0;
        tension = 0.0F;
        chasing = false;
        pins = 0;
        blackoutStart = blackoutEnd = 0L;
        wakeTintStart = wakeTintEnd = 0L;
        clockStopEnd = 0L;
        fallAsleepStart = fallAsleepEnd = 0L;
    }
}
