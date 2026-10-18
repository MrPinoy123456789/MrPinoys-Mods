package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The starter tools of Act 0 (decision 2026-10-17): the wooden axe, pickaxe and shovel the bag chest hands out
 * carry a {@code starter_tool} tag naming their kind. The chest hands a replacement set out for free, so a
 * player must never end up holding two of one kind: whenever the sweep finds a second tagged tool of a kind
 * (in the pack, on the cursor, in any open container or in Dungeon Storage) the one with the least durability
 * left is deleted.
 *
 * <p>The sweep is pure over {@link Held} entries so it can be tested without a server; {@link #sweep} gathers
 * them from the live player. It runs once a second for a player in the dungeon dimension who has not finished
 * Act 0, and right after the chest hands a set over.
 */
final class StarterTools {

    /** The custom data key (under {@code pocketdungeons}) naming a starter tool's kind. */
    static final String TAG = "starter_tool";

    private StarterTools() {}

    /** One tagged tool and a way to remove it. */
    record Held(String kind, int remaining, Runnable remove) {}

    /** The kind of {@code stack} if it is a starter tool, else the empty string. */
    static String kindOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return "";
        }
        CompoundTag mine = data.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElse(null);
        return mine == null ? "" : mine.getStringOr(TAG, "");
    }

    /** Durability left on a stack: its maximum minus its damage. */
    static int remaining(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    /**
     * Removes every duplicate: of each kind the entry with the most durability left stays, the rest go.
     *
     * @return how many were removed
     */
    static int dedupe(List<Held> held) {
        Map<String, Held> best = new HashMap<>();
        List<Held> doomed = new ArrayList<>();
        for (Held h : held) {
            Held current = best.get(h.kind());
            if (current == null) {
                best.put(h.kind(), h);
            } else if (h.remaining() > current.remaining()) {
                doomed.add(current);
                best.put(h.kind(), h);
            } else {
                doomed.add(h);
            }
        }
        for (Held h : doomed) {
            h.remove().run();
        }
        return doomed.size();
    }

    /** Whether the sweep applies to {@code player}: in the dungeon dimension and not yet through Act 0. */
    static boolean applies(MinecraftServer server, ServerPlayer player) {
        return DungeonWorldRules.inDungeon(player.level())
                && !DungeonProgress.prologueDone(DungeonLog.forServer(server), player.getUUID());
    }

    /** Sweeps {@code player}'s pack, cursor, open container and Dungeon Storage; tells them once if a tool went. */
    static void sweep(MinecraftServer server, ServerPlayer player) {
        List<Held> held = new ArrayList<>();
        Container inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            add(held, inventory, i);
        }
        ItemStack carried = player.containerMenu.getCarried();
        String carriedKind = kindOf(carried);
        if (!carriedKind.isEmpty()) {
            held.add(new Held(carriedKind, remaining(carried), () -> player.containerMenu.setCarried(ItemStack.EMPTY)));
        }
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container != inventory) {
                add(held, slot.container, slot.getContainerSlot());
            }
        }
        DungeonLog log = DungeonLog.forServer(server);
        List<ItemStack> storage = new ArrayList<>(log.storageOf(player.getUUID()));
        boolean storageOpen = false;
        for (Slot slot : player.containerMenu.slots) {
            storageOpen |= slot.container instanceof RunStorage.StorageContainer;
        }
        if (!storageOpen) {
            for (int i = 0; i < storage.size(); i++) {
                int index = i;
                String kind = kindOf(storage.get(i));
                if (!kind.isEmpty()) {
                    held.add(new Held(kind, remaining(storage.get(i)), () -> {
                        storage.set(index, ItemStack.EMPTY);
                        log.setStorage(player.getUUID(), storage);
                    }));
                }
            }
        }
        if (dedupe(held) > 0) {
            player.sendSystemMessage(Component.literal("A spare starter tool crumbled away. One set is all you need.")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private static void add(List<Held> held, Container container, int slot) {
        ItemStack stack = container.getItem(slot);
        String kind = kindOf(stack);
        if (!kind.isEmpty()) {
            held.add(new Held(kind, remaining(stack), () -> container.setItem(slot, ItemStack.EMPTY)));
        }
    }

    /** The once-a-second watch: sweeps each member of {@code record} who is online and still in Act 0. */
    static void tick(MinecraftServer server, InstanceRecord record) {
        for (java.util.UUID id : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && applies(server, player)) {
                sweep(server, player);
            }
        }
    }
}
