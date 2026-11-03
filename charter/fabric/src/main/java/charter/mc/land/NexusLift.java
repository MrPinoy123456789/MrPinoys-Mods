package charter.mc.land;

import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.land.Core;
import charter.mc.CharterMod;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.book.Cues;
import charter.org.MemberRecord;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Moving a kingdom's lodestone by hand: the Lord (anyone with the place-core power, and operators) mines the Nexus
 * and it becomes a bound item, a lodestone that names the keep; placed anywhere inside that keep's own chunks it
 * stands again there and the keep teleport, the jail and the void rescue follow it. The keep, its land, vault, granary
 * and lectern stay where they are. {@code /charter nexus move} still works, and is refused while a lift is under way.
 *
 * <p>The item is soulbound: it cannot be dropped, put in a container, bundle or frame, or placed by anyone else, and a
 * copy anywhere but its carrier's inventory is removed. The lodestone goes back to where it stood by itself when the
 * carrier dies, logs out for longer than {@code nexusLiftLogoutSeconds}, carries it longer than {@code nexusLiftMinutes},
 * or loses the item some other way, and at once when the keep is at war or open to attack.
 *
 * <p>Sieges never see a missing Nexus: a lodestone cannot be lifted while its kingdom is named in any war goal or its
 * keep is not protected, and a war or exposure that begins during a lift puts it back within a second. While it is up,
 * {@code /keep} and {@code /hub} arrive at the middle of the keep's core chunk with a line that says why.
 */
public final class NexusLift {

    /** The custom data kind of the bound item (not {@link LandItems#NEXUS}, which founds or raises a keep). */
    public static final String KIND = "nexus_lifted";
    static final String LIFT_KEY = "charter_lift";
    static final String KEEP_KEY = "charter_keep";
    static final String CARRIER_KEY = "charter_carrier";
    /** Ticks between the checks that end a lift and sweep stale items. */
    static final int CHECK_EVERY_TICKS = 20;

    private static final LiftBook BOOK = new LiftBook();
    private static Path dir;
    private static long ticks;

