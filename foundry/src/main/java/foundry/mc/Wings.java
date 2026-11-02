package foundry.mc;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Gliding as a race trait: a race whose look has {@code glide} (the Fairies of Everdelle, by their race:everdelle node)
 * can always glide, with nothing in the chest slot and nothing that wears out. The server lets them (the
 * {@code LivingEntityGlide} mixin answers yes to canGlide), and since vanilla's client only asks to glide when its own
 * chest slot holds an elytra, a Fairy who holds jump while falling is started here, as vanilla's jump to glide would.
 *
 * <p>Bedrock (Floodgate) players: gliding on Bedrock is decided by the Bedrock client, which only glides with an elytra
 * in its own chest slot, and Geyser checks the same (SessionPlayerEntity.canStartGliding: the chest item must be
 * minecraft:elytra with the glider component) before it sends "start gliding" to the server. So while a Bedrock player
 * of a gliding race has an empty real chest slot, their own client is
 * shown a stand in elytra in the chest slot. It is display only: the server never holds it, so taking, moving or
 * dropping it is undone by the server's resync, and it is sent again every two seconds. It goes when the wings come
 * off, a chestplate goes on, or the player dies. With a chestplate on, a Bedrock player cannot glide with the wings
 * (they are told once).
 */
public final class Wings {
    private static final Set<UUID> WINGED = new HashSet<>();
    /** Bedrock players whose own client is being shown the stand in elytra. */
    private static final Set<UUID> STAND_IN = new HashSet<>();
    /** Bedrock players already told that a chestplate stops the wings. */
    private static final Set<UUID> TOLD_CHEST = new HashSet<>();
    /** The chest armour slot of the player's own inventory screen (5 head, 6 chest, 7 legs, 8 feet). */
    static final int CHEST_SLOT = InventoryMenu.ARMOR_SLOT_START + 1;

    private Wings() {}

    /** True when this player's race can glide (refreshed every {@value WornDisplays#SCAN_EVERY} ticks). */
    public static boolean isWinged(UUID player) {
        return WINGED.contains(player);
    }


    static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (now % WornDisplays.SCAN_EVERY == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                boolean glides = false;
                if (player.isAlive()) {
                    for (var race : WornDisplays.table().races().entrySet()) {
                        if (race.getValue().stream().anyMatch(l -> l.glide()) && Gate.hasNode(player, race.getKey())) {
                            glides = true;
                        }
                    }
                }
                set(WINGED, player.getUUID(), glides);
                bedrockStandIn(player, now);
            }
        }
        if (WINGED.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (WINGED.contains(id) && player.isFallFlying() && !player.isInvisible() && now % 2 == 0) fairyDust(player);
            if (!WINGED.contains(id) || player.isFallFlying() || player.onGround() || player.isInWater()
                    || player.isPassenger() || player.getAbilities().flying) continue;
            if (player.getLastClientInput().jump() && player.getDeltaMovement().y < 0) player.startFallFlying();
        }
    }

    /** Whether a player's own client should be shown the stand in elytra. Pure, so it can be tested. */
    public static boolean standInWanted(boolean bedrock, boolean winged, boolean chestEmpty, boolean alive) {
        return bedrock && winged && chestEmpty && alive;
    }

    /** True while this player's client is being shown the stand in elytra (for tests and checks). */
    public static boolean showsStandIn(UUID player) {
        return STAND_IN.contains(player);
    }

    private static void bedrockStandIn(ServerPlayer player, int now) {
        UUID id = player.getUUID();
        boolean bedrock = foundry.core.Floodgate.isBedrock(id);
        boolean chestEmpty = player.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
        if (standInWanted(bedrock, WINGED.contains(id), chestEmpty, player.isAlive())) {
            TOLD_CHEST.remove(id);
            if (STAND_IN.add(id) || now % 40 == 0) sendChest(player, standIn());
            return;
        }
        if (STAND_IN.remove(id)) sendChest(player, player.inventoryMenu.getSlot(CHEST_SLOT).getItem().copy());
        if (bedrock && WINGED.contains(id) && !chestEmpty && TOLD_CHEST.add(id)) {
            player.sendSystemMessage(Component.literal("Bedrock can only glide with nothing in the chest slot: take off your "
                    + "chestplate to glide with the Fairy Wings."), true);
        }
    }

    private static ItemStack standIn() {
        ItemStack stack = new ItemStack(Items.ELYTRA);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Fairy Wings"));
        // marked, so a creative Bedrock player cannot turn this copy into a real elytra (CreativeSlotGuard)
        return HeadVisual.marked(stack);
    }

    /** Tells only this player's client what is in their chest slot; the server's own inventory is not touched. */
    private static void sendChest(ServerPlayer player, ItemStack stack) {
        if (player.connection == null) return;
        player.connection.send(new ClientboundContainerSetSlotPacket(player.inventoryMenu.containerId,
                player.inventoryMenu.getStateId(), CHEST_SLOT, stack));
    }

    /**
     * A trail of sparkles from a gliding Fairy's wings: two end rod sparkles a tick apart, from just behind the shoulders,
     * drifting slowly. Vanilla particles, so every client sees them, Bedrock included (Geyser translates particles).
     */
    private static void fairyDust(ServerPlayer player) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        double yaw = Math.toRadians(player.getYRot());
        double bx = player.getX() + Math.sin(yaw) * 0.5, bz = player.getZ() - Math.cos(yaw) * 0.5;
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, bx, player.getY() + 0.4, bz, 2, 0.35, 0.2, 0.35, 0.01);
    }

    private static void set(Set<UUID> set, UUID id, boolean on) {
        if (on) set.add(id);
        else set.remove(id);
    }

    static void forget(UUID player) {
        WINGED.remove(player);
        STAND_IN.remove(player);
        TOLD_CHEST.remove(player);
    }

    static void clear() {
        WINGED.clear();
        STAND_IN.clear();
        TOLD_CHEST.clear();
    }
}
