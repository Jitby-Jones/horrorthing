package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-world store of every player's {@link HauntState}, kept with the overworld's data. */
public final class HauntData extends SavedData {
    private static final String NAME = TheWake.MOD_ID;

    private final Map<UUID, HauntState> states = new HashMap<>();

    public static HauntData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(HauntData::new, HauntData::load, null), NAME);
    }

    public HauntState state(UUID player) {
        return states.computeIfAbsent(player, HauntState::new);
    }

    public Collection<HauntState> all() {
        return states.values();
    }

    @Override
    public boolean isDirty() {
        // Routes and counters change constantly; saving with the level is cheap enough.
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (HauntState state : states.values()) {
            list.add(state.save());
        }
        tag.put("Players", list);
        tag.putInt("Version", 1);
        return tag;
    }

    private static HauntData load(CompoundTag tag, HolderLookup.Provider registries) {
        HauntData data = new HauntData();
        ListTag list = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                HauntState state = HauntState.load(list.getCompound(i));
                data.states.put(state.id(), state);
            } catch (Exception e) {
                TheWake.LOG.warn("Skipping unreadable haunt state: {}", e.toString());
            }
        }
        return data;
    }
}
