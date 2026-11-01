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
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import java.util.function.Consumer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

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
        private final boolean onlyOwner;
        private final boolean ride;
        private final ItemStack headStack;
        private ItemDisplayElement element;
        private WornPlacement place;

        Holder(ServerPlayer owner, boolean hideFromOwner, boolean onlyOwner, boolean ride, ItemStack headStack) {
            this.owner = owner;
            this.hideFromOwner = hideFromOwner;
            this.onlyOwner = onlyOwner;
            this.ride = ride;
            this.headStack = headStack;
        }

        void drive(ItemDisplayElement element, WornPlacement place) {
            this.element = element;
            this.place = place;
        }

        /**
         * Polymer ticks the holder from the player's own entity tracker, just before it sends this tick's changes, so the
         * transformation set here goes out in the same tick (as Polymer's own entity models do it, in onTick).
         */
        @Override
        protected void onTick() {
            if (element != null && place != null && !owner.isRemoved()) place(owner, element, place);
            super.onTick();
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

        ItemStack headStack() {
            return headStack;
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
            if (onlyOwner && connection.getPlayer() != owner) return false;
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

    /** How many worn trinkets this player has drawn right now (for tests); a helmet slot item with its wearer's copy is one. */
    public static int shownFor(UUID player) {
        Shown s = SHOWN.get(player);
        return s == null ? 0 : (int) s.parts.stream().map(Part::item).distinct().count();
    }

    /** Each player's body yaw and cape motion as the clients see them (see BodyYaw), fed once a tick. */
    private static final Map<UUID, foundry.core.BodyYaw> BODY = new HashMap<>();
    private static final Map<UUID, Integer> BODY_TICK = new HashMap<>();

    /** The player's body as clients draw it, brought up to this tick. */
    public static foundry.core.BodyYaw body(ServerPlayer player) {
        foundry.core.BodyYaw body = BODY.computeIfAbsent(player.getUUID(), id -> new foundry.core.BodyYaw());
        var server = player.level().getServer();
        int now = server == null ? 0 : server.getTickCount();
        Integer last = BODY_TICK.get(player.getUUID());
        if (last == null || last != now) {
            BODY_TICK.put(player.getUUID(), now);
            body.update(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.isSwinging(), player.isBlocking());
        }
        return body;
    }

    /** Test hook: the transformation one of a player's displays was last given, or null. */
    public static Matrix4f transformOf(UUID player, int index) {
        Shown s = SHOWN.get(player);
        if (s == null || index >= s.parts.size() || s.parts.get(index).element == null) return null;
        return transformFor(s.player, s.parts.get(index).place);
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
                // What the client is actually sent for this display: the stack after Polymer rewrote it.
                ItemStack real = part.headMode ? part.holder.headStack() : part.element.getItem();
                lines.add("    client stack: " + clientView(player, real) + "; display context "
                        + (part.headMode ? "HEAD (the client draws a helmet slot item with the head transform)"
                                : part.element.getItemDisplayContext()));
            }
        }
        if (lines.isEmpty()) lines.add("No such player is online.");
        return lines;
    }

    /** The item as the viewer's client receives it: base item, item_model and custom_model_data after Polymer's rewrite. */
    public static String clientView(ServerPlayer viewer, ItemStack stack) {
        try {
            ItemStack client = PacketContext.supplyWithContext(viewer.connection,
                    () -> PolymerItemUtils.getPolymerItemStack(stack, PacketContext.get(), viewer.registryAccess()));
            return BuiltInRegistries.ITEM.getKey(client.getItem()) + " item_model=" + client.get(DataComponents.ITEM_MODEL)
                    + " custom_model_data=" + client.get(DataComponents.CUSTOM_MODEL_DATA);
        } catch (RuntimeException e) {
            return "could not be worked out (" + e + ")";
        }
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
            BODY.keySet().removeIf(id -> !online.contains(id));
            BODY_TICK.keySet().removeIf(id -> !online.contains(id));
        }
        if (SHOWN.isEmpty()) return;
        for (Shown shown : new ArrayList<>(SHOWN.values())) {
            // A head slot part needs the real helmet slot empty: rebuild at once when that changes.
            boolean headEmpty = shown.player.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
            if (shown.parts.stream().anyMatch(part -> part.place.headSlot() && part.headMode != headEmpty)) {
                rescan(shown.player);
                continue;
            }
        }
    }

    static void clear() {
        SHOWN.values().forEach(WornDisplays::destroy);
        SHOWN.clear();
        BODY.clear();
        BODY_TICK.clear();
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
            boolean headMode = place.headSlot() && headEmpty;
            if (headMode) {
                // Shown to other players as a helmet, so it moves with the head exactly. The wearer's own client cannot be
                // sent that (it would put a ghost helmet in their inventory), so the wearer gets a display copy below.
                Holder holder = new Holder(player, true, false, false, stacks.get(i));
                parts.add(new Part(holder, EntityAttachment.ofTicking(holder, player), null, place, ids.get(i),
                        slotNames.get(i), true));
            }
            if (headMode && place.hideFromWearer()) continue;
            Holder holder = new Holder(player, place.hideFromWearer(), headMode, place.ride(), null);
            ItemDisplayElement element = new ItemDisplayElement(stacks.get(i));
            element.setItemDisplayContext(ItemDisplayContext.FIXED);
            // As Polymer's own entity models do: interpolate the transformation over a tick or two, and smooth the
            // position over three ticks for a display that is not riding (a rider is moved by the client every frame).
            element.setInterpolationDuration(1);
            element.setTeleportDuration(3);
            // Placed before the holder is attached, so the first packet a viewer gets already has the right transform.
            place(player, element, place);
            if (place.ride()) holder.addPassengerElement(element);
            else holder.addElement(element);
            holder.drive(element, place);
            EntityAttachment attachment = EntityAttachment.ofTicking(holder, player);
            // Polymer shows an attachment only to players tracking the entity, and nobody tracks themselves, so the wearer
            // is added by hand (the Holder refuses when the placement hides it from them).
            if (player.connection != null) holder.startWatching(player.connection);
            parts.add(new Part(holder, attachment, element, place, ids.get(i), slotNames.get(i), false));
        }
        Shown shown = new Shown(player, signature, parts);
        SHOWN.put(player.getUUID(), shown);
    }

    /** One block in the player model is 16 model pixels at the player's 0.9375 scale. */
    private static final float PX = 0.9375f / 16f;
    /** Height of the neck (the head and body pivot) above the feet when standing: 24 model pixels. */
    static final float NECK = 24 * PX;

    /** Sets one display's whole transformation from the player's pose right now (see {@link #transformFor}). */
    private static void place(ServerPlayer player, ItemDisplayElement element, WornPlacement p) {
        foundry.core.BodyYaw body = body(player);
        element.setTransformation(transform(player, p, body.yaw(), body.capeTilt(player.isCrouching())));
        element.startInterpolationIfDirty();
    }

    /**
     * The display's transformation for this tick, with the server's own body yaw (the live displays use the client
     * style body from {@link #body} instead, see {@link foundry.core.BodyYaw}), built the way vanilla draws a player (LivingEntityRenderer and the
     * player renderer's setupRotations), and the way Polymer's own entity models do it (Patbox's model handlers build a
     * matrix from the entity's body yaw and pose every tick and send it as the display transformation):
     * <ol>
     *   <li>start at the display's origin: the feet, or the top of the player for a riding display;</li>
     *   <li>turn by 180 minus the BODY yaw, so from here +x is the player's right, +y up and +z behind them;</li>
     *   <li>crouching drops the model 2 pixels; gliding and swimming tip the body forward as vanilla does
     *       (gliding by up to 90 degrees plus the look pitch, swimming the same and moved down and back);</li>
     *   <li>a head item then pivots at the neck, turns by the head's yaw relative to the body and tips by the head's
     *       pitch (crouching lowers the head 4.2 pixels); a body item pivots at the neck too and leans forward half a
     *       radian about the chest while crouching;</li>
     *   <li>the placement's up/back/side offset (heights measured from the feet when standing) and extra rotation, and
     *       a head item is turned to face forward; a swinging item (the cape) hangs from that point and swings back by
     *       the cape tilt;</li>
     *   <li>scale, or nothing at all while the player is invisible or asleep.</li>
     * </ol>
     */
    public static Matrix4f transformFor(ServerPlayer player, WornPlacement p) {
        return transform(player, p, player.yBodyRot, 6f);
    }

    /** {@link #transformFor} with the body yaw and cape tilt given (the live displays pass the client style ones). */
    public static Matrix4f transform(ServerPlayer player, WornPlacement p, float body, float capeTilt) {
        Matrix4f m = new Matrix4f();
        if (p.ride()) m.translate(0, -player.getBbHeight(), 0);
        m.rotateY((float) Math.toRadians(180f - body));
        boolean crouch = player.isCrouching();
        if (crouch) m.translate(0, -2 * PX, 0);
        float pitch = player.getXRot();
        float swim = player.getSwimAmount(1f);
        boolean lying = false;
        if (player.isFallFlying()) {
            float t = player.getFallFlyingTicks();
            float k = Math.min(1f, Math.max(0f, t * t / 100f));
            m.rotateX((float) Math.toRadians(k * (-90f - pitch)));
            lying = k > 0.5f;
        } else if (swim > 0f) {
            float target = player.isInWater() ? -90f - pitch : -90f;
            m.rotateX((float) Math.toRadians(swim * target));
            if (player.isVisuallySwimming()) m.translate(0, -1f, 0.3f);
            lying = swim > 0.5f;
        }
        if (p.onHead()) {
            m.translate(0, NECK - (crouch ? 4.2f * PX : 0f), 0);
            m.rotateY((float) Math.toRadians(body - player.getYHeadRot()));
            // Lying down, vanilla tips the head back up by 45 degrees instead of following the look pitch.
            float headPitch = lying ? -45f : pitch;
            m.rotateX((float) Math.toRadians(-headPitch));
        } else {
            m.translate(0, NECK - (crouch ? 3.2f * PX : 0f), 0);
            if (crouch) m.rotateX(-0.5f);
        }
        m.translate(p.side(), p.up() - NECK, p.back());
        if (p.swing() && !p.onHead()) {
            // Hung from its top edge here and swung back like vanilla's cape, then dropped by half its height
            // (16 model units are half a block at scale 1, so half the item is a quarter block times the scale).
            m.rotateX((float) Math.toRadians(-capeTilt));
            m.translate(0, -0.25f * p.scale(), 0);
        }
        // The item's front is +z (as in an item frame); a body item shows it outward from the back, a head item forward.
        if (p.onHead()) m.rotateY((float) Math.PI);
        m.rotateXYZ((float) Math.toRadians(p.rx()), (float) Math.toRadians(p.ry()), (float) Math.toRadians(p.rz()));
        boolean shown = !player.isInvisible() && !player.isSleeping();
        float scale = shown ? p.scale() : 0f;
        m.scale(scale);
        return m;
    }
}
