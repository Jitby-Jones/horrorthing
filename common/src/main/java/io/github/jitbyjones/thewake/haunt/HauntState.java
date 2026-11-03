package io.github.jitbyjones.thewake.haunt;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Everything the Wake knows about one player. Persisted in {@link HauntData}. */
public final class HauntState {
    private final UUID id;

    // progress ------------------------------------------------------------------------------
    private float clarity;
    private int peakStage;
    private int nightsSkipped;
    private int nightsKept;
    private int consecutiveKept;
    private int debt;
    private int lapseCount;
    private boolean ended;
    private boolean endingKept;
    private int caughtCount;
    private long hushDay = -1L;
    private long lastAttackDay = -1L;
    private long lastGiantDay = -1L;

    // pacing --------------------------------------------------------------------------------
    private long gainDay = -1L;
    private float gainedToday;
    private long hitchDay = -1L;
    private int hitchesToday;

    // time ----------------------------------------------------------------------------------
    private long lastLogoutMillis = -1L;
    private long lastNightSkipDay = -1L;
    private long lastLayDownDayTime = -1L;
    private int restTicks;

    // places --------------------------------------------------------------------------------
    @Nullable
    private BlockPos bed;
    private int bedDirection = -1;
    private final Long2IntOpenHashMap sleepSpots = new Long2IntOpenHashMap();
    @Nullable
    private BlockPos oldBed;
    @Nullable
    private BlockPos keptBed;
    private int keptBedDirection = -1;
    private final Breadcrumbs crumbs = new Breadcrumbs();
    private final RouteLog today = new RouteLog();
    private final RouteLog yesterday = new RouteLog();
    private long routeDay = -1L;

    // ledger --------------------------------------------------------------------------------
    private int ledgerCount;
    private CompoundTag dayStats = new CompoundTag();
    private CompoundTag lastDayDiff = new CompoundTag();

    // night watch ---------------------------------------------------------------------------
    private long trackedNight = -1L;
    private int nightPresence;
    private int nightIdle;

    // bookkeeping ---------------------------------------------------------------------------
    private final Set<String> flags = new HashSet<>();
    private final Object2LongOpenHashMap<String> cooldowns = new Object2LongOpenHashMap<>();

    // transient -----------------------------------------------------------------------------
    public transient int idleTicks;
    public transient boolean idleLapseOpen;
    public transient int idleLapseTicks;
    @Nullable
    public transient Vec3 lastActivityPos;
    public transient float lastActivityYRot;
    public transient float lastActivityXRot;
    public transient boolean syncDirty = true;
    public transient boolean wasSleeping;
    public transient long sleepStartDayTime = -1L;
    public transient boolean sleepProcessed;
    public transient int lastHitchTick = -100000;

    public HauntState(UUID id) {
        this.id = id;
        this.cooldowns.defaultReturnValue(Long.MIN_VALUE);
    }

    public UUID id() {
        return id;
    }

    // ---- clarity --------------------------------------------------------------------------

    public float clarity() {
        return clarity;
    }

    public Stage stage() {
        return Stage.of(clarity);
    }

    /** Acts are one-way: beats unlock by the highest stage ever reached. */
    public Stage peakStage() {
        return Stage.values()[Mth.clamp(peakStage, 0, Stage.values().length - 1)];
    }

    public void setClarity(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        if (clamped != clarity) {
            clarity = clamped;
            peakStage = Math.max(peakStage, Stage.of(clamped).ordinal());
            syncDirty = true;
        }
    }

    public void forcePeak(Stage stage) {
        peakStage = stage.ordinal();
        syncDirty = true;
    }

    /** Applies the per-day cap to gains. Returns the amount actually applied. */
    float applyGain(float amount, long day, float dailyCap) {
        if (amount <= 0.0F) {
            setClarity(clarity + amount);
            return amount;
        }
        if (gainDay != day) {
            gainDay = day;
            gainedToday = 0.0F;
        }
        float allowed = Math.max(0.0F, Math.min(amount, dailyCap - gainedToday));
        gainedToday += allowed;
        setClarity(clarity + allowed);
        return allowed;
    }

    // ---- counters -------------------------------------------------------------------------

