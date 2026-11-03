package io.github.jitbyjones.thewake;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.haunt.Showcase;
import io.github.jitbyjones.thewake.haunt.Stage;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /thewake} for operators: inspect a player's haunting, move it along, or trigger one beat to
 * see it. Never needed to play; useful to test or to skip ahead.
 */
public final class WakeCommands {
    private WakeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("thewake")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> status(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("clarity")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.0F, 1.0F))
                                        .executes(ctx -> clarity(ctx, EntityArgument.getPlayer(ctx, "player"),
                                                FloatArgumentType.getFloat(ctx, "value"))))))
                .then(Commands.literal("stage")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("stage", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                Arrays.stream(Stage.values()).map(s -> s.name().toLowerCase(Locale.ROOT)), b))
                                        .executes(ctx -> stage(ctx, EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "stage"))))))
                .then(Commands.literal("beat")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("beat", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Showcase.BEATS, b))
                                        .executes(ctx -> beat(ctx, EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "beat"))))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> reset(ctx, EntityArgument.getPlayer(ctx, "player"))))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        HauntState s = Haunt.state(player);
        String text = String.format(Locale.ROOT,
                "%s: clarity %.3f, stage %s (peak %s), nights skipped %d, kept %d, debt %d, ledgers %d, caught %d, ended %s%s",
                player.getGameProfile().getName(), s.clarity(), s.stage(), s.peakStage(), s.nightsSkipped(), s.nightsKept(), s.debt(),
                s.ledgerCount(), s.caughtCount(), s.ended(), s.endingKept() ? " (kept)" : "");
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int clarity(CommandContext<CommandSourceStack> ctx, ServerPlayer player, float value) {
        HauntState s = Haunt.state(player);
        Haunt.force(player, s, value);
        return status(ctx, player);
    }

    private static int stage(CommandContext<CommandSourceStack> ctx, ServerPlayer player, String name) throws CommandSyntaxException {
        Stage stage;
        try {
            stage = Stage.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Unknown stage " + name));
            return 0;
        }
        HauntState s = Haunt.state(player);
        Showcase.wakeUp(s);
        Haunt.force(player, s, Math.min(1.0F, stage.threshold + 0.005F));
        return status(ctx, player);
    }

    private static int beat(CommandContext<CommandSourceStack> ctx, ServerPlayer player, String name) {
        String result = Showcase.trigger(player, name.toLowerCase(Locale.ROOT));
        ctx.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        HauntState s = Haunt.state(player);
        s.reset();
        ctx.getSource().sendSuccess(() -> Component.literal("Reset " + player.getGameProfile().getName()), true);
        return 1;
    }
}