    private NexusLift() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!CharterServer.running()) return;
            dir = CharterServer.charterDir(server);
            BOOK.load(dir, CharterMod.LOG::warn);
            // A lift still on file means the server stopped mid lift: every such lodestone goes back where it stood.
            for (LiftBook.Lift lift : BOOK.all()) {
                CharterMod.LOG.info("Charter: putting back the lifted Nexus of keep {} at {}, {}, {}", lift.keep(), lift.x(), lift.y(), lift.z());
                restore(server, lift, null);
            }
        });
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> save());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // The file stays: the next start puts each lodestone back, and the join sweep takes the stale items.
            save();
            dir = null;
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player && CharterServer.running()) {
                LiftBook.Lift lift = BOOK.byCarrier(player.getUUID());
                if (lift != null) restore(player.level().getServer(), lift, "nexus_lift.back_death");
            }
            return true;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LiftBook.Lift lift = BOOK.byCarrier(handler.player.getUUID());
            if (lift != null) {
                BOOK.put(lift.offline(System.currentTimeMillis()));
                save();
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!CharterServer.running()) return;
            LiftBook.Lift lift = BOOK.byCarrier(handler.player.getUUID());
            if (lift != null && lift.offlineSince() != 0) {
                BOOK.put(lift.offline(0));
                save();
            }
            sweep(handler.player);
        });
        ServerTickEvents.END_SERVER_TICK.register(NexusLift::tick);
    }

    private static void save() {
        if (dir != null) BOOK.save(dir, CharterMod.LOG::warn);
    }

    // ---- questions ----

    /** Whether this keep's lodestone is lifted right now. */
    public static boolean lifted(CoreId keep) {
        return BOOK.byKeep(keep.value().toString()) != null;
    }

    /** Whether this stack is a lifted Nexus. */
    public static boolean isLiftedItem(ItemStack stack) {
        return KIND.equals(LandItems.kindOf(stack));
    }

    /** The lift a carried item belongs to, or null. */
    static String liftIdOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String id = data.copyTag().getStringOr(LIFT_KEY, "");
        return id.isBlank() ? null : id;
    }

    /** The lift this player carries, or null (for tests and the book). */
    public static LiftBook.Lift carriedBy(UUID player) {
        return BOOK.byCarrier(player);
    }

    /**
     * Whether a keep is open to attack or its kingdom is in a war: the lodestone then cannot be lifted, and a lifted one
     * goes back. A kingdom named in any war goal counts, attacker or defender, so lifting is never a way out of a war.
     */
    static boolean exposed(CharterRuntime rt, Core keep) {
        OrgId root = rt.state.root(keep.owner);
        if (rt.state.wars.goals.stream().anyMatch(g -> root.equals(g.attacker) || root.equals(g.defender))) return true;
        Claim claim = rt.state.claimAt(keep.center).orElse(null);
        return claim == null || !rt.sim.rules().protectedClaim(claim);
    }

    // ---- lifting ----

    /**
     * A break of a keep's Nexus. The caller always cancels the vanilla break (so no plain lodestone drops); this lifts the
     * lodestone into the bound item when the player may, and otherwise says why not.
     */
    public static void tryLift(ServerPlayer player, Level level, BlockPos pos, Fixtures.Fixture fixture, Core core) {
        CharterRuntime rt = CharterServer.runtime();
        if (!(level instanceof ServerLevel)) return;
        boolean op = SpawnRules.isOp(player);
        boolean npc = rt.state.org(core.owner).map(o -> o.npc).orElse(true);
        if (!core.isKeep() || npc || !mayLift(rt, player, core, op) || (SpawnRules.inSpawn(level, pos) && !op)) {
            refuse(player, "land.nexus_unbreakable");
            return;
        }
        if (exposed(rt, core)) {
            refuse(player, "nexus_lift.at_war");
            return;
        }
        if (BOOK.byCarrier(player.getUUID()) != null) {
            refuse(player, "nexus_lift.one_at_a_time");
            return;
        }
        if (player.getInventory().getFreeSlot() < 0) {
            refuse(player, "nexus_lift.no_room");
            return;
        }
        OrgId root = rt.state.root(core.owner);
        LiftBook.Lift lift = new LiftBook.Lift(UUID.randomUUID().toString(), core.id.value().toString(), root.value().toString(),
                player.getUUID(), fixture.dimension(), fixture.x(), fixture.y(), fixture.z(), System.currentTimeMillis(), 0);
        Fixtures.of(rt.state).remove(fixture);
        level.levelEvent(2001, pos, Block.getId(level.getBlockState(pos)));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        BOOK.put(lift);
        save();
        ItemStack item = item(lift, rt.describe.org(root));
        int selected = player.getInventory().getSelectedSlot();
        if (player.getInventory().getItem(selected).isEmpty()) player.getInventory().setItem(selected, item);
        else player.getInventory().add(item);
        String line = Text.get("nexus_lift.lifted", "minutes", rt.knobs.nexusLiftMinutes);
        player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.AQUA));
        Cues.actionBar(player, Text.get("nexus_lift.lifted_short"), ChatFormatting.AQUA);
    }

    static boolean mayLift(CharterRuntime rt, ServerPlayer player, Core core, boolean op) {
        if (op) return true;
        OrgId root = rt.state.root(core.owner);
        PlayerId id = new PlayerId(player.getUUID());
        boolean member = rt.state.membership(id).map(MemberRecord::org).map(rt.state::root).filter(root::equals).isPresent();
        return member && new AuthorityResolver(rt.state, rt.clock.now())
                .resolve(new Who.AsPlayer(id), root, Power.PLACE_CORE, ScopeNeed.NONE, false).ok();
    }

    private static void refuse(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.literal(Text.get(key)).withStyle(ChatFormatting.RED), true);
    }

    /** The bound item for a lift: a glinting lodestone that names the kingdom, one to a stack. */
    static ItemStack item(LiftBook.Lift lift, String orgName) {
        ItemStack stack = new ItemStack(Items.LODESTONE);
        CompoundTag tag = new CompoundTag();
        tag.putString(LandItems.KEY, KIND);
        tag.putString(LIFT_KEY, lift.id());
        tag.putString(KEEP_KEY, lift.keep());
        tag.putString(CARRIER_KEY, lift.carrier().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(Text.get("nexus_lift.item.name", "org", orgName))
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                (Component) Component.literal(Text.get("nexus_lift.item.lore", "org", orgName)).withStyle(ChatFormatting.GRAY),
                Component.literal(Text.get("nexus_lift.item.lore2")).withStyle(ChatFormatting.GRAY))));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    // ---- setting it down ----

    /**
     * The placement of a lifted Nexus, from {@code Placement.place} ahead of the claim rules. Inside the keep's own
     * chunks the lodestone stands again where vanilla put it; anywhere else, or for a stale item or someone else's,
     * the placement is refused and the item stays.
     */
    public static InteractionResult place(BlockPlaceContext ctx, ServerPlayer player, Supplier<InteractionResult> original) {
        CharterRuntime rt = CharterServer.runtime();
        ItemStack held = ctx.getItemInHand();
        LiftBook.Lift lift = BOOK.byId(liftIdOf(held));
        if (lift == null || !lift.carrier().equals(player.getUUID())) {
            held.setCount(0);
            refuse(player, "nexus_lift.stale");
            return InteractionResult.FAIL;
        }
        Level level = ctx.getLevel();
        BlockPos target = ctx.getClickedPos();
        Claim claim = rt.state.claimAt(Placement.chunkOf(level, target)).orElse(null);
        if (claim == null || !claim.core().value().toString().equals(lift.keep())) {
            String org = rt.state.org(rt.state.root(new OrgId(UUID.fromString(lift.org())))).map(o -> o.name()).orElse("?");
            refuse(player, "nexus_lift.outside");
            player.sendSystemMessage(Component.literal(Text.get("nexus_lift.outside_hint", "org", org)).withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }
        InteractionResult result = original.get();
        if (!result.consumesAction()) return result;
        Fixtures.of(rt.state).add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, Protection.dimension(level),
                target.getX(), target.getY(), target.getZ(), claim.core()));
        BOOK.remove(lift.keep());
        save();
        strip(player, lift.id());
        String line = Text.get("nexus_lift.placed");
        player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GREEN));
        Cues.actionBar(player, line, ChatFormatting.GREEN);
        return result;
    }

    // ---- putting it back ----

    /**
     * Ends a lift by putting the lodestone back where it stood (lifted clear of anything built there since), takes the
     * item from its carrier when online, and tells them why with {@code key} (null says nothing).
     */
    static void restore(MinecraftServer server, LiftBook.Lift lift, String key) {
        BOOK.remove(lift.keep());
        save();
        CharterRuntime rt = CharterServer.runtime();
        CoreId keep = new CoreId(UUID.fromString(lift.keep()));
        if (rt.state.core(keep).isPresent() && server != null) {
            ServerLevel level = levelOf(server, lift.dimension());
            if (level != null) {
                BlockPos pos = new BlockPos(lift.x(), lift.y(), lift.z());
                while (!level.getBlockState(pos).canBeReplaced() && pos.getY() < level.getMaxY()) pos = pos.above();
                level.setBlockAndUpdate(pos, Blocks.LODESTONE.defaultBlockState());
                Fixtures.of(rt.state).add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, lift.dimension(), pos.getX(), pos.getY(), pos.getZ(), keep));
            }
        }
        ServerPlayer carrier = server == null ? null : server.getPlayerList().getPlayer(lift.carrier());
        if (carrier != null) {
            strip(carrier, lift.id());
            if (key != null) {
                carrier.sendSystemMessage(Component.literal(Text.get(key)).withStyle(ChatFormatting.GOLD));
                Cues.actionBar(carrier, Text.get("nexus_lift.back_short"), ChatFormatting.GOLD);
            }
        }
    }

    /**
     * Ends a lift without putting anything back, for an operator moving the whole keep: the keep gets a new lodestone
     * anyway. The carrier loses the item.
     */
    public static void cancel(MinecraftServer server, CoreId keep) {
        LiftBook.Lift lift = BOOK.remove(keep.value().toString());
        if (lift == null) return;
        save();
        ServerPlayer carrier = server == null ? null : server.getPlayerList().getPlayer(lift.carrier());
        if (carrier != null) strip(carrier, lift.id());
    }

    /** Ends every lift, putting each lodestone back (for tests). */
    public static void restoreAll(MinecraftServer server) {
        for (LiftBook.Lift lift : BOOK.all()) restore(server, lift, null);
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (Protection.dimension(level).equals(dimension)) return level;
        }
        return null;
    }

    // ---- each second ----

    private static void tick(MinecraftServer server) {
        if (!CharterServer.running()) return;
        ticks++;
        if (ticks % CHECK_EVERY_TICKS != 0) return;
        check(server);
    }

    /** Ends the lifts that should end and sweeps stale items. Public so a test can run it at once. */
    public static void check(MinecraftServer server) {
        CharterRuntime rt = CharterServer.runtime();
        long now = System.currentTimeMillis();
        for (LiftBook.Lift lift : BOOK.all()) {
            Core keep = rt.state.core(new CoreId(UUID.fromString(lift.keep()))).orElse(null);
            ServerPlayer carrier = server.getPlayerList().getPlayer(lift.carrier());
            boolean carries = carrier != null && count(carrier, lift.id()) > 0;
            LiftBook.End end = LiftBook.end(lift, now, rt.knobs.nexusLiftMinutes * 60_000L, rt.knobs.nexusLiftLogoutSeconds * 1000L,
                    keep != null, keep != null && exposed(rt, keep), carrier != null, carries);
            if (end == null) continue;
            if (end == LiftBook.End.GONE) cancel(server, new CoreId(UUID.fromString(lift.keep())));
            else restore(server, lift, "nexus_lift.back_" + end.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sweep(player);
    }

    /** Removes every lifted Nexus a player has that is not the one they are carrying for a lift under way. */
    static void sweep(ServerPlayer player) {
        LiftBook.Lift mine = BOOK.byCarrier(player.getUUID());
        sweep(player, NexusLift::isLiftedItem, mine == null ? null : mine.id());
    }

    /** Removes every item of this kind a player has whose lift is not {@code keepId} (shared with {@link LecternLift}). */
    static void sweep(ServerPlayer player, java.util.function.Predicate<ItemStack> kind, String keepId) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (kind.test(stack) && !java.util.Objects.equals(liftIdOf(stack), keepId)) inventory.setItem(i, ItemStack.EMPTY);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (kind.test(stack) && !java.util.Objects.equals(liftIdOf(stack), keepId)) player.setItemSlot(slot, ItemStack.EMPTY);
        }
        if (player.containerMenu != null) {
            ItemStack carried = player.containerMenu.getCarried();
            if (kind.test(carried) && !java.util.Objects.equals(liftIdOf(carried), keepId)) player.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    /** How many items of this lift a player has, cursor included. */
    static int count(ServerPlayer player, String liftId) {
        int n = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (liftId.equals(liftIdOf(inventory.getItem(i)))) n++;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            // Armour and the off hand may also be inventory slots; counting one twice only says "yes, carried".
            if (liftId.equals(liftIdOf(player.getItemBySlot(slot)))) n++;
        }
        if (player.containerMenu != null && liftId.equals(liftIdOf(player.containerMenu.getCarried()))) n++;
        return n;
    }

    /** Takes every item of this lift off a player. */
    static void strip(ServerPlayer player, String liftId) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (liftId.equals(liftIdOf(inventory.getItem(i)))) inventory.setItem(i, ItemStack.EMPTY);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (liftId.equals(liftIdOf(player.getItemBySlot(slot)))) player.setItemSlot(slot, ItemStack.EMPTY);
        }
        if (player.containerMenu != null && liftId.equals(liftIdOf(player.containerMenu.getCarried()))) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }
}
