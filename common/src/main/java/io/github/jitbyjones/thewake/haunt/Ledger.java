package io.github.jitbyjones.thewake.haunt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * The ledger: a written book left on the bed, authored by the player, in the voice of the vanilla
 * Statistics screen. The Night column copies the Day column, because it lived your day again
 * while you slept. Then one line that is not in vanilla: how long it stood by the bed.
 */
public final class Ledger {
    private static final String[] CUSTOM = {"walk_one_cm", "sprint_one_cm", "crouch_one_cm", "jump", "mob_kills", "open_chest",
            "damage_taken", "fish_caught", "animals_bred"};

    private Ledger() {}

    /** Morning snapshot of the stats the ledger compares against. */
    static void snapshot(ServerPlayer player, HauntState state) {
        state.setLastDayDiff(diff(player, state.dayStats()));
        state.setDayStats(capture(player));
    }

    private static CompoundTag capture(ServerPlayer player) {
        ServerStatsCounter stats = player.getStats();
        CompoundTag tag = new CompoundTag();
        for (String key : CUSTOM) {
            // Custom stat values are matched by identity: use the registered instance, not a new one.
            ResourceLocation id = BuiltInRegistries.CUSTOM_STAT.get(ResourceLocation.withDefaultNamespace(key));
            if (id != null) {
                tag.putInt("c:" + key, stats.getValue(Stats.CUSTOM.get(id)));
            }
        }
        for (Block block : BuiltInRegistries.BLOCK) {
            int v = stats.getValue(Stats.BLOCK_MINED, block);
            if (v > 0) {
                tag.putInt("m:" + BuiltInRegistries.BLOCK.getKey(block), v);
            }
        }
        for (Item item : BuiltInRegistries.ITEM) {
            int crafted = stats.getValue(Stats.ITEM_CRAFTED, item);
            if (crafted > 0) {
                tag.putInt("k:" + BuiltInRegistries.ITEM.getKey(item), crafted);
            }
            if (item.components().has(DataComponents.FOOD)) {
                int eaten = stats.getValue(Stats.ITEM_USED, item);
                if (eaten > 0) {
                    tag.putInt("e:" + BuiltInRegistries.ITEM.getKey(item), eaten);
                }
            }
        }
        return tag;
    }

    private static CompoundTag diff(ServerPlayer player, CompoundTag before) {
        CompoundTag now = capture(player);
        CompoundTag out = new CompoundTag();
        for (String key : now.getAllKeys()) {
            int d = now.getInt(key) - before.getInt(key);
            if (d > 0) {
                out.putInt(key, d);
            }
        }
        return out;
    }

    /** Which ledger, if any, this act still owes. Five ledgers at most, ever. */
    @Nullable
    static String milestoneDue(HauntState state) {
        Stage peak = state.peakStage();
        if (peak.atLeast(Stage.HUNT) && !state.hasFlag("ledger_theft")) {
            return "ledger_theft";
        }
        if (peak.atLeast(Stage.ROUTINE) && !state.hasFlag("ledger_routine")) {
            return "ledger_routine";
        }
        if (peak.atLeast(Stage.WATCHING) && !state.hasFlag("ledger_exposure")) {
            return "ledger_exposure";
        }
        return null;
    }

    static void leave(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, int nightTicks) {
        CompoundTag day = diff(player, state.dayStats());
        Stage peak = state.peakStage();
        List<Component> dayLines = lines(day);
        List<Component> nightLines = new ArrayList<>();
        float stood;
        if (peak.atLeast(Stage.HUNT)) {
            // It stopped pretending.
            nightLines.add(statLine(Component.translatable("stat.minecraft.walk_one_cm"), StatFormatter.DISTANCE.format(0)));
            stood = 1.0F;
        } else {
            List<Component> copy = lines(day);
            nightLines.addAll(copy.subList(0, Math.min(4, copy.size())));
            stood = peak.atLeast(Stage.ROUTINE) ? 0.7F : 0.4F;
        }
        nightLines.add(statLine(Component.translatable("ledger.thewake.stood"), hours((int) (nightTicks * stood))));
        write(player, state, level, head, dayLines, nightLines);
    }

    /** A night somewhere new: it walked all night looking for you, and never stood by the bed. */
    static void searched(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, int nightTicks) {
        CompoundTag day = diff(player, state.dayStats());
        int searchedCm = 180_000 + player.getRandom().nextInt(260_000);
        List<Component> nightLines = new ArrayList<>();
        nightLines.add(statLine(Component.translatable("stat.minecraft.walk_one_cm"), StatFormatter.DISTANCE.format(searchedCm)));
        nightLines.add(statLine(Component.translatable("ledger.thewake.stood"), hours(0)));
        write(player, state, level, head, lines(day), nightLines);
        player.awardStat(Stats.WALK_ONE_CM, searchedCm);
    }

