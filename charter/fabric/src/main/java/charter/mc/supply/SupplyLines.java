package charter.mc.supply;

import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.land.Claim;
import charter.land.Core;
import charter.land.LandGraph;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.Protection;
import charter.siege.CampSupply;
import charter.siege.SiegeEvents;
import charter.siege.Supply;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * M10: the supply lines in the world (PLAN.md §5.2 to §5.4). Every tick each online
 * player is observed; every {@code cartScanTicks} each chest or hopper cart and
 * chest boat near a site under siege is. Throwing a pearl or eating a chorus fruit
 * puts a player in transit, so it earns nothing. Ender chests cannot be opened in a
 * besieged keep or a cut off camp. Hopper unloads that drew on a cart pool are
 * recorded once per scan, not once per item.
 */
public final class SupplyLines {

    /** Ticks a pearl or chorus fruit keeps a player in transit (PLAN.md §5.2: 3 seconds). */
    public static final int TRANSIT_TICKS = 60;

    private static Crossings crossings;
    private static long tick;
    private static final Map<CoreId, Map<String, Integer>> HOPPER_TALLY = new LinkedHashMap<>();

    private SupplyLines() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SupplyLines::onTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (crossings != null) crossings.forget(playerCarrier(handler.player));
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (player instanceof ServerPlayer sp && (stack.is(Items.ENDER_PEARL) || stack.is(Items.CHORUS_FRUIT))) {
                crossings().inTransit(playerCarrier(sp), tick + TRANSIT_TICKS);
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || !CharterServer.running()) return InteractionResult.PASS;
            if (!enderChestBlocked(level, hit.getBlockPos())) return InteractionResult.PASS;
            sp.sendSystemMessage(Component.literal(Text.get("supply.ender_chest")).withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        });
        charter.mc.cmd.SupplyCommands.register();
    }

    /** The live tracker. Tests drive it directly. */
    public static Crossings crossings() {
        if (crossings == null) {
            crossings = new Crossings(() -> CharterServer.runtime().sim.supply(),
                    CharterServer.runtime().knobs.teleportDetectBlocks);
        }
        return crossings;
    }

    public static long tick() {
        return tick;
    }

    public static String playerCarrier(ServerPlayer player) {
        return "player:" + player.getUUID();
    }

    public static String cartCarrier(Entity cart) {
        return Supply.CART + cart.getUUID();
    }

    // ---- the tick ----

    private static void onTick(MinecraftServer server) {
        tick++;
        if (!CharterServer.running()) return;
        CharterRuntime rt = CharterServer.runtime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue;
            observe(player);
        }
        if (tick % Math.max(1, rt.knobs.cartScanTicks) == 0) {
            scanCarts(server, rt);
            flushHopperTally(rt);
        }
    }

    /** Observes one player now. The tick does this for everyone online. */
    public static Crossings.Result observe(ServerPlayer player) {
        return crossings().observe(playerCarrier(player), Protection.dimension(player.level()), player.getX(), player.getZ(),
                tick, () -> carried(player));
    }

    /** Observes one cart or chest boat now. The scan does this for those near active sites. */
    public static Crossings.Result observeCart(Entity cart) {
        return crossings().observe(cartCarrier(cart), Protection.dimension(cart.level()), cart.getX(), cart.getZ(),
                tick, () -> aboard(cart));
    }

    /** Every chest or hopper cart and chest boat near a keep under siege or a fighting camp. */
    static void scanCarts(MinecraftServer server, CharterRuntime rt) {
        Supply supply = rt.sim.supply();
        Set<String> seen = new HashSet<>();
        for (Core core : rt.state.cores()) {
            if (!supply.underSiege(core)) continue;
            ServerLevel level = levelOf(server, core.center.dimension());
            if (level == null) continue;
            for (Entity e : level.getEntitiesOfClass(Entity.class, box(rt, core), SupplyLines::isCart)) {
                if (seen.add(cartCarrier(e))) observeCart(e);
            }
        }
        crossings().forgetAllBut(seen, Supply.CART);
    }

    static boolean isCart(Entity e) {
        return e instanceof ContainerEntity && e instanceof Container && e.isAlive();
    }

    /** A keep's 5 by 5 chunks (ring and perimeter), or a camp's claims and their edge. */
    static AABB box(CharterRuntime rt, Core core) {
        int minX, maxX, minZ, maxZ;
        if (core.isKeep()) {
            minX = core.center.x() - 2;
            maxX = core.center.x() + 2;
            minZ = core.center.z() - 2;
            maxZ = core.center.z() + 2;
        } else {
            minX = maxX = core.center.x();
            minZ = maxZ = core.center.z();
            for (Claim c : rt.state.claims()) {
                if (!core.id.equals(c.core())) continue;
                minX = Math.min(minX, c.chunk.x());
                maxX = Math.max(maxX, c.chunk.x());
                minZ = Math.min(minZ, c.chunk.z());
                maxZ = Math.max(maxZ, c.chunk.z());
            }
            minX--;
            maxX++;
            minZ--;
            maxZ++;
        }
        return new AABB(minX * 16, -2048, minZ * 16, maxX * 16 + 16, 4096, maxZ * 16 + 16);
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (Protection.dimension(level).equals(dimension)) return level;
        }
        return null;
    }

    // ---- what a carrier holds ----

    /** Food a player carries: every inventory slot, offhand included, and what is inside shulker boxes and bundles. */
    public static Map<String, Integer> carried(ServerPlayer player) {
        Map<String, Integer> out = new TreeMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) add(out, inventory.getItem(i), 0);
        return out;
    }

    /** What a cart or chest boat holds, nested containers included. */
    public static Map<String, Integer> aboard(Entity cart) {
        Map<String, Integer> out = new TreeMap<>();
        if (cart instanceof Container box) {
            for (int i = 0; i < box.getContainerSize(); i++) add(out, box.getItem(i), 0);
        }
        return out;
    }

    private static void add(Map<String, Integer> out, ItemStack stack, int depth) {
        if (stack.isEmpty() || depth > 3) return;
        out.merge(CharterServer.itemId(stack), stack.getCount(), Integer::sum);
        ItemContainerContents shulker = stack.get(DataComponents.CONTAINER);
        if (shulker != null) shulker.nonEmptyItemCopyStream().forEach(s -> add(out, s, depth + 1));
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) bundle.itemCopyStream().forEach(s -> add(out, s, depth + 1));
    }

    // ---- ender chests ----

    /**
     * True if an ender chest here may not be opened: it stands in a besieged keep's
     * nine chunks or a cut off camp's claims, where it would be a supply line that
     * never crosses a side (PLAN.md §5.2).
     */
    public static boolean enderChestBlocked(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.ENDER_CHEST)) return false;
        CharterRuntime rt = CharterServer.runtime();
        Supply supply = rt.sim.supply();
        ChunkKey chunk = new ChunkKey(Protection.dimension(level), pos.getX() >> 4, pos.getZ() >> 4);
        return supply.siteAt(chunk).map(site -> site.isKeep()
                ? supply.underSiege(site)
                : rt.state.siege.camp(site.id).supply == CampSupply.CUT_OFF).orElse(false);
    }

    // ---- hopper unloads ----

    /** Counts an item a hopper unloaded against a site's cart pool; recorded in one event per scan. */
    public static void tallyHopper(CoreId site, String itemId, int n) {
        HOPPER_TALLY.computeIfAbsent(site, s -> new TreeMap<>()).merge(itemId, n, Integer::sum);
    }

    static void flushHopperTally(CharterRuntime rt) {
        if (HOPPER_TALLY.isEmpty()) return;
        List<HistoryEvent> events = new java.util.ArrayList<>();
        HOPPER_TALLY.forEach((site, items) -> {
            double nourishment = 0;
            for (var e : items.entrySet()) {
                var entry = rt.state.siege.food.get(e.getKey());
                if (entry != null) nourishment += e.getValue() * entry.nourishment();
            }
            events.add(new SiegeEvents.FoodDeposited(rt.clock.now(), rt.state.siegeClock.now(), site, Supply.HOPPER,
                    java.util.Collections.unmodifiableMap(new TreeMap<>(items)), nourishment, true));
        });
        HOPPER_TALLY.clear();
        rt.engine.recordSystem(events);
    }

    /** True if this chunk is a keep's perimeter chunk, for the supply page. */
    static boolean onPerimeter(Core keep, ChunkKey chunk) {
        return LandGraph.keepPerimeterChunks(keep.center).contains(chunk);
    }
}
