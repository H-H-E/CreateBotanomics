package education.poiesis.createbattlebots.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import education.poiesis.createbattlebots.core.ControlMode;
import education.poiesis.createbattlebots.data.ArenaRecord;
import education.poiesis.createbattlebots.data.BotRecord;
import education.poiesis.createbattlebots.server.BattleService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Commands use server player identity; clients never choose their owner or physics body UUID. */
public final class BattleCommands {
    private BattleCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("battlebots")
                .executes(ctx -> run(ctx, null, (service, player) -> service.showHelp(player)))
                .then(literal("help").executes(ctx -> run(ctx, null, (service, player) -> service.showHelp(player))))
                 .then(literal("practice").executes(ctx -> {
                    education.poiesis.createbattlebots.practice.PracticeService.action(ctx.getSource().getPlayerOrException(),0); return 1;
                }).then(literal("walker").executes(ctx -> {
                    education.poiesis.createbattlebots.practice.PracticeService.action(ctx.getSource().getPlayerOrException(),1); return 1;
                })))
                .then(literal("leave").executes(ctx -> {
                    education.poiesis.createbattlebots.practice.PracticeService.action(ctx.getSource().getPlayerOrException(),5); return 1;
                }))
                .then(literal("diagnostics").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(service(ctx).diagnostics()), false);
                    return 1;
                }))
                .then(literal("arena")
                        .then(literal("create").requires(source -> source.hasPermission(2))
                                .then(argument("arena", StringArgumentType.word())
                                        .then(argument("min", BlockPosArgument.blockPos())
                                                .then(argument("max", BlockPosArgument.blockPos())
                                                        .executes(ctx -> run(ctx, "Arena created.", (service, player) ->
                                                                service.createArena(player, name(ctx, "arena"),
                                                                        BlockPosArgument.getBlockPos(ctx, "min"),
                                                                        BlockPosArgument.getBlockPos(ctx, "max"))))))))
                        .then(literal("remove").requires(source -> source.hasPermission(2))
                                .then(argument("arena", StringArgumentType.word()).suggests(BattleCommands::suggestArenas)
                                        .executes(ctx -> run(ctx, "Arena removed.", (service, player) ->
                                                service.removeArena(player, name(ctx, "arena"))))))
                        .then(literal("list").executes(BattleCommands::listArenas)))
                .then(literal("bot")
                        .then(literal("register")
                                .then(argument("bot", StringArgumentType.word())
                                        .then(argument("transponder", BlockPosArgument.blockPos())
                                                .executes(ctx -> registerBot(ctx, "ground"))
                                                .then(argument("class", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                List.of("ground", "aerial", "walking", "hybrid"), builder))
                                                        .executes(ctx -> registerBot(ctx, name(ctx, "class")))))))
                        .then(literal("attach")
                                .then(argument("bot", StringArgumentType.word()).suggests(BattleCommands::suggestOwnedBots)
                                        .then(argument("position", BlockPosArgument.blockPos())
                                                .executes(ctx -> run(ctx, "Body attached to bot registration.", (service, player) ->
                                                        service.attachBody(player, name(ctx, "bot"), BlockPosArgument.getBlockPos(ctx, "position")))))))
                        .then(literal("unregister")
                                .then(argument("bot", StringArgumentType.word()).suggests(BattleCommands::suggestOwnedBots)
                                        .executes(ctx -> run(ctx, "Bot unregistered.", (service, player) ->
                                                service.unregisterBot(player, name(ctx, "bot"))))))
                        .then(literal("repair")
                                .then(argument("bot", StringArgumentType.word()).suggests(BattleCommands::suggestOwnedBots)
                                        .executes(ctx -> run(ctx, "Bot repaired.", (service, player) ->
                                                service.repairBot(player, name(ctx, "bot"))))))
                        .then(literal("list").executes(BattleCommands::listBots)))
                .then(literal("join")
                        .then(argument("arena", StringArgumentType.word()).suggests(BattleCommands::suggestArenas)
                                .then(argument("bot", StringArgumentType.word()).suggests(BattleCommands::suggestOwnedBots)
                                        .executes(ctx -> run(ctx, "Bot entered into the arena.", (service, player) ->
                                                service.join(player, name(ctx, "arena"), name(ctx, "bot")))))))
                .then(literal("leave")
                        .then(argument("arena", StringArgumentType.word()).suggests(BattleCommands::suggestArenas)
                                .then(argument("bot", StringArgumentType.word()).suggests(BattleCommands::suggestOwnedBots)
                                        .executes(ctx -> run(ctx, "Bot removed from the arena roster.", (service, player) ->
                                                service.leave(player, name(ctx, "arena"), name(ctx, "bot")))))))
                .then(literal("start").requires(source -> source.hasPermission(2))
                        .then(argument("arena", StringArgumentType.word()).suggests(BattleCommands::suggestArenas)
                                .executes(ctx -> start(ctx, ControlMode.HUMAN))
                                .then(literal("human").executes(ctx -> start(ctx, ControlMode.HUMAN)))
                                .then(literal("autonomous").executes(ctx -> start(ctx, ControlMode.AUTONOMOUS)))))
                .then(literal("stop").requires(source -> source.hasPermission(2))
                        .then(argument("arena", StringArgumentType.word()).suggests(BattleCommands::suggestArenas)
                                .executes(ctx -> run(ctx, "Match stopped; restoration requested.", (service, player) ->
                                        service.stop(player, name(ctx, "arena"))))))
                .then(literal("recover").requires(source -> source.hasPermission(2))
                        .then(argument("arena", StringArgumentType.word()).suggests(BattleCommands::suggestArenas)
                                .executes(ctx -> run(ctx, "Recovery requested.", (service, player) ->
                                        service.recover(player, name(ctx, "arena"))))))
                .then(literal("status")
                        .executes(ctx -> status(ctx, service(ctx).arenaAt(ctx.getSource().getPosition())
                                .map(arena -> arena.name).orElse("")))
                        .then(argument("arena", StringArgumentType.word()).suggests(BattleCommands::suggestArenas)
                                .executes(ctx -> status(ctx, name(ctx, "arena")))))
                .then(literal("surrender").executes(ctx -> run(ctx, "Surrender recorded.", BattleService::surrender))));
    }

    private static int registerBot(CommandContext<CommandSourceStack> ctx, String machineClass) throws CommandSyntaxException {
        return run(ctx, "Bot registered. Use /battlebots join <arena> <bot> when ready.", (service, player) ->
                service.registerBot(player, name(ctx, "bot"), BlockPosArgument.getBlockPos(ctx, "transponder"), machineClass));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, ControlMode mode) throws CommandSyntaxException {
        return run(ctx, "Match countdown started.", (service, player) -> service.start(player, name(ctx, "arena"), mode));
    }

    private static int status(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            if (name.isEmpty()) throw new IllegalArgumentException("Specify an arena: /battlebots status <arena>");
            Component status = service(ctx).status(name);
            ctx.getSource().sendSuccess(() -> status, false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            ctx.getSource().sendFailure(Component.literal(error.getMessage()));
            return 0;
        }
    }

    private static int listArenas(CommandContext<CommandSourceStack> ctx) {
        Collection<ArenaRecord> arenas = service(ctx).arenas();
        ctx.getSource().sendSuccess(() -> Component.literal(arenas.isEmpty() ? "No arenas registered." : "Arenas (" + arenas.size() + "):"), false);
        arenas.stream().sorted(java.util.Comparator.comparing(a -> a.name)).forEach(arena ->
                ctx.getSource().sendSuccess(() -> Component.literal("• " + arena.name + " | "
                        + arena.min.toShortString() + " → " + arena.max.toShortString()
                        + " | " + ((arena.first == null ? 0 : 1) + (arena.second == null ? 0 : 1)) + "/2 bots"), false));
        return arenas.size();
    }

    private static int listBots(CommandContext<CommandSourceStack> ctx) {
        Collection<BotRecord> bots = service(ctx).bots();
        ctx.getSource().sendSuccess(() -> Component.literal(bots.isEmpty() ? "No bots registered." : "Bots (" + bots.size() + "):"), false);
        bots.stream().sorted(java.util.Comparator.comparing(bot -> bot.name)).forEach(bot ->
                ctx.getSource().sendSuccess(() -> Component.literal("• " + bot.name + " | " + bot.machineClass
                        + " | " + bot.bodies.size() + " bodies | core " + bot.transponder.toShortString()), false));
        return bots.size();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestArenas(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(service(ctx).arenas().stream().map(arena -> arena.name), builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOwnedBots(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(service(ctx).bots().stream().filter(bot ->
                        ctx.getSource().hasPermission(2) || ctx.getSource().getEntity() instanceof ServerPlayer player
                                && player.getUUID().equals(bot.owner)).map(bot -> bot.name), builder);
    }

    private static String name(CommandContext<CommandSourceStack> ctx, String argument) {
        return StringArgumentType.getString(ctx, argument);
    }

    private static BattleService service(CommandContext<CommandSourceStack> ctx) {
        return BattleService.get(ctx.getSource().getLevel());
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String success, PlayerAction action) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        try {
            action.run(service(ctx), player);
            if (success != null) ctx.getSource().sendSuccess(() -> Component.literal(success), false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            ctx.getSource().sendFailure(Component.literal(error.getMessage() == null ? "Action could not be completed." : error.getMessage()));
            return 0;
        }
    }

    @FunctionalInterface
    private interface PlayerAction {
        void run(BattleService service, ServerPlayer player) throws CommandSyntaxException;
    }
}
