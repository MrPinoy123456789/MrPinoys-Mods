package charter.mc.book;

import charter.mc.CharterServer;
import charter.mc.Text;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** The Charter book and the founding Nexus: one register call from {@code CharterMod}. */
public final class BookMod {

    private BookMod() {
    }

    /**
     * Hands a book to a joining player who carries none (C2). Nothing is saved: a
     * player who loses their book gets another the next time they join.
     */
    public static void onJoin(ServerPlayer player) {
        if (!CharterServer.running()) return;
        ensure(player, false);
        AdminBook.ensure(player);
    }

    /** True when the player carries a Charter book anywhere on them. */
    public static boolean carriesBook(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (CharterBook.isBook(inventory.getItem(i))) return true;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (CharterBook.isBook(player.getItemBySlot(slot))) return true;
        }
        return false;
    }

    /**
     * Gives the player a book if they carry none. On console and Bedrock the book is the only way into the menu, since
     * there is no typing a command, so a lost one is replaced at once and not only when they next join. Who is handed
     * one at all is the {@code bookGivenTo} knob ({@link #autoGive}); extra copies go whatever it says.
     */
    public static void ensure(ServerPlayer player, boolean lost) {
        removeExtras(player);
        if (carriesBook(player)) return;
        if (!autoGive(player)) return;
        giveBook(player, CharterBook.create(player.getUUID()));
        // A new stack carries no glint or count yet: the next refresh writes them.
        SHOWN.remove(player.getUUID());
        player.sendSystemMessage(Component.literal(Text.get(lost ? "book.replaced" : "book.given")).withStyle(ChatFormatting.GOLD));
    }

    /**
     * A player never keeps more than one Charter book. The first one found (hotbar first, then the rest of the
     * inventory, armour and off hand, and last the stack on the cursor) stays; every other one is deleted. This is
     * what makes a looted book, or the old one lying where its owner died, harmless. Returns how many were deleted.
     */
    public static int removeExtras(ServerPlayer player) {
        var inventory = player.getInventory();
        boolean kept = false;
        ItemStack keeper = null;
        int removed = 0;
        int foreign = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!CharterBook.isBook(inventory.getItem(i))) continue;
            if (!own(player, inventory.getItem(i))) {
                inventory.setItem(i, ItemStack.EMPTY);
                foreign++;
                continue;
            }
            if (!kept) {
                kept = true;
                keeper = inventory.getItem(i);
            } else {
                inventory.setItem(i, ItemStack.EMPTY);
                removed++;
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            // The inventory above may already cover armour and the off hand: the same stack is never counted twice.
            ItemStack worn = player.getItemBySlot(slot);
            if (!CharterBook.isBook(worn) || worn == keeper) continue;
            if (!own(player, worn)) {
                player.setItemSlot(slot, ItemStack.EMPTY);
                foreign++;
                continue;
            }
            if (!kept) {
                kept = true;
                keeper = worn;
            } else {
                player.setItemSlot(slot, ItemStack.EMPTY);
                removed++;
            }
        }
        if (player.containerMenu != null && CharterBook.isBook(player.containerMenu.getCarried())) {
            ItemStack carried = player.containerMenu.getCarried();
            if (!own(player, carried)) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
                foreign++;
            } else if (kept) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
                removed++;
            }
        }
        if (removed + foreign > 0) {
            SHOWN.remove(player.getUUID());
            player.containerMenu.broadcastChanges();
        }
        if (removed > 0) player.sendSystemMessage(Component.literal(Text.get("book.duplicate")).withStyle(ChatFormatting.GOLD));
        if (foreign > 0) player.sendSystemMessage(Component.literal(Text.get("book.not_yours")).withStyle(ChatFormatting.GOLD));
        return removed + foreign;
    }

    /**
     * Whether a copy may stay with this player: their own, or one made before books had owners, which they adopt here.
     * A copy that belongs to someone else may not (the one-copy rule: each player carries their own book only).
     */
    private static boolean own(ServerPlayer player, ItemStack book) {
        return switch (BookRules.verdict(CharterBook.ownerOf(book), player.getUUID())) {
            case KEEP -> true;
            case ADOPT -> {
                CharterBook.setOwner(book, player.getUUID());
                yield true;
            }
            case FOREIGN -> false;
        };
    }

    /** Whether this player is handed a book automatically, by the {@code bookGivenTo} knob. */
    public static boolean autoGive(ServerPlayer player) {
        if (!CharterServer.running()) return false;
        var rt = CharterServer.runtime();
        var id = new charter.id.PlayerId(player.getUUID());
        return BookRules.gives(BookRules.mode(rt.knobs.bookGivenTo), rt.state.membership(id).isPresent(), rt.state.raceOf(id).isPresent());
    }

    /**
     * The menu's "Get a new Charter book" button: a fresh copy for portable use, whatever {@code bookGivenTo} says, but
     * never a second one. Returns the result line for the page (a {@link BookResult#ok} line when it was given).
     */
    public static String giveNew(ServerPlayer player) {
        removeExtras(player);
        if (carriesBook(player)) return Text.get("book.new.already");
        giveBook(player, CharterBook.create(player.getUUID()));
        SHOWN.remove(player.getUUID());
        return BookResult.ok(Text.get("book.new.given"));
    }

    /** Once a second for everyone online: a second book is deleted, whether or not lost books are replaced. */
    public static void removeExtrasAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            removeExtras(player);
            AdminBook.police(player);
        }
    }

    /** One pass over everyone online: whoever has no book gets one. */
    public static void ensureAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isAlive() && !player.isSpectator()) ensure(player, true);
        }
    }

    /**
     * The book goes where it is one press away: the last hotbar slot when it is free, otherwise the first free hotbar
     * slot, otherwise anywhere in the inventory, and at the player's feet only when nothing is free.
     */
    public static void giveBook(ServerPlayer player, ItemStack book) {
        var inventory = player.getInventory();
        int last = net.minecraft.world.entity.player.Inventory.SELECTION_SIZE - 1;
        if (inventory.getItem(last).isEmpty()) {
            inventory.setItem(last, book);
            return;
        }
        for (int i = 0; i < last; i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, book);
                return;
            }
        }
        giveOrDrop(player, book);
    }

    // ---- the book tells you when you are needed ----

    /** What each player's book last said, as "count|summary", so a stack is touched only when that changes. */
    private static final java.util.Map<java.util.UUID, String> SHOWN = new java.util.concurrent.ConcurrentHashMap<>();

    /** A slow pass over everyone online (every five seconds from the server tick). */
    public static void refreshAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isAlive() && !player.isSpectator()) refresh(player);
        }
    }

    /**
     * Writes the need count into the book's name, what needs the player into its lore, and turns the glint on while
     * anything waits: "Charter (2 need you)", "Votes: 1, Requests: 1". A soft chime plays when the count goes up.
     * Nothing is written when the count and the summary are what the book already shows.
     */
    public static void refresh(ServerPlayer player) {
        if (!CharterServer.running()) return;
        List<Needs.Need> needs = Needs.of(player);
        String summary = summary(needs);
        String now = needs.size() + "|" + summary;
        String before = SHOWN.get(player.getUUID());
        if (now.equals(before)) return;
        ItemStack book = theBook(player);
        if (book == null) {
            SHOWN.remove(player.getUUID());
            return;
        }
        SHOWN.put(player.getUUID(), now);
        apply(book, needs.size(), summary);
        int was = before == null ? 0 : Integer.parseInt(before.substring(0, before.indexOf('|')));
        if (needs.size() > was) Cues.chime(player);
    }

    static void apply(ItemStack book, int count, String summary) {
        String name = count > 0 ? Text.get("book.item.name_needs", "n", count) : Text.get("book.item.name");
        book.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name)
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withItalic(false)));
        List<Component> lore = new java.util.ArrayList<>();
        if (count > 0) lore.add(Component.literal(summary).withStyle(ChatFormatting.YELLOW));
        lore.add(Component.literal(Text.get("book.item.lore")).withStyle(ChatFormatting.GRAY));
        book.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
        if (count > 0) book.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        else book.remove(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    /** "Votes: 1, Requests: 2", in a fixed order, or empty when nothing waits. */
    static String summary(List<Needs.Need> needs) {
        List<String> parts = new java.util.ArrayList<>();
        for (Needs.Kind kind : Needs.Kind.values()) {
            long n = needs.stream().filter(x -> x.kind() == kind).count();
            if (n > 0) parts.add(Text.get("book.item.need." + kind.name().toLowerCase(java.util.Locale.ROOT), "n", n));
        }
        return String.join(", ", parts);
    }

    /** The player's one book: the first found, as {@link #removeExtras} keeps it. */
    static ItemStack theBook(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (CharterBook.isBook(inventory.getItem(i))) return inventory.getItem(i);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (CharterBook.isBook(player.getItemBySlot(slot))) return player.getItemBySlot(slot);
        }
        return null;
    }

    /** Into the inventory, or at the player's feet when it does not fit. Null when it fitted. */
    public static net.minecraft.world.entity.item.ItemEntity giveOrDrop(ServerPlayer player, ItemStack stack) {
        return player.getInventory().add(stack) ? null : player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    /** Books taken off a dying player, by player, to be handed back on respawn (or at once when a totem saved them). */
    private static final java.util.Map<java.util.UUID, ItemStack> STASH = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Takes every Charter book off a player who is about to die, so none is in the death drops. The player's own copy is
     * set aside and handed back when they respawn; extra or foreign copies are gone. Returns how many were taken.
     */
    public static int vanish(ServerPlayer player) {
        int n = 0;
        ItemStack kept = null;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (CharterBook.isBook(stack)) {
                if (kept == null && BookRules.verdict(CharterBook.ownerOf(stack), player.getUUID()) != BookRules.Verdict.FOREIGN) kept = stack;
                inventory.setItem(i, ItemStack.EMPTY);
                n++;
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (CharterBook.isBook(stack)) {
                if (kept == null && BookRules.verdict(CharterBook.ownerOf(stack), player.getUUID()) != BookRules.Verdict.FOREIGN) kept = stack;
                player.setItemSlot(slot, ItemStack.EMPTY);
                n++;
            }
        }
        if (kept != null) STASH.put(player.getUUID(), kept);
        return n;
    }

    /** Whether a book taken at death waits for this player (for tests). */
    public static boolean stashed(java.util.UUID id) {
        return STASH.containsKey(id);
    }

    /**
     * Hands back the book taken at death, unless the player already carries one; then, with {@code replaceLostBook} on,
     * gives one to a player the {@code bookGivenTo} knob covers who still has none. Called on respawn.
     */
    public static void respawned(ServerPlayer player) {
        ItemStack book = STASH.remove(player.getUUID());
        if (book != null && !carriesBook(player)) {
            giveBook(player, book);
            SHOWN.remove(player.getUUID());
        }
        if (CharterServer.running() && CharterServer.runtime().knobs.replaceLostBook) ensure(player, true);
    }

    /** A totem saved a player whose book was taken at death: once they are alive again, it goes back. */
    private static void returnSaved(net.minecraft.server.MinecraftServer server) {
        if (STASH.isEmpty()) return;
        for (java.util.UUID id : java.util.List.copyOf(STASH.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.isDeadOrDying() || player.isRemoved()) continue;
            ItemStack book = STASH.remove(id);
            if (book != null && !carriesBook(player)) giveBook(player, book);
        }
    }

    public static void register() {
        // Before the death loot is dropped: the book never lies on the ground, and respawn hands the same one back.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player) {
                vanish(player);
                AdminBook.vanish(player);
            }
            return true;
        });
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, Identifier.fromNamespaceAndPath("charter", "nexus"),
                NexusRecipe.SERIALIZER);
        CharterBook.register();
        AdminBook.register();
        FloodgateAdapter.installIfPresent();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            BookDialogs.LAST.remove(handler.player.getUUID());
            SHOWN.remove(handler.player.getUUID());
            BedrockForms.forget(handler.player.getUUID());
            STASH.remove(handler.player.getUUID());
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(BookMod::returnSaved);
        BoundItems.register();
    }
}
