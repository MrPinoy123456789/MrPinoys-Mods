package charter.mc.land;

import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Core;
import charter.mc.CharterMod;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.book.Cues;
import charter.mc.terminal.TerminalBook;
import charter.mc.terminal.Terminals;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Moving a keep's lectern terminal by hand, the way {@link NexusLift} moves the lodestone: the Lord (anyone with the
 * place-core power, and operators) mines the keep lectern and it becomes a bound item that names the keep; placed
 * anywhere inside that keep's core chunk it stands again there and is the keep's terminal again. Only the core chunk,
 * not all nine: the lectern is part of the keep's heart, with the vault and the granary. {@code /charter lectern move}
 * does the same by command.
 *
 * <p>The item is bound like the lifted Nexus ({@code BoundItems}): it cannot be dropped, stored or placed by anyone
 * else, and a copy anywhere but its carrier's inventory is removed. While it is carried the keep has no terminal, so
 * the empty spot neither sparks nor opens the menu. It goes back where it stood (facing away from the Nexus) when the
 * carrier dies, logs out for longer than {@code nexusLiftLogoutSeconds}, carries it longer than {@code nexusLiftMinutes}
 * or loses it, and at once when the kingdom is at war or the keep is open to attack. The lodestone's own rules and
 * knobs apply; the two lifts are separate, so one player may carry a lodestone and another the lectern.
 *
 * <p>Anyone else mining a keep lectern is refused: before this, a member who could build in the keep could break it.
 */
public final class LecternLift {

    /** The custom data kind of the bound item. */
    public static final String KIND = "lectern_lifted";
    public static final String FILE_NAME = "lectern_lifts.json";

    private static final LiftBook BOOK = new LiftBook(FILE_NAME);
    private static Path dir;
    private static long ticks;