    public int nightsSkipped() { return nightsSkipped; }
    /** Commands only: skip the quiet nights. */
    public void ensureNightsSkipped(int min) { nightsSkipped = Math.max(nightsSkipped, min); }
    public void addDebt() { debt = Math.min(debt + 1, 6); }
    public void incrementNightsSkipped() { nightsSkipped++; consecutiveKept = 0; }
    public int nightsKept() { return nightsKept; }
    public int consecutiveKept() { return consecutiveKept; }
    public void recordKeptNight() { nightsKept++; consecutiveKept++; debt = Math.min(debt + 1, 6); }
    public int debt() { return debt; }
    public void payDebt() { debt = Math.max(0, debt - 1); }
    public int lapseCount() { return lapseCount; }

    /** Every lost moment bumps this. Images from the previous generation vanish. */
    public void lapse() { lapseCount++; }

    public boolean ended() { return ended; }
    public boolean endingKept() { return endingKept; }

    public void setEnded(boolean ended, boolean kept) {
        this.ended = ended;
        this.endingKept = kept;
        syncDirty = true;
    }

    public int caughtCount() { return caughtCount; }
    public void incrementCaught() { caughtCount++; }
    public long hushDay() { return hushDay; }
    /** In-game day of its last attack: one a day at most. */
    public long lastAttackDay() { return lastAttackDay; }
    public void setLastAttackDay(long day) { lastAttackDay = day; }
    public long lastGiantDay() { return lastGiantDay; }
    public void setLastGiantDay(long day) { lastGiantDay = day; }
    public void setHushDay(long day) { hushDay = day; }

    boolean takeHitch(long day, int max) {
        if (hitchDay != day) {
            hitchDay = day;
            hitchesToday = 0;
        }
        if (hitchesToday >= max) {
            return false;
        }
        hitchesToday++;
        return true;
    }

    // ---- time -----------------------------------------------------------------------------

    public long lastLogoutMillis() { return lastLogoutMillis; }
    public void setLastLogoutMillis(long millis) { lastLogoutMillis = millis; }
    public long lastNightSkipDay() { return lastNightSkipDay; }
    public void setLastNightSkipDay(long day) { lastNightSkipDay = day; }
    public long lastLayDownDayTime() { return lastLayDownDayTime; }

    public void setLastLayDownDayTime(long dayTime) {
        lastLayDownDayTime = dayTime;
        syncDirty = true;
    }

    public int restTicks() { return restTicks; }
    public void setRestTicks(int ticks) { restTicks = ticks; }

    // ---- beds -----------------------------------------------------------------------------

    @Nullable
    public BlockPos bed() { return bed; }
    public int bedDirection() { return bedDirection; }

    public void setBed(@Nullable BlockPos pos, int direction2d) {
        bed = pos == null ? null : pos.immutable();
        bedDirection = direction2d;
    }

    /** Nights already slept within {@code radius} blocks of {@code pos}. Two beds side by side are one place. */
    public int nightsNear(BlockPos pos, double radius) {
        int total = 0;
        double r2 = radius * radius;
        for (var e : sleepSpots.long2IntEntrySet()) {
            if (BlockPos.of(e.getLongKey()).distSqr(pos) <= r2) {
                total += e.getIntValue();
            }
        }
        return total;
    }

    public void recordNightAt(BlockPos pos) {
        sleepSpots.addTo(pos.asLong(), 1);
        if (sleepSpots.size() > 64) {
            long worst = 0L;
            int worstCount = Integer.MAX_VALUE;
            for (var e : sleepSpots.long2IntEntrySet()) {
                if (e.getIntValue() < worstCount) {
                    worstCount = e.getIntValue();
                    worst = e.getLongKey();
                }
            }
            sleepSpots.remove(worst);
        }
    }

    @Nullable
    public BlockPos oldBed() { return oldBed; }
    public void setOldBed(@Nullable BlockPos pos) { oldBed = pos == null ? null : pos.immutable(); }
    @Nullable
    public BlockPos keptBed() { return keptBed; }
    public int keptBedDirection() { return keptBedDirection; }

    public void setKeptBed(@Nullable BlockPos pos, int direction2d) {
        keptBed = pos == null ? null : pos.immutable();
        keptBedDirection = direction2d;
    }

    // ---- routes ---------------------------------------------------------------------------

    public Breadcrumbs crumbs() { return crumbs; }
    public RouteLog today() { return today; }
    public RouteLog yesterday() { return yesterday; }
    public long routeDay() { return routeDay; }

