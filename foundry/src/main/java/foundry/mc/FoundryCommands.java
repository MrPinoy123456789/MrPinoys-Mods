package foundry.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import foundry.core.ContentSet;
import foundry.core.Ident;
import foundry.core.Problem;
import java.util.Map;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** {@code /foundry help|status|validate|give}. Operator only. */
final class FoundryCommands {
    private FoundryCommands() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("foundry").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> help(ctx.getSource()))
                        .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("validate").executes(ctx -> validate(ctx.getSource())))
                        .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))
                        .then(Commands.literal("pack").then(Commands.literal("status")
                                .executes(ctx -> packStatus(ctx.getSource())))
                                .then(Commands.literal("build")
                                .executes(ctx -> packBuild(ctx.getSource()))))
                        .then(Commands.literal("rename")
                                .then(Commands.argument("old", IdentifierArgument.id())
                                        .then(Commands.argument("new", IdentifierArgument.id())
                                                .executes(ctx -> rename(ctx.getSource(),
                                                        IdentifierArgument.getId(ctx, "old").toString(),
                                                        IdentifierArgument.getId(ctx, "new").toString())))))
                        .then(Commands.literal("give")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("id", IdentifierArgument.id())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        FoundryItems.all().keySet().stream().map(Object::toString).sorted().toList(), builder))
                                                .executes(ctx -> give(ctx.getSource(), EntityArgument.getPlayer(ctx, "target"),
                                                        IdentifierArgument.getId(ctx, "id").toString(), 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 99))
                                                        .executes(ctx -> give(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "target"),
                                                                IdentifierArgument.getId(ctx, "id").toString(),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))));
    }

    private static int help(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal(
                "Foundry: /foundry status, list, validate, reload, pack status, pack build, rename <old> <new>,"
                        + " give <player> <id> [count]"), false);
        return 1;
    }

    private static int status(CommandSourceStack src) {
        ContentSet set = FoundryMod.loaded();
        long errors = set.problems().stream().filter(Problem::blocking).count();
        src.sendSuccess(() -> Component.literal("Foundry: " + FoundryItems.all().size() + " items registered, "
                + set.recipes().size() + " recipes, "
                + errors + " errors, " + (set.problems().size() - errors) + " warnings at last load."), false);
        return 1;
    }

    /** {@code /foundry list}: every registered item id, one per line, ready to use with {@code give}. */
    private static int list(CommandSourceStack src) {
        var ids = FoundryItems.all().keySet().stream().map(Object::toString).sorted().toList();
        if (ids.isEmpty()) {
            src.sendSuccess(() -> Component.literal("Foundry: no items are registered. Add item files under config/foundry/content and restart."), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Foundry items (" + ids.size() + "):"), false);
        for (String id : ids) src.sendSuccess(() -> Component.literal("  " + id), false);
        src.sendSuccess(() -> Component.literal("Get one with /foundry give <player> <id>, or /give <player> <id>."), false);
        return ids.size();
    }

    /** Re-reads the files and reports. New or changed items still need a restart to take effect. */
    private static int validate(CommandSourceStack src) {
        ContentSet set = FoundryMod.loadContent();
        if (set.problems().isEmpty()) {
            src.sendSuccess(() -> Component.literal("Foundry: content is valid (" + set.items().size() + " items)."), false);
            return 1;
        }
        for (Problem p : set.problems()) src.sendSuccess(() -> Component.literal(p.toString()), false);
        long registered = set.items().keySet().stream().filter(i -> FoundryItems.get(i) == null).count();
        if (registered > 0) {
            src.sendSuccess(() -> Component.literal("Foundry: " + registered
                    + " item(s) are new since startup. Restart the server to register them."), false);
        }
        return set.hasErrors() ? 0 : 1;
    }

    private static int reload(CommandSourceStack src) {
        FoundryMod.ReloadReport r = FoundryMod.reload(src.getServer());
        if (!r.swapped()) {
            src.sendFailure(Component.literal("Foundry: reload refused, " + r.errors()
                    + " error(s). The previous content is still live. Run /foundry validate for details."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Foundry: reloaded. Mechanics updated: " + r.updated()
                + (r.restartNeeded().isEmpty() ? "" : ". Restart needed for: " + r.restartNeeded())), false);
        return 1;
    }

    private static int packStatus(CommandSourceStack src) {
        var path = eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.getMainPath();
        if (path == null || !java.nio.file.Files.isRegularFile(path)) {
            src.sendFailure(Component.literal("Foundry: no resource pack has been built yet. It is built at server start."));
            return 0;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            String sha1 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes));
            src.sendSuccess(() -> Component.literal("Foundry pack: " + bytes.length + " bytes, sha1 " + sha1
                    + ", required=" + eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.isRequired()), false);
            long last = PackBuilds.lastSuccessMs();
            src.sendSuccess(() -> Component.literal("Foundry pack: "
                    + (PackBuilds.isBuilding() ? "a build is running; " : "")
                    + (last == 0 ? "no runtime build yet" : "last build " + java.time.Instant.ofEpochMilli(last))
                    + (PackBuilds.lastFailure() == null ? "" : ", last failure: " + PackBuilds.lastFailure())
                    + (PackBuilds.inputsChanged() ? ", inputs changed since last build" : ", inputs unchanged")), false);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Foundry: could not read the pack: " + e.getMessage()));
            return 0;
        }
    }

    /** Rebuilds on a worker thread; a failure keeps the previous zip. Online players fetch it on rejoin. */
    private static int packBuild(CommandSourceStack src) {
        PackBuilds.build(src.getServer(), msg -> src.sendSuccess(() -> Component.literal(msg), false));
        return 1;
    }

    /**
     * {@code /foundry rename <old> <new>}: rewrites the definition file and every quoted reference, with backups,
     * then reloads. Renaming a registered item still needs a restart; its registry id cannot change at runtime.
     */
    private static int rename(CommandSourceStack src, String oldId, String newId) {
        Ident oldIdent = Ident.parse(oldId);
        Ident newIdent = Ident.parse(newId);
        if (oldIdent == null || newIdent == null) {
            src.sendFailure(Component.literal("Foundry: ids look like namespace:name, lowercase."));
            return 0;
        }
        java.nio.file.Path root = FoundryMod.contentRoot();
        Map<String, String> files;
        try {
            files = ContentFiles.readAll(root);
        } catch (java.io.IOException e) {
            src.sendFailure(Component.literal("Foundry: could not read the content folder: " + e.getMessage()));
            return 0;
        }
        ContentSet set = FoundryMod.loaded();
        java.util.Set<Ident> taken = new java.util.HashSet<>(set.items().keySet());
        taken.addAll(set.recipes().keySet());
        foundry.core.Rename.Result result = foundry.core.Rename.plan(oldIdent, newIdent, taken, files,
                rel -> ContentFiles.exists(root, rel));
        if (result.refused()) {
            for (String why : result.refusals()) src.sendFailure(Component.literal("Foundry: " + why));
            return 0;
        }
        try {
            String summary = ContentFiles.apply(root, result.plan());
            src.sendSuccess(() -> Component.literal("Foundry: renamed " + oldId + " to " + newId + ": " + summary), true);
        } catch (java.io.IOException e) {
            src.sendFailure(Component.literal("Foundry: rename failed mid-write: " + e.getMessage()
                    + ". Backups are next to the files as *.bak-*."));
            return 0;
        }
        FoundryMod.ReloadReport r = FoundryMod.reload(src.getServer());
        src.sendSuccess(() -> Component.literal(r.swapped()
                ? "Foundry: reloaded. Restart needed for: " + r.restartNeeded()
                : "Foundry: reload refused after rename; check /foundry validate."), false);
        return 1;
    }

    private static int give(CommandSourceStack src, ServerPlayer target, String id, int count) {
        Ident ident = Ident.parse(id);
        Item item = ident == null ? null : FoundryItems.get(ident);
        if (item == null) {
            src.sendFailure(Component.literal("Foundry: no registered item " + id
                    + ". Run /foundry validate; new items need a restart."));
            return 0;
        }
        target.getInventory().placeItemBackInInventory(new ItemStack(item, count), net.minecraft.util.Prediction.SERVER_ONLY);
        src.sendSuccess(() -> Component.literal("Gave " + count + " " + id + " to " + target.getName().getString()), true);
        return 1;
    }
}