    private LecternLift() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!CharterServer.running()) return;
            dir = CharterServer.charterDir(server);
            BOOK.load(dir, CharterMod.LOG::warn);
            // A lift still on file means the server stopped mid lift: every such lectern goes back where it stood.
            for (LiftBook.Lift lift : BOOK.all()) {
                CharterMod.LOG.info("Charter: putting back the lifted lectern of keep {} at {}, {}, {}", lift.keep(), lift.x(), lift.y(), lift.z());
                restore(server, lift, null);
            }
        });
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> save());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            save();
            dir = null;
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player && CharterServer.running()) {
                LiftBook.Lift lift = BOOK.byCarrier(player.getUUID());
                if (lift != null) restore(player.level().getServer(), lift, "lectern_lift.back_death");
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
        ServerTickEvents.END_SERVER_TICK.register(LecternLift::tick);
    }

    private static void save() {
        if (dir != null) BOOK.save(dir, CharterMod.LOG::warn);
    }

    // ---- questions ----

    /** Whether this keep's lectern is lifted right now. */
    public static boolean lifted(CoreId keep) {
        return BOOK.byKeep(keep.value().toString()) != null;
    }

    /** Whether this stack is a lifted keep lectern. */
    public static boolean isLiftedItem(ItemStack stack) {
        return KIND.equals(LandItems.kindOf(stack));
    }

    /** The lift this player carries, or null (for tests). */
    public static LiftBook.Lift carriedBy(UUID player) {
        return BOOK.byCarrier(player);
    }

    /** The keep a block's terminal belongs to when it is a keep's lectern, or null (any other block or terminal). */
    public static Core keepOf(Level level, BlockPos pos) {
        if (!CharterServer.running() || !level.getBlockState(pos).is(Blocks.LECTERN)) return null;
        TerminalBook.Terminal terminal = Terminals.book().at(Terminals.spot(level, pos));
        if (terminal == null || terminal.core() == null) return null;
        try {
            return CharterServer.runtime().state.core(new CoreId(UUID.fromString(terminal.core()))).filter(Core::isKeep).orElse(null);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    /**
     * The block a keep lectern faces when Charter sets it down without a player to face: away from the Nexus, as
     * {@link Terminals#placeBeside} sets it, so a reader stands with the lodestone behind the lectern.
     */
    public static Direction facingAwayFromNexus(CharterRuntime rt, CoreId keep, BlockPos at) {
        Fixtures.Fixture nexus = Fixtures.of(rt.state).first(keep, Fixtures.Kind.NEXUS);
        if (nexus == null) return Direction.NORTH;
        int dx = at.getX() - nexus.x();
        int dz = at.getZ() - nexus.z();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ---- lifting ----

    /**
     * A break of a keep's lectern. The caller always cancels the vanilla break (so no plain lectern drops); this lifts
     * it into the bound item when the player may, and otherwise says why not.
     */
    public static void tryLift(ServerPlayer player, Level level, BlockPos pos, Core core) {
        CharterRuntime rt = CharterServer.runtime();
        if (!(level instanceof ServerLevel)) return;
        boolean op = SpawnRules.isOp(player);
        boolean npc = rt.state.org(core.owner).map(o -> o.npc).orElse(true);
        if (npc || !NexusLift.mayLift(rt, player, core, op) || (SpawnRules.inSpawn(level, pos) && !op)) {
            refuse(player, "lectern_lift.unbreakable");
            return;
        }
        if (NexusLift.exposed(rt, core)) {
            refuse(player, "lectern_lift.at_war");
            return;
        }
        if (BOOK.byCarrier(player.getUUID()) != null) {
            refuse(player, "lectern_lift.one_at_a_time");
            return;
        }
        if (player.getInventory().getFreeSlot() < 0) {
            refuse(player, "lectern_lift.no_room");
            return;
        }
        OrgId root = rt.state.root(core.owner);
        LiftBook.Lift lift = new LiftBook.Lift(UUID.randomUUID().toString(), core.id.value().toString(), root.value().toString(),
                player.getUUID(), Protection.dimension(level), pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis(), 0);
        // The spot stops being a terminal at once, so nothing opens the menu at an empty block.
        Terminals.remove(Terminals.spot(level, pos));
        level.levelEvent(2001, pos, Block.getId(level.getBlockState(pos)));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        BOOK.put(lift);
        save();
        ItemStack item = item(lift, rt.describe.org(root));
        int selected = player.getInventory().getSelectedSlot();
        if (player.getInventory().getItem(selected).isEmpty()) player.getInventory().setItem(selected, item);
        else player.getInventory().add(item);
        player.sendSystemMessage(Component.literal(Text.get("lectern_lift.lifted", "minutes", rt.knobs.nexusLiftMinutes)).withStyle(ChatFormatting.AQUA));
        Cues.actionBar(player, Text.get("lectern_lift.lifted_short"), ChatFormatting.AQUA);
    }

    private static void refuse(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.literal(Text.get(key)).withStyle(ChatFormatting.RED), true);
    }

    /** The bound item for a lift: a glinting lectern that names the kingdom, one to a stack. */
    static ItemStack item(LiftBook.Lift lift, String orgName) {
        ItemStack stack = new ItemStack(Items.LECTERN);
        CompoundTag tag = new CompoundTag();
        tag.putString(LandItems.KEY, KIND);
        tag.putString(NexusLift.LIFT_KEY, lift.id());
        tag.putString(NexusLift.KEEP_KEY, lift.keep());
        tag.putString(NexusLift.CARRIER_KEY, lift.carrier().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(Text.get("lectern_lift.item.name", "org", orgName))
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                (Component) Component.literal(Text.get("lectern_lift.item.lore", "org", orgName)).withStyle(ChatFormatting.GRAY),
                Component.literal(Text.get("lectern_lift.item.lore2")).withStyle(ChatFormatting.GRAY))));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    // ---- setting it down ----

    /**
     * The placement of a lifted lectern, from {@code Placement.place} ahead of the claim rules. Inside the keep's core
     * chunk the lectern stands where vanilla put it (facing the player) and is the keep's terminal again; anywhere else,
     * or for a stale item or someone else's, the placement is refused and the item stays.
     */
    public static InteractionResult place(BlockPlaceContext ctx, ServerPlayer player, Supplier<InteractionResult> original) {
        CharterRuntime rt = CharterServer.runtime();
        ItemStack held = ctx.getItemInHand();
        LiftBook.Lift lift = BOOK.byId(NexusLift.liftIdOf(held));
        if (lift == null || !lift.carrier().equals(player.getUUID())) {
            held.setCount(0);
            refuse(player, "lectern_lift.stale");
            return InteractionResult.FAIL;
        }
        Level level = ctx.getLevel();
        BlockPos target = ctx.getClickedPos();
        Core keep = rt.state.core(new CoreId(UUID.fromString(lift.keep()))).orElse(null);
        if (keep == null || !Placement.chunkOf(level, target).equals(keep.center)) {
            String org = rt.state.org(rt.state.root(new OrgId(UUID.fromString(lift.org())))).map(o -> o.name()).orElse("?");
            refuse(player, "lectern_lift.outside");
            player.sendSystemMessage(Component.literal(Text.get("lectern_lift.outside_hint", "org", org)).withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }
        InteractionResult result = original.get();
        if (!result.consumesAction()) return result;
        Terminals.add(Terminals.spot(level, target), lift.keep());
        BOOK.remove(lift.keep());
        save();
        NexusLift.strip(player, lift.id());
        String line = Text.get("lectern_lift.placed");
        player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GREEN));
        Cues.actionBar(player, line, ChatFormatting.GREEN);
        return result;
    }

    // ---- putting it back ----

    /**
     * Ends a lift by putting the lectern back where it stood (lifted clear of anything built there since, facing away
     * from the Nexus) as the keep's terminal, takes the item from its carrier when online, and tells them why with
     * {@code key} (null says nothing).
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
                level.setBlockAndUpdate(pos, Blocks.LECTERN.defaultBlockState()
                        .setValue(LecternBlock.FACING, facingAwayFromNexus(rt, keep, pos)));
                Terminals.add(Terminals.spot(level, pos), lift.keep());
            }
        }
        ServerPlayer carrier = server == null ? null : server.getPlayerList().getPlayer(lift.carrier());
        if (carrier != null) {
            NexusLift.strip(carrier, lift.id());
            if (key != null) {
                carrier.sendSystemMessage(Component.literal(Text.get(key)).withStyle(ChatFormatting.GOLD));
                Cues.actionBar(carrier, Text.get("lectern_lift.back_short"), ChatFormatting.GOLD);
            }
        }
    }

    /**
     * Ends a lift without putting anything back, for a keep that moved (it gets a new lectern beside its new Nexus) or
     * an operator setting the lectern down by command. The carrier loses the item.
     */
    public static void cancel(MinecraftServer server, CoreId keep) {
        LiftBook.Lift lift = BOOK.remove(keep.value().toString());
        if (lift == null) return;
        save();
        ServerPlayer carrier = server == null ? null : server.getPlayerList().getPlayer(lift.carrier());
        if (carrier != null) NexusLift.strip(carrier, lift.id());
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
        if (ticks % NexusLift.CHECK_EVERY_TICKS != 0) return;
        check(server);
    }

    /** Ends the lifts that should end and sweeps stale items. Public so a test can run it at once. */
    public static void check(MinecraftServer server) {
        CharterRuntime rt = CharterServer.runtime();
        long now = System.currentTimeMillis();
        for (LiftBook.Lift lift : BOOK.all()) {
            Core keep = rt.state.core(new CoreId(UUID.fromString(lift.keep()))).orElse(null);
            ServerPlayer carrier = server.getPlayerList().getPlayer(lift.carrier());
            boolean carries = carrier != null && NexusLift.count(carrier, lift.id()) > 0;
            LiftBook.End end = LiftBook.end(lift, now, rt.knobs.nexusLiftMinutes * 60_000L, rt.knobs.nexusLiftLogoutSeconds * 1000L,
                    keep != null, keep != null && NexusLift.exposed(rt, keep), carrier != null, carries);
            if (end == null) continue;
            if (end == LiftBook.End.GONE) cancel(server, new CoreId(UUID.fromString(lift.keep())));
            else restore(server, lift, "lectern_lift.back_" + end.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sweep(player);
    }

    /** Removes every lifted lectern a player has that is not the one they are carrying for a lift under way. */
    static void sweep(ServerPlayer player) {
        LiftBook.Lift mine = BOOK.byCarrier(player.getUUID());
        NexusLift.sweep(player, LecternLift::isLiftedItem, mine == null ? null : mine.id());
    }
}
