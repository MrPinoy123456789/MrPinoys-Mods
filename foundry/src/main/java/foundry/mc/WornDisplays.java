package foundry.mc;

import com.mojang.datafixers.util.Pair;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import foundry.core.WornPlacement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import java.util.function.Consumer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws worn trinkets for vanilla clients, which cannot render Trinkets models: one item display per trinket,
 * attached to the player with Polymer and turned with the body (or head) every tick. Only Foundry items in a Trinkets
 * slot with a placement are drawn. Where and how big comes from {@code config/foundry/worn_displays.json}
 * ({@link WornPlacement}); the file is checked every two seconds and a change is applied to everyone without a restart.
 * Worn trinkets are looked for every {@value #SCAN_EVERY} ticks, and the per tick turning only runs while someone wears one.
 * A display goes away when the trinket leaves its slot, and with death, logout, a new player object or a dimension change.
 */
public final class WornDisplays {
    static final int SCAN_EVERY = 10;
    private static final int CONFIG_EVERY = 40;
    private static final String FILE = "worn_displays.json";

    /**
     * A holder that can refuse to show itself to the wearer. A riding holder also tells each new viewer that its
     * displays are passengers of the player (the client then moves them with the player every frame). A head slot holder
     * has no displays: it puts the item in the helmet slot of each viewer's copy of the player.
     */
    private static final class Holder extends ElementHolder {
        private final ServerPlayer owner;
        private final boolean hideFromOwner;
        private final boolean ride;
        private final ItemStack headStack;

        Holder(ServerPlayer owner, boolean hideFromOwner, boolean ride, ItemStack headStack) {
            this.owner = owner;
            this.hideFromOwner = hideFromOwner;
            this.ride = ride;
            this.headStack = headStack;
        }

        @Override
        protected void startWatchingExtraPackets(ServerGamePacketListenerImpl connection,
                                                 Consumer<Packet<ClientGamePacketListener>> out) {
            if (headStack != null) out.accept(equipment(headStack));
            else if (ride) out.accept(new ClientboundSetPassengersPacket(owner));
        }

        private Packet<ClientGamePacketListener> equipment(ItemStack head) {
            return new ClientboundSetEquipmentPacket(owner.getId(), List.of(Pair.of(EquipmentSlot.HEAD, head)));
        }

        /** Gives every viewer the player's real helmet back (before a head slot holder goes away). */
        void restoreHead() {
            if (headStack == null) return;
            for (ServerGamePacketListenerImpl c : new ArrayList<>(getWatchingPlayers())) {
                if (c.isAcceptingMessages()) c.send(equipment(owner.getItemBySlot(EquipmentSlot.HEAD)));
            }
        }

        @Override
        public boolean startWatching(ServerGamePacketListenerImpl connection) {
            // Geyser cannot translate Polymer's item_display (it throws per packet on 26.3), so Bedrock players never get one.
            if (foundry.core.Floodgate.isBedrock(connection.getPlayer().getUUID())) return false;
            if (hideFromOwner && connection.getPlayer() == owner) return false;
            return super.startWatching(connection);
        }
    }

    /** {@code element} is null for a head slot part, which draws nothing itself. */
    private record Part(Holder holder, EntityAttachment attachment, ItemDisplayElement element, WornPlacement place,
                        String item, String slot, boolean headMode) {}

    private record Shown(ServerPlayer player, String signature, List<Part> parts) {}

    private static final Map<UUID, Shown> SHOWN = new HashMap<>();
    private static volatile WornPlacement.Table table = WornPlacement.defaults();
    private static long fileStamp = Long.MIN_VALUE;
    private static int tableVersion;

    private WornDisplays() {}

    /** How many displays this player has on right now (for tests). */
    public static int shownFor(UUID player) {
        Shown s = SHOWN.get(player);
        return s == null ? 0 : s.parts.size();
    }

    /** Test hook: the facing of one of a player's displays right now. */
    public static Quaternionf rotationOf(UUID player, int index) {
        Shown s = SHOWN.get(player);
        if (s == null || index >= s.parts.size() || s.parts.get(index).element == null) return null;
        return new Quaternionf(s.parts.get(index).element.getLeftRotation());
    }

    /** Test hook: "head_slot" or "display" for one of a player's parts, or null. */
    public static String modeOf(UUID player, int index) {
        Shown s = SHOWN.get(player);
        return s == null || index >= s.parts.size() ? null : s.parts.get(index).headMode ? "head_slot" : "display";
    }

    /**
     * Test hook: does for one viewer what Polymer's entity tracking does when a player comes into range (the harness's
     * mock players are never tracked by each other, so the real call cannot be exercised there).
     */
    public static void startTracking(UUID wearer, ServerPlayer viewer) {
        Shown s = SHOWN.get(wearer);
        if (s != null) s.parts.forEach(part -> part.holder.startWatching(viewer.connection));
    }

    /** Ids of everyone receiving this player's displays, per display (for tests and {@code /foundry worn list}). */
    public static List<List<UUID>> watchers(UUID player) {
        Shown s = SHOWN.get(player);
        List<List<UUID>> out = new ArrayList<>();
        if (s != null) {
            for (Part part : s.parts) {
                out.add(part.holder.getWatchingPlayers().stream().map(c -> c.getPlayer().getUUID()).toList());
            }
        }
        return out;
    }

    /** One line per display for each online player (or just {@code only}); what {@code /foundry worn list} prints. */
    static List<String> describe(MinecraftServer server, ServerPlayer only) {
        List<String> lines = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (only != null && only != player) continue;
            String name = player.getGameProfile().name();
            List<String> worn = new ArrayList<>();
            if (WornItems.trinkets()) {
                TrinketsHook.worn(player, stack -> {
                    if (stack.getItem() instanceof FoundryItem item && item.def() != null && item.def().worn()) {
                        worn.add(item.def().id() + " (" + item.def().wornSlots().get(0) + ")");
                    }
                });
            }
            var chest = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            lines.add(name + " chest slot: " + (chest.isEmpty() ? "empty" : net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(chest.getItem()) + (chest.getItem() instanceof FoundryItem w && w.def() != null && w.def().glider()
                    ? " (wings, drawn by the client as an elytra)" : "")));
            Shown shown = SHOWN.get(player.getUUID());
            lines.add(name + ": wears " + (worn.isEmpty() ? "no Foundry trinkets" : String.join(", ", worn))
                    + "; " + (shown == null ? 0 : shown.parts.size()) + " displays");
            if (shown == null) continue;
            for (Part part : shown.parts) {
                lines.add("  " + part.item + " in " + part.slot + " ("
                        + (part.headMode ? "helmet slot packet" : part.place.ride() ? "display riding the player" : "display")
                        + "): watched by "
                        + part.holder.getWatchingPlayers().stream().map(c -> c.getPlayer().getGameProfile().name()).sorted().toList()
                        + ", at " + String.format("up %.2f back %.2f side %.2f scale %.2f", part.place.up(), part.place.back(),
                                part.place.side(), part.place.scale())
                        + (part.place.hideFromWearer() ? ", hidden from wearer" : "")
                        + (part.headMode ? "" : ", position " + part.holder.getPos()));
            }
        }
        if (lines.isEmpty()) lines.add("No such player is online.");
        return lines;
    }

    /** True when someone has a display up, so the per tick turning has work to do. */
    static boolean active() {
        return !SHOWN.isEmpty();
    }

    private static Path file() {
        return FoundryMod.contentRoot().resolveSibling(FILE);
    }

    /** Re-reads the file when its modified time changed; a bad file keeps the table that was working. */
    private static void reloadConfig() {
        Path path = file();
        long stamp;
        try {
            stamp = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : -1L;
        } catch (IOException e) {
            return;
        }
        if (stamp == fileStamp) return;
        fileStamp = stamp;
        if (stamp < 0) {
            table = WornPlacement.defaults();
            tableVersion++;
            return;
        }
        try {
            var parsed = WornPlacement.parse(Files.readString(path));
            parsed.problems().forEach(p -> FoundryMod.LOG.warn("Foundry: {}", p));
            if (parsed.problems().isEmpty()) {
                table = parsed.table();
                tableVersion++;
                FoundryMod.LOG.info("Foundry: worn display placements reloaded from {}", path.getFileName());
            } else {
                FoundryMod.LOG.warn("Foundry: {} has problems; keeping the placements that were working", path.getFileName());
            }
        } catch (IOException e) {
            FoundryMod.LOG.warn("Foundry: could not read {}: {}", path.getFileName(), e.toString());
        }
    }

    static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (now % CONFIG_EVERY == 0) reloadConfig();
        if (now % SCAN_EVERY == 0) {
            List<UUID> online = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                online.add(player.getUUID());
                rescan(player);
            }
            SHOWN.entrySet().removeIf(e -> {
                if (online.contains(e.getKey())) return false;
                destroy(e.getValue());
                return true;
            });
        }
        if (SHOWN.isEmpty()) return;
        for (Shown shown : new ArrayList<>(SHOWN.values())) {
            // A head slot part needs the real helmet slot empty: rebuild at once when that changes.
            boolean headEmpty = shown.player.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
            if (shown.parts.stream().anyMatch(part -> part.place.headSlot() && part.headMode != headEmpty)) {
                rescan(shown.player);
                continue;
            }
            turn(shown);
        }
    }

    static void clear() {
        SHOWN.values().forEach(WornDisplays::destroy);
        SHOWN.clear();
        fileStamp = Long.MIN_VALUE;
    }

    private static void destroy(Shown s) {
        for (Part part : s.parts) {
            part.holder.restoreHead();
            part.attachment.destroy();
            part.holder.destroy();
        }
    }

    /** Works out which trinkets the player wears now and builds, keeps or removes their displays. */
    private static void rescan(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        List<WornPlacement> places = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<String> slotNames = new ArrayList<>();
        if (WornItems.trinkets() && player.isAlive()) {
            var current = table;
            TrinketsHook.worn(player, stack -> {
                if (stack.getItem() instanceof FoundryItem item && item.def() != null && item.def().worn()) {
                    current.find(item.def().id().toString(), item.def().wornSlots().get(0)).ifPresent(p -> {
                        stacks.add(stack.copyWithCount(1));
                        places.add(p);
                        ids.add(item.def().id().toString());
                        slotNames.add(item.def().wornSlots().get(0));
                    });
                }
            });
        }
        boolean headEmpty = player.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
        String signature = tableVersion + "|" + player.level().dimension() + "|" + headEmpty + "|"
                + stacks.stream().map(s -> s.getItem().toString()).toList();
        Shown cur = SHOWN.get(player.getUUID());
        if (cur != null && (cur.player != player || !cur.signature.equals(signature))) {
            destroy(cur);
            SHOWN.remove(player.getUUID());
            cur = null;
        }
        if (stacks.isEmpty() || cur != null) return;
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            WornPlacement place = places.get(i);
            if (place.headSlot() && headEmpty) {
                // Shown to other players as a helmet, so it moves with the head exactly. Never to the wearer.
                Holder holder = new Holder(player, true, false, stacks.get(i));
                parts.add(new Part(holder, EntityAttachment.ofTicking(holder, player), null, place, ids.get(i),
                        slotNames.get(i), true));
                continue;
            }
            Holder holder = new Holder(player, place.hideFromWearer(), place.ride(), null);
            ItemDisplayElement element = new ItemDisplayElement(stacks.get(i));
            element.setItemDisplayContext(ItemDisplayContext.FIXED);
            element.setInterpolationDuration(3);
            // Placed before the holder is attached, so the first packet a viewer gets already has the right transform.
            place(player, element, place);
            if (place.ride()) holder.addPassengerElement(element);
            else holder.addElement(element);
            EntityAttachment attachment = EntityAttachment.ofTicking(holder, player);
            // Polymer shows an attachment only to players tracking the entity, and nobody tracks themselves, so the wearer
            // is added by hand (the Holder refuses when the placement hides it from them).
            if (player.connection != null) holder.startWatching(player.connection);
            parts.add(new Part(holder, attachment, element, place, ids.get(i), slotNames.get(i), false));
        }
        Shown shown = new Shown(player, signature, parts);
        SHOWN.put(player.getUUID(), shown);
    }

    /** Places every display of one player for this tick. */
    private static void turn(Shown shown) {
        for (Part part : shown.parts) if (part.element != null) place(shown.player, part.element, part.place);
    }

    /** Sets one display's size, position and facing from the player's body (or head) right now. */
    private static void place(ServerPlayer player, ItemDisplayElement element, WornPlacement p) {
        boolean upright = !player.isInvisible() && player.getPose() != Pose.SWIMMING
                && player.getPose() != Pose.FALL_FLYING && player.getPose() != Pose.SLEEPING;
        float scale = upright ? p.scale() : 0f;
        float yaw = p.onHead() ? player.getYHeadRot() : player.yBodyRot;
        // Minecraft yaw 0 faces south (+z). "Back" is opposite the facing, "side" is to the player's right.
        double rad = Math.toRadians(yaw);
        // A riding display is placed from the player's top (where the client mounts it), so that height is taken off.
        float up = p.up() - (player.isCrouching() ? 0.25f : 0f) - (p.ride() ? player.getBbHeight() : 0f);
        Vector3f at = new Vector3f(
                (float) (Math.sin(rad) * p.back() - Math.cos(rad) * p.side()), up,
                (float) (-Math.cos(rad) * p.back() - Math.sin(rad) * p.side()));
        // A back item turns its front away from the player's back; a head item faces the way the head does.
        // A head item also tips with the head's pitch (positive looks down, which tips the front down).
        Quaternionf turn = new Quaternionf().rotationY((float) Math.toRadians((p.onHead() ? 0f : 180f) - yaw))
                .rotateX((float) Math.toRadians(p.onHead() ? player.getXRot() : 0f))
                .rotateXYZ((float) Math.toRadians(p.rx()), (float) Math.toRadians(p.ry()), (float) Math.toRadians(p.rz()));
        element.setLeftRotation(turn);
        element.setScale(new Vector3f(scale, scale, scale));
        element.setTranslation(at);
        element.startInterpolationIfDirty();
    }
}