    /**
     * New day: today's route becomes the one it replays. Returns how much of today retraced
     * yesterday (0..1): routine is what it learns from.
     */
    public float rollRoutes(long day) {
        routeDay = day;
        float overlap = 0.0F;
        if (today.size() >= 8 && yesterday.size() >= 8) {
            int hits = 0;
            var before = yesterday.points();
            for (BlockPos p : today.points()) {
                for (BlockPos q : before) {
                    if (p.distSqr(q) <= 9.0) {
                        hits++;
                        break;
                    }
                }
            }
            overlap = hits / (float) today.size();
        }
        if (today.size() >= 6) {
            yesterday.copyFrom(today);
        }
        today.clear();
        return overlap;
    }

    // ---- ledger ---------------------------------------------------------------------------

    public int ledgerCount() { return ledgerCount; }
    public void countLedger() { ledgerCount++; }
    public CompoundTag dayStats() { return dayStats; }
    public void setDayStats(CompoundTag tag) { dayStats = tag; }
    public CompoundTag lastDayDiff() { return lastDayDiff; }
    public void setLastDayDiff(CompoundTag tag) { lastDayDiff = tag; }

    // ---- night watch ----------------------------------------------------------------------

    public long trackedNight() { return trackedNight; }

    public void beginNight(long night) {
        trackedNight = night;
        nightPresence = 0;
        nightIdle = 0;
    }

    public void addNightPresence(int ticks, boolean idle) {
        nightPresence += ticks;
        if (idle) {
            nightIdle += ticks;
        }
    }

    public int nightPresence() { return nightPresence; }
    public int nightIdle() { return nightIdle; }

    // ---- bookkeeping ----------------------------------------------------------------------

    public boolean hasFlag(String flag) { return flags.contains(flag); }
    public void setFlag(String flag) { flags.add(flag); syncDirty = true; }
    public void clearFlag(String flag) { flags.remove(flag); syncDirty = true; }
    public boolean ready(String event, long gameTime) { return gameTime >= cooldowns.getLong(event); }
    public void cooldown(String event, long gameTime, long ticks) { cooldowns.put(event, gameTime + ticks); }

    /** After escaping: forget the routes and the beds. What follows the player is kept elsewhere. */
    public void forget() {
        clarity = 0.0F;
        crumbs.clear();
        today.clear();
        yesterday.clear();
        sleepSpots.clear();
        cooldowns.clear();
        debt = 0;
        consecutiveKept = 0;
        syncDirty = true;
    }

    /** Full wipe for the debug command. */
    public void reset() {
        forget();
        peakStage = 0;
        nightsSkipped = 0;
        nightsKept = 0;
        lapseCount++;
        ended = false;
        endingKept = false;
        caughtCount = 0;
        hushDay = -1L;
        ledgerCount = 0;
        flags.clear();
        bed = null;
        oldBed = null;
        keptBed = null;
        gainedToday = 0.0F;
    }

