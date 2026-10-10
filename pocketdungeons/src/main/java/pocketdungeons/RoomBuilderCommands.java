package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /dungeon roombuilder}: an operator-only command subtree for creating,
 * editing, saving, loading and deleting room templates from inside the game.
 *
 * <p>Replaces the older {@code /dungeon admin buildroom} and
 * {@code /dungeon admin saveroom} pair with a fuller cycle:
 * <ul>
 *   <li>{@code new}: open a fresh empty build shell</li>
 *   <li>{@code load <name>}: stamp an existing room template into a build shell for editing</li>
 *   <li>{@code save <name>}: capture the build shell, overwriting the named template and its metadata</li>
 *   <li>{@code delete <name>}: remove a room's template and metadata, then reload the manifest</li>
 *   <li>{@code rooms}: open a paginated chest GUI listing every loaded room, with click-to-load</li>
 * </ul>
 *
 * <p>One build room per player, same as the old admin commands. Opening a new
 * or loaded build room tears down any existing one first.
 */
final class RoomBuilderCommands {

    private RoomBuilderCommands() {}

    /** Slots per page in the rooms GUI (5 rows of 9, minus borders). */
    private static final int ROOMS_PER_PAGE = 45;

    /** Registers the {@code /dungeon roombuilder} subtree. Operator-only. */
    static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal("roombuilder")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                .then(Commands.literal("new")
                        .executes(ctx -> newBuildRoom(ctx.getSource())))

                .then(Commands.literal("load")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> loadRoom(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))