    private static void write(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, List<Component> dayLines,
            List<Component> nightLines) {
        int number = (int) Math.max(1L, level.getDayTime() / 24000L);
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(Filterable.passThrough(page(Component.translatable("ledger.thewake.day", number), dayLines)));
        pages.add(Filterable.passThrough(page(Component.translatable("ledger.thewake.night", number), nightLines)));
        book(player, state, level, head, number, pages);
    }

    /** After the Stopped catches you: the record of a day you did not live. One page; no night. */
    public static void leaveStolenDay(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head) {
        CompoundTag day = state.lastDayDiff().isEmpty() ? diff(player, state.dayStats()) : state.lastDayDiff();
        int number = (int) Math.max(1L, level.getDayTime() / 24000L);
        List<Component> lines = lines(day);
        lines.add(statLine(Component.translatable("stat.minecraft.sleep_in_bed"), "1"));
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(Filterable.passThrough(page(Component.translatable("ledger.thewake.day", number), lines)));
        book(player, state, level, head, number, pages);
    }

    private static void book(ServerPlayer player, HauntState state, ServerLevel level, BlockPos head, int number,
            List<Filterable<Component>> pages) {
        state.countLedger();
        String title = Component.translatable("ledger.thewake.title", number).getString();
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(trim(title)),
                player.getGameProfile().getName(), 0, pages, true));
        drop(level, head, book);
    }

    private static void drop(ServerLevel level, BlockPos head, ItemStack book) {
        Vec3 at = Vec3.atCenterOf(head).add(0.0, 0.2, 0.0);
        ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, book);
        item.setDeltaMovement(Vec3.ZERO);
        item.setUnlimitedLifetime();
        level.addFreshEntity(item);
    }

    private static String trim(String s) {
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    private static List<Component> lines(CompoundTag diff) {
        List<Component> out = new ArrayList<>();
        int walked = diff.getInt("c:walk_one_cm") + diff.getInt("c:sprint_one_cm") + diff.getInt("c:crouch_one_cm");
        out.add(statLine(Component.translatable("stat.minecraft.walk_one_cm"), StatFormatter.DISTANCE.format(walked)));
        top(diff, "m:", 2).forEach(e -> {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(e.getKey()));
            out.add(statLine(Component.translatable("ledger.thewake.mined", block.getName()), Integer.toString(e.getValue())));
        });
        top(diff, "k:", 1).forEach(e -> {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(e.getKey()));
            out.add(statLine(Component.translatable("ledger.thewake.crafted", item.getDescription()), Integer.toString(e.getValue())));
        });
        top(diff, "e:", 1).forEach(e -> {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(e.getKey()));
            out.add(statLine(Component.translatable("ledger.thewake.eaten", item.getDescription()), Integer.toString(e.getValue())));
        });
        if (diff.getInt("c:mob_kills") > 0) {
            out.add(statLine(Component.translatable("stat.minecraft.mob_kills"), Integer.toString(diff.getInt("c:mob_kills"))));
        }
        if (diff.getInt("c:open_chest") > 0) {
            out.add(statLine(Component.translatable("stat.minecraft.open_chest"), Integer.toString(diff.getInt("c:open_chest"))));
        }
        if (diff.getInt("c:jump") > 0) {
            out.add(statLine(Component.translatable("stat.minecraft.jump"), Integer.toString(diff.getInt("c:jump"))));
        }
        // A book page holds fourteen lines; every stat takes two.
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    private static List<Map.Entry<String, Integer>> top(CompoundTag diff, String prefix, int count) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (String key : diff.getAllKeys()) {
            if (key.startsWith(prefix)) {
                entries.add(Map.entry(key.substring(prefix.length()), diff.getInt(key)));
            }
        }
        entries.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        return entries.subList(0, Math.min(count, entries.size()));
    }

    private static Component statLine(Component name, String value) {
        return Component.empty().append(name.copy().withStyle(ChatFormatting.DARK_GRAY)).append("\n  ").append(value).append("\n");
    }

    private static Component page(Component heading, List<Component> lines) {
        MutableComponent page = Component.empty().append(heading.copy().withStyle(ChatFormatting.BOLD)).append("\n\n");
        for (Component line : lines) {
            page.append(line);
        }
        return page;
    }

    /** In-game hours: a night is about eleven of them. */
    private static String hours(int ticks) {
        int h = ticks / 1000;
        int m = (ticks % 1000) * 60 / 1000;
        return h + " h " + (m < 10 ? "0" : "") + m + " m";
    }
}
