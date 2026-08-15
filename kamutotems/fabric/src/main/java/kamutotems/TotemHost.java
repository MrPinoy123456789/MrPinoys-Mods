package kamutotems;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import kamutotems.core.Construct;
import kamutotems.core.HostType;
import kamutotems.core.Kamuy;
import kamutotems.core.KamuyName;
import kamutotems.core.Slot;

/**
 * Entry point for the totem host. Owns per-player {@link Kamuy} persistence,
 * the three totem commands, and the lifecycle wiring the master calls.
 */
public final class TotemHost {

    private static final String UNNAMED = "an unnamed spirit";

    private TotemHost() {}

    public static void register() {
        TotemCharges.register();
        DamageFunnel.register();
        EchoQueue.register();
        AuraHost.register();

        ServerLifecycleEvents.SERVER_STARTED.register(KamuyStore::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> KamuyStore.flushAll());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            KamuyStore.flushDirty();
            TotemCharges.tick(server);
        });

        // First join / re-issue. spiritwolves/Tracker uses this exact shape.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                onJoin(handler.player));

        // Right-click air with a totem opens the discovery book.
        UseItemCallback.EVENT.register(TotemHost::onUseItem);

        // Right-click a block with a totem is the Scan verb.
        UseBlockCallback.EVENT.register(TotemHost::onUseBlock);

        // Commands are wired by KamuTotemsMod, NOT here. Registering them in
        // both places pushed every /totem, /kamuy and /kamu node through
        // Brigadier twice; duplicate roots merge silently rather than erroring,
        // so it looked fine and quietly did the work twice.
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("totem")
                .executes(ctx -> openPanel(ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("name")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> renameKamuy(
                                        ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "text"))))));

        dispatcher.register(Commands.literal("kamuy")
                .executes(ctx -> printJournal(ctx.getSource().getPlayerOrException())));

        // The discovery book. It used to be reachable only by right-clicking
        // air with the totem; that gesture now opens the slot panel, so the
        // book needs a home of its own (SPEC section 9, section 11).
        dispatcher.register(Commands.literal("kamu")
                .then(Commands.literal("book")
                        .executes(ctx -> {
                            BookMenu.open(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("forge")
                        .executes(ctx -> {
                            KamuForge.open(ctx.getSource().getPlayerOrException());
                            return 1;
                        })));
    }

    private static int openPanel(ServerPlayer player) {
        Kamuy kamuy = KamuyStore.getOrCreate(player);
        if (Totem.find(player) == null) {
            Totem.giveOrDrop(player, Totem.create(kamuy, Totem.maxCharges()));
        } else {
            Totem.updatePlayerTotem(player, kamuy, Totem.chargesRemaining(Totem.find(player)));
        }
        SlotMenu.open(player);
        return 1;
    }

    private static int renameKamuy(ServerPlayer player, String raw) {
        String sanitised = KamuyName.sanitise(raw, 32);
        if (sanitised == null) {
            player.sendSystemMessage(Component.literal(
                            "That name cannot be used. Try /totem name <text> with fewer than 32 characters.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        Kamuy kamuy = KamuyStore.getOrCreate(player);
        String born = kamuy.bornDateKey() != null && !kamuy.bornDateKey().isBlank()
                ? kamuy.bornDateKey()
                : LocalDate.now(ZoneOffset.UTC).toString();
        Kamuy next = new Kamuy(sanitised, kamuy.construct(), born,
                kamuy.kamuDrunk(), kamuy.saves(), kamuy.bossesSlain());
        KamuyStore.put(player.getUUID(), next);

        ItemStack totem = Totem.find(player);
        if (totem != null) {
            Totem.refreshLore(totem, next);
        }
        player.sendSystemMessage(Component.literal(
                        "Your Kamuy is now " + sanitised + ".")
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int printJournal(ServerPlayer player) {
        Kamuy kamuy = KamuyStore.getOrCreate(player);
        String name = kamuy.name() != null ? kamuy.name() : UNNAMED;
        player.sendSystemMessage(Component.literal(name).withStyle(ChatFormatting.GOLD));
        for (String line : kamuy.journal()) {
            player.sendSystemMessage(Component.literal(line)
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        }
        return 1;
    }

    private static void onJoin(ServerPlayer player) {
        Kamuy kamuy = KamuyStore.getOrCreate(player);
        if (Totem.find(player) == null) {
            Totem.giveOrDrop(player, Totem.create(kamuy, Totem.maxCharges()));
        } else {
            Totem.updatePlayerTotem(player, kamuy, Totem.chargesRemaining(Totem.find(player)));
        }
    }

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack held = serverPlayer.getItemInHand(hand);

        // Holding a loose KAMU: bind it into the Kamuy's pool. The item is
        // consumed and becomes a pooled entry, which is then assigned to a slot
        // from the station. Three explicit states -- item, pooled, slotted --
        // and one deliberate action between each.
        if (Totem.isKamu(held)) {
            Kamuy kamuy = KamuyStore.getOrCreate(serverPlayer);
            Slot bound = new Slot(Totem.kamuId(held), Totem.kamuTier(held));
            KamuyStore.put(serverPlayer.getUUID(), kamuy.addToPool(bound));
            held.shrink(1);

            Chime.play(serverPlayer, SoundEvents.NOTE_BLOCK_CHIME, 0.2f, 1.2f);
            serverPlayer.sendSystemMessage(Component.literal(
                            "Bound. Open your totem to place it.")
                    .withStyle(ChatFormatting.AQUA));
            return InteractionResult.CONSUME;
        }

        // The totem itself no longer opens a panel from the hand. All carving,
        // fusion, naming and the rest live at the Kamu Station (fletching table).
        return InteractionResult.PASS;
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
                                                BlockHitResult hitResult) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Scanning is deliberately sneak-gated. Without it, a totem worn in the
        // offhand would fire on every plain right-click of pass-through blocks
        // (dirt, stone, grass...), which is most blocks most of the time.
        if (!serverPlayer.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        ItemStack held = serverPlayer.getItemInHand(hand);

        if (!Totem.is(held)) {
            return InteractionResult.PASS;
        }
        // The Scan verb (SPEC section 6.3): the totem is the quest host's
        // scanner, which is why "scan" needed no new interaction vocabulary.
        // This is the one call that crosses a host boundary, and it is
        // deliberately one-way -- the totem host tells the quest host what
        // happened and does not care what it does about it.
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(hitResult.getBlockPos()).getBlock());
        // The POSITION is passed too: a scan is identified by where it is, not
        // just what it is, so the same block cannot be farmed.
        QuestHost.onScan(serverPlayer, blockId, hitResult.getBlockPos());

        serverPlayer.sendSystemMessage(Component.literal(
                        "The totem has scanned this place.")
                .withStyle(ChatFormatting.GRAY));
        return InteractionResult.CONSUME;
    }

    /** Per-player Kamuy registry and persistence. Patterned on spiritwolves/PlayerWolfRegistry. */
    public static final class KamuyStore {

        private static final Map<UUID, Kamuy> records = new HashMap<>();
        private static final Set<UUID> dirty = new HashSet<>();
        private static Path directory;

        private KamuyStore() {}

        static void load(MinecraftServer server) {
            records.clear();
            dirty.clear();
            directory = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("kamutotems");

            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                TotemHost.warn("Could not create Kamuy registry directory");
                return;
            }

            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    String fileName = file.getFileName().toString();
                    if (!fileName.endsWith(".dat")) {
                        continue;
                    }
                    String uuidPart = fileName.substring(0, fileName.length() - ".dat".length());
                    loadOne(file, uuidPart);
                }
            } catch (IOException e) {
                TotemHost.warn("Could not list Kamuy registry directory");
            }
        }

        private static void loadOne(Path file, String uuidPart) {
            UUID playerUuid;
            try {
                playerUuid = UUID.fromString(uuidPart);
            } catch (IllegalArgumentException e) {
                quarantine(file, "not a UUID filename");
                return;
            }

            try {
                CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                Kamuy kamuy = fromTag(tag);
                records.put(playerUuid, kamuy);
            } catch (Exception e) {
                TotemHost.warn("Corrupt Kamuy record for " + uuidPart + ", quarantining");
                quarantine(file, "failed to parse");
            }
        }

        private static void quarantine(Path file, String reason) {
            Path corrupt = file.resolveSibling(file.getFileName() + ".corrupt");
            try {
                Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                TotemHost.warn("Could not quarantine bad Kamuy record " + file + " (" + reason + ")");
            }
        }

        public static Kamuy getOrCreate(ServerPlayer player) {
            UUID id = player.getUUID();
            Kamuy existing = records.get(id);
            if (existing != null) {
                return existing;
            }

            // A missing record with a real totem rebuilds an unnamed Kamuy from the item.
            ItemStack totem = Totem.find(player);
            List<Slot> slots;
            if (totem != null) {
                slots = Totem.readSlots(totem);
            } else {
                slots = Construct.empty(HostType.TOTEM).slots();
            }
            Construct construct = new Construct(HostType.TOTEM, slots);
            Kamuy kamuy = new Kamuy(null, construct, "", 0, 0, 0);
            records.put(id, kamuy);
            markDirty(id);
            return kamuy;
        }

        public static void put(UUID player, Kamuy kamuy) {
            records.put(player, kamuy);
            markDirty(player);
        }

        public static void advanceSaves(ServerPlayer player) {
            Kamuy k = getOrCreate(player);
            Kamuy next = new Kamuy(k.name(), k.construct(), k.bornDateKey(),
                    k.kamuDrunk(), k.saves() + 1, k.bossesSlain());
            put(player.getUUID(), next);
        }

        static void markDirty(UUID player) {
            dirty.add(player);
        }

        static void flushDirty() {
            if (dirty.isEmpty() || directory == null) {
                return;
            }
            Set<UUID> toFlush = new HashSet<>(dirty);
            dirty.clear();
            for (UUID player : toFlush) {
                Kamuy record = records.get(player);
                if (record != null) {
                    writeOne(player, record);
                }
            }
        }

        static void flushAll() {
            flushDirty();
        }

        private static void writeOne(UUID player, Kamuy record) {
            if (directory == null) {
                return;
            }
            Path target = directory.resolve(player + ".dat");
            Path tmp = directory.resolve(player + ".dat.tmp");
            try {
                NbtIo.writeCompressed(toTag(record), tmp);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                TotemHost.warn("Could not save Kamuy record for " + player);
            }
        }

        private static CompoundTag toTag(Kamuy k) {
            CompoundTag tag = new CompoundTag();
            tag.putString("name", k.name() != null ? k.name() : "");
            tag.putString("born", k.bornDateKey() != null ? k.bornDateKey() : "");
            tag.putInt("kamuDrunk", k.kamuDrunk());
            tag.putInt("saves", k.saves());
            tag.putInt("bossesSlain", k.bossesSlain());

            ListTag slots = new ListTag();
            for (Slot slot : k.construct().slots()) {
                CompoundTag entry = new CompoundTag();
                if (slot == null) {
                    entry.putString("id", "");
                    entry.putInt("tier", 0);
                } else {
                    entry.putString("id", slot.kamuId());
                    entry.putInt("tier", slot.tier());
                }
                slots.add(entry);
            }
            tag.put("slots", slots);
            return tag;
        }

        private static Kamuy fromTag(CompoundTag tag) {
            String name = tag.getStringOr("name", "");
            if (name.isBlank()) {
                name = null;
            }
            String born = tag.getStringOr("born", "");
            if (born.isBlank()) {
                born = null;
            }
            int kamuDrunk = tag.getIntOr("kamuDrunk", 0);
            int saves = tag.getIntOr("saves", 0);
            int bossesSlain = tag.getIntOr("bossesSlain", 0);

            List<Slot> slots = new ArrayList<>();
            ListTag list = tag.getListOrEmpty("slots");
            for (int i = 0; i < Construct.SLOT_COUNT; i++) {
                if (i < list.size()) {
                    CompoundTag entry = list.getCompoundOrEmpty(i);
                    String id = entry.getStringOr("id", "");
                    if (id.isBlank()) {
                        slots.add(null);
                    } else {
                        slots.add(new Slot(id, Math.max(1, Math.min(3, entry.getIntOr("tier", 1)))));
                    }
                } else {
                    slots.add(null);
                }
            }
            while (slots.size() < Construct.SLOT_COUNT) {
                slots.add(null);
            }

            Construct construct = new Construct(HostType.TOTEM, slots);
            return new Kamuy(name, construct, born, kamuDrunk, saves, bossesSlain);
        }
    }

    private static void warn(String message) {
        KamuTotemsMod.LOG.warn(message);
    }
}