                .then(Commands.literal("save")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> saveRoom(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))

                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> deleteRoom(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))

                .then(Commands.literal("versions")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> listVersions(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))

                .then(Commands.literal("rooms")
                        .executes(ctx -> roomsList(ctx.getSource())))

                .then(Commands.literal("kit")
                        .executes(ctx -> restoreKit(ctx.getSource())))

                .then(Commands.literal("validate")
                        .executes(ctx -> validate(ctx.getSource())))

                .then(Commands.literal("exit")
                        .executes(ctx -> exitBuildRoom(ctx.getSource())))

                .then(Commands.literal("clear")
                        .executes(ctx -> clearBuildRoom(ctx.getSource())))

                .then(Commands.literal("undo")
                        .executes(ctx -> undo(ctx.getSource())))

                .then(Commands.literal("redo")
                        .executes(ctx -> redo(ctx.getSource())));
    }

    // ---- new ---------------------------------------------------------------

    /**
     * Opens a fresh empty build shell, the same as the old
     * {@code /dungeon admin buildroom}.
     */
    private static int newBuildRoom(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        int slot = Instances.adminBuildRoom(source.getServer(), player);
        if (slot == -2) {
            source.sendFailure(Component.literal(
                    "You are inside a live instance. Leave it before opening a build room."));
            return 0;
        }
        if (slot < 0) {
            source.sendFailure(Component.literal(
                    "Could not open a build room: the dungeon dimension is missing or stamping failed."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Build room at " + InstanceRegistry.slotOrigin(slot).toShortString()
                        + ". Build freely; save it with /dungeon roombuilder save <name>."), false);
        return 1;
    }

    // ---- load --------------------------------------------------------------

    /**
     * Stamps an existing room template into a build shell so the operator can
     * edit it in-world. The template is looked up by room name from the loaded
     * manifest, placed at rotation 0 into a fresh build cell.
     */
    private static int loadRoom(CommandSourceStack source, String name)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();

        RoomManifest manifest = RoomManifest.current();
        RoomManifest.Entry entry = manifest.byName(name);
        if (entry == null) {
            source.sendFailure(Component.literal(
                    "No room named '" + name + "' is loaded. Check /dungeon admin manifest list."));
            return 0;
        }

        // Open a build shell first (same path as newBuildRoom).
        int slot = Instances.adminBuildRoom(server, player);
        if (slot == -2) {
            source.sendFailure(Component.literal(
                    "You are inside a live instance. Leave it before opening a build room."));
            return 0;
        }
        if (slot < 0) {
            source.sendFailure(Component.literal(
                    "Could not open a build room: the dungeon dimension is missing or stamping failed."));
            return 0;
        }

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            source.sendFailure(Component.literal("The dungeon dimension is not loaded."));
            return 0;
        }

        BlockPos origin = InstanceRegistry.slotOrigin(slot);
        StructureTemplateManager manager = level.getStructureManager();
        Identifier templateId = Identifier.parse(entry.meta.template);

        // Place the template at rotation 0, no processors, no jigsaw replacement.
        // The build shell already has the floor/walls/ceiling; the template
        // overwrites the interior with the room's authored content.
        StructureTemplate template = manager.get(templateId).orElse(null);
        if (template == null) {
            source.sendFailure(Component.literal(
                    "Template '" + templateId + "' is missing from the structure manager."));
            return 0;
        }

        Vec3i size = template.getSize();
        int storyOffset = size.getY() - (RoomGeometry.CEILING_Y + 1);
        BlockPos stampOrigin = storyOffset == 0 ? origin : origin.below(storyOffset);

        StructurePlaceSettings settings = new StructurePlaceSettings();
        settings.setRotation(net.minecraft.world.level.block.Rotation.NONE);
        settings.setRotationPivot(BlockPos.ZERO);
        settings.setIgnoreEntities(false);
        template.placeInWorld(level, stampOrigin, stampOrigin, settings,
                level.getRandom(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);

        // Re-centre the player in the cell.
        Instances.teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL,
                net.minecraft.world.phys.Vec3.atBottomCenterOf(RoomBuilder.cellCentre(origin, 0, 0)),
                0.0f, 0.0f);

        source.sendSuccess(() -> Component.literal(
                "Loaded room '" + name + "' into the build shell. Edit it, then save with "
                        + "/dungeon roombuilder save <name>."), false);
        return 1;
    }

    // ---- save --------------------------------------------------------------

    /**
     * Captures the player's build room and saves it as the named template,
     * overwriting any existing file with the same name. Also writes or
     * updates the dungeon_room JSON metadata so the room is immediately
     * available after a manifest reload.
     */
    private static int saveRoom(CommandSourceStack source, String name)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();

        // Find the player's build room.
        InstanceRecord record = findBuildRoom(server, player);
        if (record == null) {
            source.sendFailure(Component.literal(
                    "No build room is open for you. Open one with /dungeon roombuilder new."));
            return 0;
        }

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            source.sendFailure(Component.literal("The dungeon dimension is not loaded."));
            return 0;
        }

        // Validate before saving. Fatal errors block the save so an
        // unreachable or empty room cannot be written to disk.
        if (!RoomValidator.validateAndReport(player)) {
            source.sendFailure(Component.literal(
                    "Save blocked: validation found errors. Fix them and try again."));
            return 0;
        }

        String safeName = sanitizeName(name);
        Path templateDir = RoomTemplateGenerator.templateOutDir();
        if (templateDir == null) {
            source.sendFailure(Component.literal("Could not resolve the template output directory."));
            return 0;
        }

        // Back up the previous version before overwriting, if one exists.
        Path templateFile = templateDir.resolve(safeName + ".nbt");
        Path versionDir = templateDir.resolve("versions");
        try {
            if (Files.exists(templateFile)) {
                Files.createDirectories(versionDir);
                String stamp = java.time.Instant.now()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .toString().replace(":", "-");
                Path backup = versionDir.resolve(safeName + "_" + stamp + ".nbt");
                Files.copy(templateFile, backup);
                PocketDungeonsMod.LOG.info("Backed up previous version of '{}' to {}", safeName, backup);
            }
        } catch (IOException e) {
            PocketDungeonsMod.LOG.warn("Could not back up previous version of '{}'", safeName, e);
        }

        // Capture the build cell to the template file, overwriting any existing.
        try {
            StructureTemplate template = new StructureTemplate();
            Vec3i size = new Vec3i(RoomGeometry.CELL, RoomGeometry.CEILING_Y + 1, RoomGeometry.CELL);
            Lemon.withoutLemon(level, new net.minecraft.world.phys.AABB(record.origin.getX(), record.origin.getY(),
                            record.origin.getZ(), record.origin.getX() + size.getX(),
                            record.origin.getY() + size.getY(), record.origin.getZ() + size.getZ()),
                    () -> template.fillFromWorld(level, record.origin, size, true, List.of()));
            CompoundTag nbt = template.save(new CompoundTag());
            nbt.putString("pd_author", player.getUUID().toString());
            nbt.putLong("pd_saved_at", System.currentTimeMillis());
            NbtIo.writeCompressed(nbt, templateFile);
            PocketDungeonsMod.LOG.info("Saved room template to {}", templateFile);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not save room template '{}'", safeName, e);
            source.sendFailure(Component.literal("Could not save the template: " + e.getMessage()));
            return 0;
        }

        // Write or update the dungeon_room JSON metadata.
        Path metaDir = metaOutDir();
        if (metaDir != null) {
            Path metaFile = metaDir.resolve(safeName + ".json");
            // Back up previous JSON if it exists.
            Path metaVersionDir = metaDir.resolve("versions");
            try {
                if (Files.exists(metaFile)) {
                    Files.createDirectories(metaVersionDir);
                    String stamp = java.time.Instant.now()
                            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                            .toString().replace(":", "-");
                    Path metaBackup = metaVersionDir.resolve(safeName + "_" + stamp + ".json");
                    Files.copy(metaFile, metaBackup);
                }
            } catch (IOException e) {
                PocketDungeonsMod.LOG.warn("Could not back up previous metadata for '{}'", safeName, e);
            }
            try {
                JsonObject json = buildRoomJson(safeName, player.getName().getString());
                Files.writeString(metaFile, json.toString());
            } catch (IOException e) {
                PocketDungeonsMod.LOG.error("Could not save room metadata '{}'", safeName, e);
                source.sendFailure(Component.literal(
                        "Template saved, but metadata write failed: " + e.getMessage()));
            }
        }

        // Reload the manifest so the new room is immediately available.
        RoomManifest.load(server);

        // Tear down the build room and send the player home.
        RunLifecycle.dropMember(server, record, player.getUUID(), player, "build room saved");
        RoomEditorKit.removeKit(player);
        RoomEditorMetadata.clear(player.getUUID());
        RoomEditorHistory.clear(player.getUUID());
        Instances.sendToWorldSpawn(server, player);
        InstanceTeardown.purge(server, record, "build room saved");

        source.sendSuccess(() -> Component.literal(
                "Saved room '" + safeName + "'. Previous versions are in the versions/ subdirectory; "
                        + "check /dungeon roombuilder versions " + safeName + "."), false);
        return 1;
    }

    // ---- delete ------------------------------------------------------------

    /**
     * Deletes a room's template and metadata files, then reloads the manifest.
     * The room must be loaded in the manifest (so the operator knows the name
     * is real), and the files are removed from both the dev and production
     * output directories.
     */
    private static int deleteRoom(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        String safeName = sanitizeName(name);

        RoomManifest manifest = RoomManifest.current();
        RoomManifest.Entry entry = manifest.byName(name);
        if (entry == null) {
            source.sendFailure(Component.literal(
                    "No room named '" + name + "' is loaded. Check /dungeon admin manifest list."));
            return 0;
        }

        Path templateDir = RoomTemplateGenerator.templateOutDir();
        Path metaDir = metaOutDir();
        boolean deletedAny = false;

        if (templateDir != null) {
            Path templateFile = templateDir.resolve(safeName + ".nbt");
            try {
                if (Files.deleteIfExists(templateFile)) {
                    deletedAny = true;
                }
            } catch (IOException e) {
                PocketDungeonsMod.LOG.error("Could not delete template '{}'", safeName, e);
                source.sendFailure(Component.literal(
                        "Could not delete template file: " + e.getMessage()));
                return 0;
            }
        }

        if (metaDir != null) {
            Path metaFile = metaDir.resolve(safeName + ".json");
            try {
                if (Files.deleteIfExists(metaFile)) {
                    deletedAny = true;
                }
            } catch (IOException e) {
                PocketDungeonsMod.LOG.error("Could not delete metadata '{}'", safeName, e);
                source.sendFailure(Component.literal(
                        "Could not delete metadata file: " + e.getMessage()));
                return 0;
            }
        }

        if (!deletedAny) {
            source.sendFailure(Component.literal(
                    "Room '" + name + "' is loaded but no files were found on disk to delete."));
            return 0;
        }

        // Reload the manifest so the room is gone from the index.
        RoomManifest.load(server);

        source.sendSuccess(() -> Component.literal(
                "Deleted room '" + name + "' and reloaded the manifest."), false);
        return 1;
    }

    // ---- rooms GUI ---------------------------------------------------------

    /**
     * Opens a paginated chest GUI listing every loaded room. Left-click a room
     * to load it into a build shell. The GUI is 6 rows (54 slots): 45 room
     * slots per page, with prev/next/close buttons in the bottom row.
     */
    private static int roomsList(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RoomManifest manifest = RoomManifest.current();
        List<RoomManifest.Entry> rooms = manifest.rooms();

        if (rooms.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No rooms loaded. Run /dungeon admin manifest reload."));
            return 0;
        }

        openRoomsPage(player, rooms, 0);
        return 1;
    }

    /**
     * Opens one page of the rooms GUI. Page 0 is the first 45 rooms, page 1
     * the next 45, and so on. The bottom row has prev, close, and next buttons.
     */
    private static void openRoomsPage(ServerPlayer player, List<RoomManifest.Entry> rooms,
                                       int page) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal("Room Builder - Rooms (" + (page + 1) + "/"
                + ((rooms.size() + ROOMS_PER_PAGE - 1) / ROOMS_PER_PAGE) + ")"));

        int start = page * ROOMS_PER_PAGE;
        int end = Math.min(start + ROOMS_PER_PAGE, rooms.size());

        for (int i = start; i < end; i++) {
            RoomManifest.Entry entry = rooms.get(i);
            int slot = i - start;
            gui.setSlot(slot, roomElement(entry, player, rooms, page));
        }

        // Fill remaining room slots with empty panes.
        for (int slot = end - start; slot < ROOMS_PER_PAGE; slot++) {
            gui.setSlot(slot, new GuiElementBuilder(Items.GLASS_PANE)
                    .setName(Component.literal("")));
        }

        // Bottom row (slots 45-53): prev, spacer, close, spacer, next.
        for (int i = 45; i < 54; i++) {
            gui.setSlot(i, new GuiElementBuilder(Items.GLASS_PANE)
                    .setName(Component.literal("")));
        }

        int totalPages = (rooms.size() + ROOMS_PER_PAGE - 1) / ROOMS_PER_PAGE;

        if (page > 0) {
            gui.setSlot(45, new GuiElementBuilder(Items.ARROW)
                    .setName(Component.literal("Previous page")
                            .withStyle(ChatFormatting.WHITE))
                    .setCallback((index, clickType, actionType, g) -> {
                        g.close();
                        openRoomsPage(player, rooms, page - 1);
                    }));
        }

        gui.setSlot(49, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setCallback((index, clickType, actionType, g) -> g.close()));

        if (page < totalPages - 1) {
            gui.setSlot(53, new GuiElementBuilder(Items.ARROW)
                    .setName(Component.literal("Next page")
                            .withStyle(ChatFormatting.WHITE))
                    .setCallback((index, clickType, actionType, g) -> {
                        g.close();
                        openRoomsPage(player, rooms, page + 1);
                    }));
        }

        gui.open();
    }

    /**
     * One room's chest element: the room name as the title, roles and
     * footprint in the lore, and a click callback that loads the room into
     * a build shell.
     */
    private static GuiElementBuilder roomElement(RoomManifest.Entry entry, ServerPlayer player,
                                                  List<RoomManifest.Entry> rooms, int page) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("Roles: " + String.join(", ", entry.meta.roles))
                .withStyle(ChatFormatting.GRAY)
                .withStyle(s -> s.withItalic(false)));
        lore.add(Component.literal(
                "Footprint: " + entry.meta.footprintX + "x" + entry.meta.footprintZ)
                .withStyle(ChatFormatting.GRAY)
                .withStyle(s -> s.withItalic(false)));
        if (entry.meta.content != null) {
            lore.add(Component.literal("Content: " + entry.meta.content)
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .withStyle(s -> s.withItalic(false)));
        }
        lore.add(Component.literal("Click to load into a build shell.")
                .withStyle(ChatFormatting.GREEN)
                .withStyle(s -> s.withItalic(false)));

        return new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal(entry.name)
                        .withStyle(ChatFormatting.WHITE))
                .setLore(lore)
                .setCallback((index, clickType, actionType, gui) -> {
                    if (!clickType.isLeft) {
                        return;
                    }
                    gui.close();
                    MinecraftServer server = player.level().getServer();
                    if (server == null) {
                        return;
                    }
                    int slot = Instances.adminBuildRoom(server, player);
                    if (slot < 0) {
                        player.sendSystemMessage(Component.literal(
                                "Could not open a build room.")
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
                    if (level == null) {
                        player.sendSystemMessage(Component.literal(
                                "The dungeon dimension is not loaded.")
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    BlockPos origin = InstanceRegistry.slotOrigin(slot);
                    StructureTemplateManager manager = level.getStructureManager();
                    Identifier templateId = Identifier.parse(entry.meta.template);
                    StructureTemplate template = manager.get(templateId).orElse(null);
                    if (template == null) {
                        player.sendSystemMessage(Component.literal(
                                "Template '" + templateId + "' is missing.")
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    Vec3i size = template.getSize();
                    int storyOffset = size.getY() - (RoomGeometry.CEILING_Y + 1);
                    BlockPos stampOrigin = storyOffset == 0 ? origin : origin.below(storyOffset);
                    StructurePlaceSettings settings = new StructurePlaceSettings();
                    settings.setRotation(net.minecraft.world.level.block.Rotation.NONE);
                    settings.setRotationPivot(BlockPos.ZERO);
                    settings.setIgnoreEntities(false);
                    template.placeInWorld(level, stampOrigin, stampOrigin, settings,
                            level.getRandom(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                    Instances.teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL,
                            net.minecraft.world.phys.Vec3.atBottomCenterOf(
                                    RoomBuilder.cellCentre(origin, 0, 0)),
                            0.0f, 0.0f);
                    player.sendSystemMessage(Component.literal(
                            "Loaded room '" + entry.name + "' into the build shell. "
                                    + "Save with /dungeon roombuilder save <name>.")
                            .withStyle(ChatFormatting.AQUA));
                });
    }

    // ---- kit ----------------------------------------------------------------

    /**
     * Restores the Room Editor Kit item if the operator lost it. Only works
     * inside a build room. The kit is normally issued automatically on entry;
     * this is the recovery path.
     */
    private static int restoreKit(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        InstanceRecord record = findBuildRoom(source.getServer(), player);
        if (record == null) {
            source.sendFailure(Component.literal(
                    "You are not in a build room. Open one with /dungeon roombuilder new."));
            return 0;
        }
        RoomEditorKit.issueKit(player);
        source.sendSuccess(() -> Component.literal(
                "Room Editor Kit restored. Right-click it to open the editor GUI."), false);
        return 1;
    }

    // ---- validate ----------------------------------------------------------

    /**
     * Runs validation on the current build room and reports in chat.
     */
    private static int validate(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RoomValidator.validateAndReport(player);
        return 1;
    }

    // ---- exit ---------------------------------------------------------------

    /**
     * Exits the build room without saving. Prompts to save as unfinished or
     * discard. For now, just discards and tears down.
     */
    private static int exitBuildRoom(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();
        InstanceRecord record = findBuildRoom(server, player);
        if (record == null) {
            source.sendFailure(Component.literal(
                    "No build room is open for you."));
            return 0;
        }
        RoomEditorKit.removeKit(player);
        RoomEditorMetadata.clear(player.getUUID());
        RoomEditorHistory.clear(player.getUUID());
        RunLifecycle.dropMember(server, record, player.getUUID(), player, "build room exit");
        Instances.sendToWorldSpawn(server, player);
        InstanceTeardown.purge(server, record, "build room exit");
        source.sendSuccess(() -> Component.literal(
                "Exited the build room without saving."), false);
        return 1;
    }

    // ---- clear --------------------------------------------------------------

    /**
     * Clears the build room interior to a fresh empty shell.
     */
    private static int clearBuildRoom(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();
        InstanceRecord record = findBuildRoom(server, player);
        if (record == null) {
            source.sendFailure(Component.literal(
                    "No build room is open for you."));
            return 0;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            source.sendFailure(Component.literal("The dungeon dimension is not loaded."));
            return 0;
        }
        RoomBuilder.buildShell(level, record.origin, RoomBuilder.FLOOR);
        source.sendSuccess(() -> Component.literal(
                "Build room cleared to a fresh empty shell."), false);
        return 1;
    }

    // ---- undo / redo --------------------------------------------------------

    /**
     * Undoes the last block change in the build room.
     */
    private static int undo(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (RoomEditorHistory.undo(player)) {
            source.sendSuccess(() -> Component.literal("Undone."), false);
        } else {
            source.sendFailure(Component.literal("Nothing to undo."));
        }
        return 1;
    }

    /**
     * Redoes the last undone block change in the build room.
     */
    private static int redo(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (RoomEditorHistory.redo(player)) {
            source.sendSuccess(() -> Component.literal("Redone."), false);
        } else {
            source.sendFailure(Component.literal("Nothing to redo."));
        }
        return 1;
    }

    // ---- versions ----------------------------------------------------------

    /**
     * Lists the version history for a room: every backup in the
     * {@code versions/} subdirectory of the template output dir, newest last.
     */
    private static int listVersions(CommandSourceStack source, String name) {
        String safeName = sanitizeName(name);
        Path templateDir = RoomTemplateGenerator.templateOutDir();
        if (templateDir == null) {
            source.sendFailure(Component.literal("Could not resolve the template output directory."));
            return 0;
        }
        Path versionDir = templateDir.resolve("versions");
        if (!Files.isDirectory(versionDir)) {
            source.sendSuccess(() -> Component.literal(
                    "No version history for '" + name + "' (no backups yet)."), false);
            return 0;
        }
        List<String> backups = new ArrayList<>();
        try (var stream = Files.list(versionDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith(safeName + "_")
                            && p.getFileName().toString().endsWith(".nbt"))
                    .forEach(p -> backups.add(p.getFileName().toString()));
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not read version directory: " + e.getMessage()));
            return 0;
        }
        if (backups.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No version history for '" + name + "'."), false);
            return 0;
        }
        java.util.Collections.sort(backups);
        source.sendSuccess(() -> Component.literal(
                "Version history for '" + name + "' (" + backups.size() + " backup"
                        + (backups.size() == 1 ? "" : "s") + "):"), false);
        for (String backup : backups) {
            source.sendSuccess(() -> Component.literal("  " + backup), false);
        }
        source.sendSuccess(() -> Component.literal(
                "To restore: stop the server, copy a backup over the live .nbt, then run "
                        + "/dungeon admin manifest reload."), false);
        return backups.size();
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Finds the player's open build room record, or null if none.
     */
    private static InstanceRecord findBuildRoom(MinecraftServer server, ServerPlayer player) {
        for (InstanceRecord r : InstanceRegistry.bySlot.values()) {
            if (r.adminBuild && player.getUUID().equals(r.owner)) {
                return r;
            }
        }
        return null;
    }

    /**
     * The dungeon_room JSON output directory, parallel to
     * {@link RoomTemplateGenerator#templateOutDir()}. In a dev environment
     * this is the source tree; in production it is the same datapack.
     */
    private static Path metaOutDir() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            Path outDir = FabricLoader.getInstance().getGameDir().getParent().resolve(
                    "src/main/resources/data/" + PocketDungeonsMod.MOD_ID + "/dungeon_room");
            try {
                Files.createDirectories(outDir);
            } catch (IOException e) {
                PocketDungeonsMod.LOG.error("Could not create room metadata directory", e);
                return null;
            }
            return outDir;
        }
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path dir = gameDir.resolve("world/datapacks/pocketdungeons_rooms"
                + "/data/" + PocketDungeonsMod.MOD_ID + "/dungeon_room");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            PocketDungeonsMod.LOG.error("Could not create room metadata directory", e);
            return null;
        }
        return dir;
    }

    /**
     * Builds a minimal dungeon_room JSON for a saved room. The room gets
     * the {@code loot} role by default (the most generic), a 1x1 footprint,
     * and the template id pointing at the saved .nbt. The operator can edit
     * the JSON by hand for more specific roles, content, tier, etc.
     */
    private static JsonObject buildRoomJson(String name, String authorName) {
        JsonObject obj = new JsonObject();
        obj.addProperty("template",
                PocketDungeonsMod.MOD_ID + ":rooms/" + name);
        JsonArray footprint = new JsonArray();
        footprint.add(1);
        footprint.add(1);
        obj.add("footprint", footprint);
        JsonArray roles = new JsonArray();
        roles.add("loot");
        obj.add("roles", roles);
        obj.addProperty("weight", 1);
        obj.addProperty("minDepth", 0);
        obj.addProperty("maxPerDungeon", -1);
        return obj;
    }

    /** {@code [a-z0-9_-]} only, so a command argument can never escape the rooms directory. */
    private static String sanitizeName(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return cleaned.isEmpty() ? "room" : cleaned;
    }
}
