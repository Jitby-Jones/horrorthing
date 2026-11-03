package io.github.jitbyjones.thewake.client;

/** What this client knows about its own haunting. Reset on disconnect. */
public final class ClientState {
    public static float clarity;
    public static int stage;
    public static boolean pauseArmed;
    public static boolean ended;
    /** Day time the player last lay down: where clocks stop near it. -1 if never. */
    public static long lastLayDown = -1L;

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
        pauseArmed = false;
        ended = false;
        lastLayDown = -1L;
        blackoutStart = blackoutEnd = 0L;
        wakeTintStart = wakeTintEnd = 0L;
        clockStopEnd = 0L;
        fallAsleepStart = fallAsleepEnd = 0L;
    }
}
