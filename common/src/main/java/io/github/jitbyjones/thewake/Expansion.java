package io.github.jitbyjones.thewake;

import com.mojang.brigadier.context.CommandContext;
import io.github.jitbyjones.thewake.entity.WakeBrain;
import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.haunt.Possession;
import io.github.jitbyjones.thewake.haunt.Pursuit;
import io.github.jitbyjones.thewake.haunt.Reputation;
import io.github.jitbyjones.thewake.haunt.Showcase;
import io.github.jitbyjones.thewake.haunt.Temper;
import io.github.jitbyjones.thewake.haunt.WakeDimensions;
import io.github.jitbyjones.thewake.item.TapeItem;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.world.Passages;
import io.github.jitbyjones.thewake.world.TheSet;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** The {@code /thewake} subcommands for its temper, its body and its places. Testing only. */
final class Expansion {
    static final String[] PURPOSES = {"chase", "stalk", "lurk", "siege", "asleep", "last_night"};

    private Expansion() {}

    static String describe(ServerPlayer player, HauntState s) {
        Reputation r = s.reputation();
        StringBuilder tactics = new StringBuilder();
        for (Reputation.Tactic t : Reputation.Tactic.values()) {
            if (r.tactic(t) > 0) {
                tactics.append(t.name().toLowerCase(Locale.ROOT)).append(' ').append(r.tactic(t)).append(", ");
            }
        }
        return String.format(Locale.ROOT, "anger %.2f (%s), interest %.2f, tension %.2f, chases %d, escapes %d, grabs %d, pins %d, body %s; learned: %s",
                r.anger(), r.form().name().toLowerCase(Locale.ROOT), r.interest(), s.tension, r.chases(), r.escapes(), r.grabs(), r.pins(),
                Pursuit.active(player) ? "out" : "no", tactics.length() == 0 ? "nothing" : tactics.toString());
    }

    static int anger(CommandContext<CommandSourceStack> ctx, ServerPlayer player, float value) {
        HauntState s = Haunt.state(player);
        Showcase.wakeUp(s);
        float delta = value - s.reputation().anger();
        Temper.provoke(player, s, delta >= 0 ? Temper.Provocation.CHEESE : Temper.Provocation.CAUGHT,
                Math.abs(delta) / Math.abs(delta >= 0 ? Temper.Provocation.CHEESE.amount : Temper.Provocation.CAUGHT.amount));
        ctx.getSource().sendSuccess(() -> Component.literal(describe(player, s)), false);
        return 1;
    }

    static int tension(CommandContext<CommandSourceStack> ctx, ServerPlayer player, float value) {
        HauntState s = Haunt.state(player);
        s.tension = value;
        s.lastScareTick = -100000L;
        ctx.getSource().sendSuccess(() -> Component.literal(describe(player, s)), false);
        return 1;
    }

    static int body(CommandContext<CommandSourceStack> ctx, ServerPlayer player, String purpose) {
        WakeBrain.Purpose why;
        try {
            why = WakeBrain.Purpose.valueOf(purpose.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Unknown purpose " + purpose));
            return 0;
        }
        HauntState s = Haunt.state(player);
        Showcase.wakeUp(s);
        double min = why == WakeBrain.Purpose.SIEGE ? 4.0 : why == WakeBrain.Purpose.LURK ? 3.0 : 14.0;
        Vec3 at = Pursuit.reachableNear(player, min, min + 8.0);
        if (at == null) {
            at = player.position().add(player.getViewVector(1.0F).multiply(-1, 0, -1).normalize().scale(min));
        }
        var body = Pursuit.spawn(player, at, why);
        ctx.getSource().sendSuccess(() -> Component.literal(body == null ? "Could not place it" : "It is out: " + why), false);
        return body == null ? 0 : 1;
    }

    static int go(CommandContext<CommandSourceStack> ctx, ServerPlayer player, String place) {
        switch (place) {
            case "archive" -> Passages.intoArchive(player);
            case "set" -> TheSet.enter(player);
            default -> WakeDimensions.home(player);
        }
        return 1;
    }

    static int pins(CommandContext<CommandSourceStack> ctx, ServerPlayer player, int count) {
        HauntState s = Haunt.state(player);
        while (Temper.pins(s) < count) {
            Temper.pullPin(s, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(describe(player, s)), false);
        return 1;
    }

    static int kit(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        player.getInventory().add(new ItemStack(WakeContent.OLD_CAMERA.get()));
        player.getInventory().add(new ItemStack(WakeContent.FLASH_BULB.get(), 16));
        player.getInventory().add(TapeItem.stamped(player));
        player.getInventory().add(new ItemStack(WakeContent.VCR.get()));
        return 1;
    }

    static int possess(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        HauntState s = Haunt.state(player);
        Possession.demand(player, s, Possession.Why.COMMAND);
        ctx.getSource().sendSuccess(() -> Component.literal(Possession.exempt(player) ? "Exempt: this world started in creative"
                : Possession.pending(player) ? "It has the chat box" : "Nothing to do (not in creative or spectator)"), false);
        return 1;
    }
}
