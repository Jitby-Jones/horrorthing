package io.github.jitbyjones.thewake.haunt;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * What it thinks of you, as opposed to how well it knows you (that is clarity). Anger is what you
 * have done to it: hurting it, keeping it out of your nights, hiding from it in ways it has to tear
 * down. Tactics are what you did to get away, counted, so that the next time it is ready for them.
 */
public final class Reputation {
    /** Its temper, as it shows: each one a worse version of the face. */
    public enum Form {
        /** It only watches. The card is clean. */
        WATCHER(0.0F),
        /** The card is creased, the jaw hangs, it knocks. */
        UNEASY(0.25F),
        /** Stained, leaning, and it will come for you when you are most afraid. */
        ANGRY(0.5F),
        /** Torn half off, on all fours, screaming. */
        FURIOUS(0.75F);

        public final float threshold;

        Form(float threshold) {
            this.threshold = threshold;
        }

        public static Form of(float anger) {
            Form out = WATCHER;
            for (Form f : values()) {
                if (anger >= f.threshold) {
                    out = f;
                }
            }
            return out;
        }

        public boolean atLeast(Form other) {
            return ordinal() >= other.ordinal();
        }
    }

    /** Ways a player got away, or tried to. */
    public enum Tactic {
        WATER, BOAT, PILLAR, TOWER, BOX, LIGHT, DOOR, PORTAL, LOGOUT, FLASH, HIDE, OUTRUN, CHEAT, HURT, SLEEPLESS;

        static final Tactic[] VALUES = values();
    }

    private static final int MAX_SPOTS = 8;

    float anger;
    /** How much it wants to watch you: grows when you look back, shrinks when you ignore it. */
    float interest = 0.3F;
    final int[] tactics = new int[Tactic.VALUES.length];
    /** Places you hid in and it found out about: where it looks first. */
    final List<BlockPos> hides = new ArrayList<>();
    /** High places you watch from: where it climbs to. */
    final List<BlockPos> lookouts = new ArrayList<>();
    int chases;
    int escapes;
    int grabs;
    int possessions;
    /** Its four pins, pulled out of the card one at a time. Four and it is nobody. */
    int pins;
    /** Pins pulled while it lay asleep in the rerun: it will not sleep a third time. */
    int pinsWhileAsleep;
    long lastChaseTick = -100000L;
    long lastHurtTick = -100000L;
    /** Gone for good: the card came off. */
    boolean removed;
    /** A chase was still running when the player left the game. */
    boolean chasePending;
    /** It has your tape. You took it back. */
    boolean tapeTaken;
    int lastNightStarted = -1;

    public float anger() {
        return anger;
    }

    public Form form() {
        return Form.of(anger);
    }

    public float interest() {
        return interest;
    }

    public int tactic(Tactic tactic) {
        return tactics[tactic.ordinal()];
    }

    public int chases() {
        return chases;
    }

    public int escapes() {
        return escapes;
    }

    public int grabs() {
        return grabs;
    }

    public int possessions() {
        return possessions;
    }

    public int pins() {
        return pins;
    }

    public int pinsWhileAsleep() {
        return pinsWhileAsleep;
    }

    public boolean removed() {
        return removed;
    }

    public boolean chasePending() {
        return chasePending;
    }

    public boolean tapeTaken() {
        return tapeTaken;
    }

    public long lastChaseTick() {
        return lastChaseTick;
    }

    public long lastHurtTick() {
        return lastHurtTick;
    }

    public List<BlockPos> hides() {
        return hides;
    }

    public List<BlockPos> lookouts() {
        return lookouts;
    }

    void setAnger(float value) {
        anger = Mth.clamp(value, 0.0F, 1.0F);
    }

    void setInterest(float value) {
        interest = Mth.clamp(value, 0.0F, 1.0F);
    }

    void count(Tactic tactic) {
        tactics[tactic.ordinal()] = Math.min(tactics[tactic.ordinal()] + 1, 99);
    }

    static void remember(List<BlockPos> spots, BlockPos pos, double mergeRadius) {
        for (int i = 0; i < spots.size(); i++) {
            if (spots.get(i).closerThan(pos, mergeRadius)) {
                spots.remove(i);
                break;
            }
        }
        spots.add(0, pos.immutable());
        while (spots.size() > MAX_SPOTS) {
            spots.remove(spots.size() - 1);
        }
    }

    @Nullable
    static BlockPos nearest(List<BlockPos> spots, BlockPos to, double within) {
        BlockPos best = null;
        double bestD = within * within;
        for (BlockPos p : spots) {
            double d = p.distSqr(to);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Anger", anger);
        tag.putFloat("Interest", interest);
        CompoundTag t = new CompoundTag();
        for (Tactic tactic : Tactic.VALUES) {
            if (tactics[tactic.ordinal()] > 0) {
                t.putInt(tactic.name(), tactics[tactic.ordinal()]);
            }
        }
        tag.put("Tactics", t);
        tag.putLongArray("Hides", hides.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("Lookouts", lookouts.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putInt("Chases", chases);
        tag.putInt("Escapes", escapes);
        tag.putInt("Grabs", grabs);
        tag.putInt("Possessions", possessions);
        tag.putInt("Pins", pins);
        tag.putInt("PinsAsleep", pinsWhileAsleep);
        tag.putBoolean("Removed", removed);
        tag.putBoolean("ChasePending", chasePending);
        tag.putBoolean("TapeTaken", tapeTaken);
        return tag;
    }

    static Reputation load(CompoundTag tag) {
        Reputation r = new Reputation();
        if (tag.isEmpty()) {
            return r;
        }
        r.anger = Mth.clamp(tag.getFloat("Anger"), 0.0F, 1.0F);
        r.interest = tag.contains("Interest") ? Mth.clamp(tag.getFloat("Interest"), 0.0F, 1.0F) : 0.3F;
        CompoundTag t = tag.getCompound("Tactics");
        for (Tactic tactic : Tactic.VALUES) {
            r.tactics[tactic.ordinal()] = t.getInt(tactic.name());
        }
        for (long l : tag.getLongArray("Hides")) {
            r.hides.add(BlockPos.of(l));
        }
        for (long l : tag.getLongArray("Lookouts")) {
            r.lookouts.add(BlockPos.of(l));
        }
        r.chases = tag.getInt("Chases");
        r.escapes = tag.getInt("Escapes");
        r.grabs = tag.getInt("Grabs");
        r.possessions = tag.getInt("Possessions");
        r.pins = Mth.clamp(tag.getInt("Pins"), 0, 4);
        r.pinsWhileAsleep = tag.getInt("PinsAsleep");
        r.removed = tag.getBoolean("Removed");
        r.chasePending = tag.getBoolean("ChasePending");
        r.tapeTaken = tag.getBoolean("TapeTaken");
        return r;
    }
}
