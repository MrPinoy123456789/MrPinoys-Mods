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

        private int wing;

        void drive(ItemDisplayElement element, WornPlacement place, int wing) {
            this.element = element;
            this.place = place;
            this.wing = wing;
        }

        /**
         * Polymer ticks the holder from the player's own entity tracker, just before it sends this tick's changes, so the
         * transformation set here goes out in the same tick (as Polymer's own entity models do it, in onTick).
         */
        @Override
        protected void onTick() {
            if (element != null && place != null && !owner.isRemoved()) place(owner, element, place, onlyOwner, wing);
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

        /** Sends the head item again to everyone watching (vanilla's own helmet updates can replace it). */
        void resendHead() {
            if (headStack == null) return;
            for (ServerGamePacketListenerImpl c : new ArrayList<>(getWatchingPlayers())) {
                if (c.isAcceptingMessages()) c.send(equipment(headStack));
            }
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

    private record Shown(ServerPlayer player, String signature, List<Part> parts, int headHash) {}

    private static int headHash(ServerPlayer player) {
        return ItemStack.hashItemAndComponents(player.getItemBySlot(EquipmentSlot.HEAD));
    }

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
            for (var race : table.races().entrySet()) {
                if (Gate.hasNode(player, race.getKey())) worn.add(race.getKey() + ": " + race.getValue().item());
            }
            var chest = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            lines.add(name + " chest slot: " + (chest.isEmpty() ? "empty" : net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(chest.getItem()) + (chest.getItem() instanceof FoundryItem w && w.def() != null && w.def().glider()
                    ? " (wings, drawn by the client as an elytra)" : "")));
            Shown shown = SHOWN.get(player.getUUID());
            lines.add(name + ": race looks " + (worn.isEmpty() ? "none (no race)" : String.join(", ", worn))
                    + "; " + (shown == null ? 0 : shown.parts.size()) + " displays");
            if (shown == null) continue;
            for (Part part : shown.parts) {
                lines.add("  " + part.item + " in " + part.slot + " ("
                        + (part.headMode ? "helmet slot packet" : part.place.ride() ? "display riding the player" : "display")
                        + (part.holder.onlyOwner ? ", the wearer's own copy" : "")
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

    /** The placements and race looks now in force. */
    static WornPlacement.Table table() {
        return table;
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
                ownHelmet(player, now);
            }
            SHOWN.entrySet().removeIf(e -> {
                if (online.contains(e.getKey())) return false;
                destroy(e.getValue());
                return true;
            });
            BODY.keySet().removeIf(id -> !online.contains(id));
            BODY_TICK.keySet().removeIf(id -> !online.contains(id));
            OWN_HELMET.removeIf(id -> !online.contains(id));
        }
        if (SHOWN.isEmpty()) return;
        for (Shown shown : new ArrayList<>(SHOWN.values())) {
            // A head slot part serves the real helmet with the trinket on it, so rebuild at once when the helmet changes
            // in any way (on, off, damaged, dyed). This runs after the player's tick, so it goes out after vanilla's own
            // helmet update and replaces it on every client.
            if (shown.parts.stream().anyMatch(part -> part.place.headSlot())
                    && headHash(shown.player) != shown.headHash) {
                rescan(shown.player);
                ownHelmet(shown.player, now);
                continue;
            }
            if (now % 40 == 0) shown.parts.forEach(part -> part.holder.resendHead());
        }
    }

    static void clear() {
        SHOWN.values().forEach(WornDisplays::destroy);
        SHOWN.clear();
        BODY.clear();
        BODY_TICK.clear();
        OWN_HELMET.clear();
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
        List<Boolean> flaps = new ArrayList<>();
        if (player.isAlive()) {
            // A race always shows its look, so everyone can tell a player's race at a glance (races in worn_displays.json).
            var current = table;
            for (var race : current.races().entrySet()) {
                if (!Gate.hasNode(player, race.getKey())) continue;
                var look = race.getValue();
                var ident = foundry.core.Ident.parse(look.item());
                var item = ident == null ? null : FoundryItems.get(ident);
                if (item == null) continue;
                current.find(look.item(), look.slot()).ifPresent(p -> {
                    stacks.add(new ItemStack(item));
                    places.add(p);
                    ids.add(look.item());
                    slotNames.add(look.slot());
                    flaps.add(look.flap());
                });
            }
        }
        ItemStack realHead = player.getItemBySlot(EquipmentSlot.HEAD);
        int headHash = ItemStack.hashItemAndComponents(realHead);
        boolean seesOwn = selfView(player.getUUID());
        String signature = tableVersion + "|" + player.level().dimension() + "|" + headHash + "|" + seesOwn + "|"
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
            ItemStack onHead = place.headSlot() ? HeadVisual.combine(realHead, stacks.get(i)) : null;
            boolean headMode = onHead != null;
            if (headMode) {
                // Drawn on the head by every client as a head item: the trinket alone, or the player's own helmet with the
                // trinket on it. Other players get it as their copy of this player's helmet; the wearer gets it in their
                // own helmet slot (ownHelmet), so it is hidden in first person like any helmet.
                Holder holder = new Holder(player, true, false, false, onHead);
                parts.add(new Part(holder, EntityAttachment.ofTicking(holder, player), null, place, ids.get(i),
                        slotNames.get(i), true));
            } else {
                addDisplays(parts, player, stacks.get(i), place, ids.get(i), slotNames.get(i), flaps.get(i), false);
            }
            // The wearer's own copy, only for a player who asked to see their trinkets (/trinketview): the client draws
            // everything attached to a player even in first person, and never tells the server which view it is in.
            if (seesOwn && !headMode && !place.hideFromWearer()) {
                addDisplays(parts, player, stacks.get(i), place, ids.get(i), slotNames.get(i), flaps.get(i), true);
            }
        }
        Shown shown = new Shown(player, signature, parts, headHash);
        SHOWN.put(player.getUUID(), shown);
    }

    /**
     * One display of a worn trinket, for other players (wearersCopy false) or for the wearer alone (true). The copy for
     * others is smoothed over three ticks, the same as their clients smooth this player's head and body, so the two stay
     * together; the wearer's copy is smoothed over one tick and drawn ahead by their ping (see place).
     */
    /**
     * The displays for one look: one display, or for a pair of flapping wings two, the left and right halves (vanilla
     * stacks naming the {@code <item>_left} and {@code <item>_right} models), each turning about the spine on its own.
     */
    private static void addDisplays(List<Part> parts, ServerPlayer player, ItemStack stack, WornPlacement place, String id,
                                    String slot, boolean flap, boolean wearersCopy) {
        if (!flap) {
            parts.add(display(player, stack, place, id, slot, wearersCopy, 0));
            return;
        }
        var ident = foundry.core.Ident.parse(id);
        for (int wing : new int[] {-1, 1}) {
            ItemStack half = new ItemStack(net.minecraft.world.item.Items.PAPER);
            half.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.fromNamespaceAndPath(
                    ident.namespace(), ident.path() + (wing < 0 ? "_left" : "_right")));
            parts.add(display(player, half, place, id, slot, wearersCopy, wing));
        }
    }

    private static Part display(ServerPlayer player, ItemStack stack, WornPlacement place, String id, String slot,
                                boolean wearersCopy, int wing) {
        Holder holder = new Holder(player, !wearersCopy, wearersCopy, place.ride(), null);
        ItemDisplayElement element = new ItemDisplayElement(stack);
        element.setItemDisplayContext(ItemDisplayContext.FIXED);
        // wings beat every tick, so they are smoothed over two ticks whoever sees them
        element.setInterpolationDuration(wing != 0 ? 2 : wearersCopy ? 1 : 3);
        element.setTeleportDuration(3);
        // Placed before the holder is attached, so the first packet a viewer gets already has the right transform.
        place(player, element, place, wearersCopy, wing);
        if (place.ride()) holder.addPassengerElement(element);
        else holder.addElement(element);
        holder.drive(element, place, wing);
        EntityAttachment attachment = EntityAttachment.ofTicking(holder, player);
        // Polymer shows an attachment only to players tracking the entity, and nobody tracks themselves, so the wearer's
        // copy is given to them by hand.
        if (wearersCopy && player.connection != null) holder.startWatching(player.connection);
        return new Part(holder, attachment, element, place, id, slot, false);
    }

    // ---- the wearer's own head slot item ----

    /** Players whose own client is being shown their head slot trinket as a helmet. */
    private static final java.util.Set<UUID> OWN_HELMET = new java.util.HashSet<>();
    /** The helmet slot of the player's own inventory screen (5 head, 6 chest, 7 legs, 8 feet). */
    static final int HEAD_SLOT = net.minecraft.world.inventory.InventoryMenu.ARMOR_SLOT_START;

    /** Test hook: the item served on this player's head (the trinket, or their helmet with it on), or null. */
    public static ItemStack headItemFor(UUID player) {
        Shown s = SHOWN.get(player);
        if (s == null) return null;
        return s.parts.stream().filter(Part::headMode).map(part -> part.holder.headStack()).findFirst().orElse(null);
    }

    /** True while this player's own client is shown their head slot trinket as a helmet (for tests and checks). */
    public static boolean showsOwnHelmet(UUID player) {
        return OWN_HELMET.contains(player);
    }

    /**
     * The head item (the antlers, or the player's helmet with the antlers on it, see {@link HeadVisual}) is also put in
     * the wearer's own helmet slot, on their client only, so their game draws it on their own head: exact, and hidden in
     * first person like any helmet. The server's inventory is not touched. In survival every click is checked by the
     * server, so the copy cannot be taken; in creative the client is trusted, so the copy carries a mark and
     * {@code CreativeSlotGuard} refuses any marked item a client sends back (that is how a second pair appeared). It is
     * sent again every two seconds, and the real helmet slot item is sent back when the trinket comes off, the player
     * dies or the placement hides it from its wearer. Not for Bedrock players.
     */
    private static void ownHelmet(ServerPlayer player, int now) {
        UUID id = player.getUUID();
        Shown shown = SHOWN.get(id);
        Part head = shown == null || shown.player != player ? null
                : shown.parts.stream().filter(Part::headMode).findFirst().orElse(null);
        boolean want = head != null && !head.place.hideFromWearer() && player.isAlive()
                && !foundry.core.Floodgate.isBedrock(id);
        if (want) {
            if (OWN_HELMET.add(id) || now % 40 == 0) sendOwnSlot(player, HEAD_SLOT, head.holder.headStack());
        } else if (OWN_HELMET.remove(id)) {
            sendOwnSlot(player, HEAD_SLOT, player.inventoryMenu.getSlot(HEAD_SLOT).getItem().copy());
        }
    }

    /** Tells only this player's client what is in one slot of their own inventory screen. */
    private static void sendOwnSlot(ServerPlayer player, int slot, ItemStack stack) {
        if (player.connection == null) return;
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                player.inventoryMenu.containerId, player.inventoryMenu.getStateId(), slot, stack));
    }

    // ---- seeing your own trinkets ----

    private static final java.util.Set<UUID> SELF_VIEW = new java.util.HashSet<>();
    private static boolean selfViewLoaded;

    private static Path selfViewFile() {
        return FoundryMod.contentRoot().resolveSibling("worn_self_view.json");
    }

    /** True when this player asked to see their own worn trinkets. Off unless they turned it on. */
    public static boolean selfView(UUID player) {
        if (!selfViewLoaded) {
            selfViewLoaded = true;
            try {
                Path f = selfViewFile();
                if (Files.isRegularFile(f)) {
                    for (var e : com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonArray("players")) {
                        SELF_VIEW.add(UUID.fromString(e.getAsString()));
                    }
                }
            } catch (Exception e) {
                FoundryMod.LOG.warn("Foundry: could not read worn_self_view.json: {}", e.toString());
            }
        }
        return SELF_VIEW.contains(player);
    }

    /** Turns seeing your own trinkets on or off, remembered in config/foundry/worn_self_view.json. */
    public static void setSelfView(UUID player, boolean on) {
        selfView(player);
        if (on) SELF_VIEW.add(player);
        else SELF_VIEW.remove(player);
        var players = new com.google.gson.JsonArray();
        SELF_VIEW.stream().map(UUID::toString).sorted().forEach(players::add);
        var root = new com.google.gson.JsonObject();
        root.add("players", players);
        try {
            Files.createDirectories(selfViewFile().getParent());
            Files.writeString(selfViewFile(), root + "\n");
        } catch (IOException e) {
            FoundryMod.LOG.warn("Foundry: could not save worn_self_view.json: {}", e.toString());
        }
    }

    /** Rebuilds one player's displays now (after a setting of theirs changed). */
    public static void refresh(ServerPlayer player) {
        rescan(player);
    }

    /**
     * {@code /trinketview [on|off]}, for everyone: whether you see your own worn trinkets. Off by default, because the
     * game draws them in first person too and does not tell the server when you switch to third person.
     */
    static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(net.minecraft.commands.Commands.literal("trinketview")
                        .executes(ctx -> toggleView(ctx.getSource().getPlayerOrException(), null))
                        .then(net.minecraft.commands.Commands.literal("on").executes(ctx -> toggleView(ctx.getSource().getPlayerOrException(), true)))
                        .then(net.minecraft.commands.Commands.literal("off").executes(ctx -> toggleView(ctx.getSource().getPlayerOrException(), false)))));
    }

    private static int toggleView(ServerPlayer player, Boolean on) {
        boolean now = on != null ? on : !selfView(player.getUUID());
        setSelfView(player.getUUID(), now);
        refresh(player);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(now
                ? "You now see your own worn trinkets. They show in first person too (the game does not tell the server "
                        + "which view you use); /trinketview off hides them again."
                : "Your own worn trinkets are hidden from you. Everyone else still sees them; /trinketview on shows them."));
        return 1;
    }

    /** One block in the player model is 16 model pixels at the player's 0.9375 scale. */
    private static final float PX = 0.9375f / 16f;
    /** Height of the neck (the head and body pivot) above the feet when standing: 24 model pixels. */
    static final float NECK = 24 * PX;

    /** Sets one display's whole transformation from the player's pose right now (see {@link #transformFor}). */
    private static void place(ServerPlayer player, ItemDisplayElement element, WornPlacement p, boolean wearersCopy, int wing) {
        if (p.ride()) {
            // A riding display's transformation starts at the top of the player, where the client seats a passenger. Until a
            // viewer's client has seated it (just after it appears, a respawn or a teleport) it stands at its own position,
            // so that position is put at the top of the player too; otherwise it showed a body height too low, below the feet.
            double top = player.getBbHeight();
            if (element.getOffset().y != top) element.setOffset(new net.minecraft.world.phys.Vec3(0, top, 0));
        }
        foundry.core.BodyYaw body = body(player);
        float tilt = body.capeTilt(player.isCrouching());
        if (wearersCopy) {
            // The wearer's own model turns at once on their screen, and this copy arrives a round trip later, so it is
            // drawn as far ahead as their ping plus one tick (at most six ticks, and at most 45 degrees).
            float lead = Math.min(6f, Math.max(1f, player.connection == null ? 1f : player.connection.latency() / 50f + 1f));
            element.setTransformation(transform(player, p, body.yawAhead(lead), body.headAhead(player.getYHeadRot(), lead),
                    player.getXRot(), tilt, hinge(player, body, wing)));
        } else {
            element.setTransformation(transform(player, p, body.yaw(), player.getYHeadRot(), player.getXRot(), tilt,
                    hinge(player, body, wing)));
        }
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
     *       a back item's model is already mirrored to face out from the back; a swinging item (the cape) hangs from that point and swings back by
     *       the cape tilt;</li>
     *   <li>scale, or nothing at all while the player is invisible or asleep.</li>
     * </ol>
     */
    public static Matrix4f transformFor(ServerPlayer player, WornPlacement p) {
        return transform(player, p, player.yBodyRot, 6f);
    }

    /** {@link #transformFor} with the body yaw and cape tilt given (the live displays pass the client style ones). */
    public static Matrix4f transform(ServerPlayer player, WornPlacement p, float body, float capeTilt) {
        return transform(player, p, body, player.getYHeadRot(), player.getXRot(), capeTilt, 0f);
    }

    /**
     * A wing half's turn about the spine this tick, in degrees: 0 for anything that is not a wing. The beat comes from
     * {@link foundry.core.WingBeat}; a positive fold sweeps the tips back, so the right wing (+x) turns one way and the left
     * the other.
     */
    public static float hinge(ServerPlayer player, foundry.core.BodyYaw body, int wing) {
        if (wing == 0) return 0f;
        var server = player.level().getServer();
        long tick = server == null ? 0 : server.getTickCount();
        var state = foundry.core.WingBeat.state(player.isFallFlying(), player.onGround(), body.speed());
        float fold = foundry.core.WingBeat.fold(state, tick, player.getUUID().hashCode());
        return wing > 0 ? -fold : fold;
    }

    /** {@link #transform} with the head's yaw and pitch given too (the wearer's own copy passes predicted ones). */
    public static Matrix4f transform(ServerPlayer player, WornPlacement p, float body, float headYaw, float headPitch,
                                     float capeTilt, float hinge) {
        Matrix4f m = new Matrix4f();
        if (p.ride()) m.translate(0, -player.getBbHeight(), 0);
        m.rotateY((float) Math.toRadians(180f - body));
        boolean crouch = player.isCrouching();
        if (crouch) m.translate(0, -2 * PX, 0);
        float pitch = headPitch;
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
            m.rotateY((float) Math.toRadians(body - headYaw));
            // Lying down, vanilla tips the head back up by 45 degrees instead of following the look pitch.
            float tip = lying ? -45f : pitch;
            m.rotateX((float) Math.toRadians(-tip));
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
        // A display shows a model as it is: its north face toward the display's -z, its +x to the display's +x. Here -z is
        // forward and +x is the player's right, so a head model faces forward. Back models (pauldron, cape, wings) are
        // built mirrored front to back by tools/gen_trinket_models.py so their outer face looks out from the back. A half
        // turn here instead would also swap left and right (found in game: the wings crossed over and the pauldron's lames
        // pointed at the neck).
        // a wing half turns about the spine, which is the model's centre line (x 8), so here, before any scale
        if (hinge != 0f) m.rotateY((float) Math.toRadians(hinge));
        m.rotateXYZ((float) Math.toRadians(p.rx()), (float) Math.toRadians(p.ry()), (float) Math.toRadians(p.rz()));
        boolean shown = !player.isInvisible() && !player.isSleeping();
        float scale = shown ? p.scale() : 0f;
        m.scale(scale);
        return m;
    }
}