    // ---- persistence ----------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putFloat("Clarity", clarity);
        tag.putInt("PeakStage", peakStage);
        tag.putInt("NightsSkipped", nightsSkipped);
        tag.putInt("NightsKept", nightsKept);
        tag.putInt("ConsecutiveKept", consecutiveKept);
        tag.putInt("Debt", debt);
        tag.putInt("Lapses", lapseCount);
        tag.putBoolean("Ended", ended);
        tag.putBoolean("EndingKept", endingKept);
        tag.putInt("Caught", caughtCount);
        tag.putLong("LikenessDay", hushDay);
        tag.putLong("LastAttackDay", lastAttackDay);
        tag.putLong("LastGiantDay", lastGiantDay);
        tag.putLong("GainDay", gainDay);
        tag.putFloat("GainedToday", gainedToday);
        tag.putLong("LastLogout", lastLogoutMillis);
        tag.putLong("LastSkipDay", lastNightSkipDay);
        tag.putLong("LastLayDown", lastLayDownDayTime);
        tag.putInt("RestTicks", restTicks);
        if (bed != null) {
            tag.put("Bed", NbtUtils.writeBlockPos(bed));
        }
        tag.putInt("BedDir", bedDirection);
        long[] keys = sleepSpots.keySet().toLongArray();
        int[] counts = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            counts[i] = sleepSpots.get(keys[i]);
        }
        tag.putLongArray("SleepSpots", keys);
        tag.putIntArray("SleepSpotNights", counts);
        if (oldBed != null) {
            tag.put("OldBed", NbtUtils.writeBlockPos(oldBed));
        }
        if (keptBed != null) {
            tag.put("KeptBed", NbtUtils.writeBlockPos(keptBed));
        }
        tag.putInt("KeptBedDir", keptBedDirection);
        CompoundTag routes = new CompoundTag();
        crumbs.save(routes);
        today.save(routes, "Today");
        yesterday.save(routes, "Yesterday");
        tag.put("Routes", routes);
        tag.putLong("RouteDay", routeDay);
        tag.putInt("Ledgers", ledgerCount);
        tag.put("DayStats", dayStats);
        tag.put("LastDayDiff", lastDayDiff);
        tag.putLong("TrackedNight", trackedNight);
        tag.putInt("NightPresence", nightPresence);
        tag.putInt("NightIdle", nightIdle);
        ListTag flagList = new ListTag();
        for (String flag : flags) {
            flagList.add(StringTag.valueOf(flag));
        }
        tag.put("Flags", flagList);
        CompoundTag cd = new CompoundTag();
        for (var e : cooldowns.object2LongEntrySet()) {
            cd.putLong(e.getKey(), e.getLongValue());
        }
        tag.put("Cooldowns", cd);
        return tag;
    }

    public static HauntState load(CompoundTag tag) {
        HauntState s = new HauntState(tag.getUUID("Id"));
        s.clarity = Mth.clamp(tag.getFloat("Clarity"), 0.0F, 1.0F);
        s.peakStage = tag.getInt("PeakStage");
        s.nightsSkipped = tag.getInt("NightsSkipped");
        s.nightsKept = tag.getInt("NightsKept");
        s.consecutiveKept = tag.getInt("ConsecutiveKept");
        s.debt = tag.getInt("Debt");
        s.lapseCount = tag.getInt("Lapses");
        s.ended = tag.getBoolean("Ended");
        s.endingKept = tag.getBoolean("EndingKept");
        s.caughtCount = tag.getInt("Caught");
        s.hushDay = tag.contains("LikenessDay") ? tag.getLong("LikenessDay") : -1L;
        s.lastAttackDay = tag.contains("LastAttackDay") ? tag.getLong("LastAttackDay") : -1L;
        s.lastGiantDay = tag.contains("LastGiantDay") ? tag.getLong("LastGiantDay") : -1L;
        s.gainDay = tag.contains("GainDay") ? tag.getLong("GainDay") : -1L;
        s.gainedToday = tag.getFloat("GainedToday");
        s.lastLogoutMillis = tag.contains("LastLogout") ? tag.getLong("LastLogout") : -1L;
        s.lastNightSkipDay = tag.contains("LastSkipDay") ? tag.getLong("LastSkipDay") : -1L;
        s.lastLayDownDayTime = tag.contains("LastLayDown") ? tag.getLong("LastLayDown") : -1L;
        s.restTicks = tag.getInt("RestTicks");
        NbtUtils.readBlockPos(tag, "Bed").ifPresent(p -> s.bed = p);
        s.bedDirection = tag.contains("BedDir") ? tag.getInt("BedDir") : -1;
        long[] keys = tag.getLongArray("SleepSpots");
        int[] counts = tag.getIntArray("SleepSpotNights");
        for (int i = 0; i < Math.min(keys.length, counts.length); i++) {
            s.sleepSpots.put(keys[i], counts[i]);
        }
        NbtUtils.readBlockPos(tag, "OldBed").ifPresent(p -> s.oldBed = p);
        NbtUtils.readBlockPos(tag, "KeptBed").ifPresent(p -> s.keptBed = p);
        s.keptBedDirection = tag.contains("KeptBedDir") ? tag.getInt("KeptBedDir") : -1;
        CompoundTag routes = tag.getCompound("Routes");
        s.crumbs.load(routes);
        s.today.load(routes, "Today");
        s.yesterday.load(routes, "Yesterday");
        s.routeDay = tag.contains("RouteDay") ? tag.getLong("RouteDay") : -1L;
        s.ledgerCount = tag.getInt("Ledgers");
        s.dayStats = tag.getCompound("DayStats");
        s.lastDayDiff = tag.getCompound("LastDayDiff");
        s.trackedNight = tag.contains("TrackedNight") ? tag.getLong("TrackedNight") : -1L;
        s.nightPresence = tag.getInt("NightPresence");
        s.nightIdle = tag.getInt("NightIdle");
        ListTag flagList = tag.getList("Flags", Tag.TAG_STRING);
        for (int i = 0; i < flagList.size(); i++) {
            s.flags.add(flagList.getString(i));
        }
        CompoundTag cd = tag.getCompound("Cooldowns");
        for (String key : cd.getAllKeys()) {
            s.cooldowns.put(key, cd.getLong(key));
        }
        return s;
    }
}
