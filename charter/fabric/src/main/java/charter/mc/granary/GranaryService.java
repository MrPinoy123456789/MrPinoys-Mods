package charter.mc.granary;

import charter.food.FoodTable;
import charter.food.Provisions;
import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.granary.GranaryBook.Pos;
import charter.mc.granary.GranaryBook.Site;
import charter.siege.SiegeRules;
import charter.siege.Store;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Keeps core's {@link Store#provisions} and the real chests in step (PLAN.md §4.5, §6).
 *
 * <p>Core decides what is eaten and what spoils. This class never computes that: it
 * reads the chests into the mirror before a sim step, and after the step takes from
 * the real items exactly what core removed from the mirror. A site whose chunk is not
 * loaded cannot be touched, so what core removed is queued on the site and applied
 * when the chunk loads, before anyone can open the chest.
 */
public final class GranaryService {

    /** What a player may do at a store. */
    public enum Access {
        NONE,
        /** May open it and put food in, not take it out. */
        DEPOSIT,
        /** May open it, put food in and take it out. */
        FULL
    }

    private final MinecraftServer server;
    private final CharterRuntime rt;

    /**
     * Test seam: sites listed here are treated as if their chunk were unloaded, because
     * a gametest cannot unload the world it runs in. Empty in a real server.
     */
    public final Set<CoreId> pretendUnloaded = new HashSet<>();

    public GranaryService(MinecraftServer server, CharterRuntime rt) {
        this.server = server;
        this.rt = rt;
    }

    public CharterRuntime runtime() {
        return rt;
    }

    public MinecraftServer server() {
        return server;
    }

    public GranaryBook book() {
        return GranaryBook.of(rt.state);
    }

    public FoodTable food() {
        return rt.sim.food();
    }

    // ---- the world ----

    public ServerLevel level(String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    }

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public boolean loaded(Site site) {
        if (pretendUnloaded.contains(site.core)) return false;
        ServerLevel level = level(site.dimension);
        if (level == null || site.containers.isEmpty()) return false;
        Pos first = site.containers.getFirst();
        return level.getChunkSource().getChunkNow(first.chunkX(), first.chunkZ()) != null;
    }

    /** The container at a position, or null when there is none (or the chunk is not there). */
    public static Container containerAt(ServerLevel level, LevelChunk chunk, Pos pos) {
        BlockPos at = new BlockPos(pos.x(), pos.y(), pos.z());
        BlockEntity be = chunk != null ? chunk.getBlockEntity(at) : level.getBlockEntity(at);
        if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) return (Container) be;
        return null;
    }

    /** The site's containers that exist right now, in link order. */
    public List<Container> containers(Site site, LevelChunk chunk) {
        List<Container> out = new ArrayList<>();
        ServerLevel level = level(site.dimension);
        if (level == null) return out;
        for (Pos pos : site.containers) {
            Container c = containerAt(level, chunk, pos);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** What the containers hold of foods the table knows. */
    public Provisions read(List<Container> boxes) {
        Provisions out = new Provisions();
        FoodTable table = food();
        for (Container box : boxes) {
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack stack = box.getItem(i);
                if (stack.isEmpty()) continue;
                String id = itemId(stack);
                if (table.isFood(id)) out.add(id, stack.getCount());
            }
        }
        return out;
    }

    /** Removes up to the wanted counts from the containers and returns what actually came out. */
    public static Map<String, Integer> take(List<Container> boxes, Map<String, Integer> want) {
        Map<String, Integer> taken = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : want.entrySet()) {
            int left = entry.getValue();
            for (Container box : boxes) {
                for (int i = 0; i < box.getContainerSize() && left > 0; i++) {
                    ItemStack stack = box.getItem(i);
                    if (stack.isEmpty() || !itemId(stack).equals(entry.getKey())) continue;
                    int n = Math.min(left, stack.getCount());
                    box.removeItem(i, n);
                    left -= n;
                    taken.merge(entry.getKey(), n, Integer::sum);
                }
            }
        }
        return taken;
    }

    // ---- the mirror ----

    /** Reads a loaded site's containers into core's mirror, replacing what was there. */
    public void mirror(Site site, LevelChunk chunk) {
        Store store = rt.state.siege.store(site.core);
        store.provisions = read(containers(site, chunk));
    }

    /**
     * A site's chunk has loaded (or the chest is about to be used): apply the removals
     * the sim queued while it was away to the real items, then read what is left.
     * Nothing can have been added meanwhile, so the mirror cannot have drifted.
     */
    public void reconcile(Site site, LevelChunk chunk) {
        if (!site.queued.isEmpty()) {
            take(containers(site, chunk), site.queued);
            site.queued.clear();
        }
        mirror(site, chunk);
    }

    /** The chunk-load hook: reconciles every site standing in this chunk. */
    public void onChunkLoad(String dimension, int chunkX, int chunkZ, LevelChunk chunk) {
        for (Site site : book().sites()) {
            if (!site.dimension.equals(dimension) || site.containers.isEmpty()) continue;
            Pos first = site.containers.getFirst();
            if (first.chunkX() == chunkX && first.chunkZ() == chunkZ) reconcile(site, chunk);
        }
    }

    /**
     * The same, on the tick after a chunk loaded, never inside the load itself (see
     * {@link Granaries}). A site whose chunk has unloaded again meanwhile stays queued.
     */
    public void onChunkLoaded(String dimension, int chunkX, int chunkZ) {
        for (Site site : book().sites()) {
            if (!site.dimension.equals(dimension) || site.containers.isEmpty()) continue;
            Pos first = site.containers.getFirst();
            if (first.chunkX() == chunkX && first.chunkZ() == chunkZ && loaded(site)) reconcile(site, null);
        }
    }

    /**
     * Runs one sim step (or an admin advance) with the mirror honest on both sides:
     * loaded chests are read in before, and afterwards whatever core removed (eaten,
     * spoiled) is taken from the real items, or queued when the chunk is not loaded.
     */
    public void around(Runnable step) {
        Map<CoreId, Provisions> before = new java.util.LinkedHashMap<>();
        for (Site site : book().sites()) {
            if (rt.state.core(site.core).isEmpty()) continue;
            if (loaded(site)) {
                dropMissing(site);
                if (book().site(site.core) == null) continue;
                reconcile(site, null);
            }
            before.put(site.core, rt.state.siege.store(site.core).provisions.copy());
        }
        step.run();
        for (Site site : book().sites()) {
            if (rt.state.core(site.core).isEmpty()) {
                book().remove(site.core);
                continue;
            }
            Provisions was = before.get(site.core);
            if (was == null) continue;
            Provisions now = rt.state.siege.store(site.core).provisions;
            Map<String, Integer> removed = new TreeMap<>();
            was.counts().forEach((id, n) -> {
                int gone = n - now.count(id);
                if (gone > 0) removed.put(id, gone);
            });
            if (removed.isEmpty()) continue;
            if (loaded(site)) {
                take(containers(site, null), removed);
                mirror(site, null);
            } else {
                removed.forEach((id, n) -> site.queued.merge(id, n, Integer::sum));
            }
        }
    }

    /** Forgets containers whose block is gone (broken, replaced). A site with none left ends, and its food with it. */
    private void dropMissing(Site site) {
        ServerLevel level = level(site.dimension);
        if (level == null) return;
        site.containers.removeIf(pos -> containerAt(level, null, pos) == null);
        if (site.containers.isEmpty()) {
            book().remove(site.core);
            rt.state.siege.store(site.core).provisions = new Provisions();
        }
    }

    // ---- who may do what ----

    public Optional<Core> coreOf(Site site) {
        return rt.state.core(site.core);
    }

    /** True once the keep has fallen (or the core is gone): the store is loot. */
    public boolean fallen(Core core) {
        return rt.state.siege.falls.containsKey(core.id);
    }

    public Access access(PlayerId player, Core core) {
        if (fallen(core)) return Access.FULL;
        SiegeRules rules = rt.sim.rules();
        var holder = core.isKeep() ? rules.holder(core) : rules.faction(core.owner);
        boolean occupied = rt.state.siege.occupations.containsKey(core.id);
        boolean member = rt.state.membership(player).map(m -> rt.state.root(m.org()).equals(holder)).orElse(false);
        if (member && occupied) return Access.FULL;
        AuthorityResolver resolver = new AuthorityResolver(rt.state, rt.clock.now());
        if (!occupied && resolver.resolve(new Who.AsPlayer(player), core.owner, Power.GRANARY_WITHDRAW, ScopeNeed.NONE, false).ok()) {
            return Access.FULL;
        }
        if (member) return Access.DEPOSIT;
        if (!occupied && resolver.resolve(new Who.AsPlayer(player), core.owner, Power.GRANARY_DEPOSIT, ScopeNeed.NONE, false).ok()) {
            return Access.DEPOSIT;
        }
        return Access.NONE;
    }

    // ---- finding and registering ----

    /** The keep whose core chunk this is, or the camp whose banner chunk it is. */
    public Optional<Core> coreAtChunk(ChunkKey chunk) {
        for (Core core : rt.state.cores()) {
            if (core.center.equals(chunk)) return Optional.of(core);
        }
        return Optional.empty();
    }

    /** Why a container at this position cannot join, or null when it can (adding it if {@code commit}). */
    public String link(Core core, ServerLevel level, BlockPos at, boolean needBanner, boolean commit) {
        String dimension = level.dimension().identifier().toString();
        Pos pos = new Pos(at.getX(), at.getY(), at.getZ());
        if (book().at(dimension, pos) != null) return "granary.already";
        if (!core.center.dimension().equals(dimension)
                || core.center.x() != pos.chunkX() || core.center.z() != pos.chunkZ()) return "granary.wrong_chunk";
        if (containerAt(level, null, pos) == null) return "granary.not_a_container";
        Site site = book().site(core.id);
        int limit = core.isKeep() ? rt.knobs.granaryMaxChests : 1;
        if (site != null) {
            if (site.containers.size() >= limit) return "granary.full";
            if (core.isKeep() && site.containers.stream().noneMatch(p -> touching(p, pos))) return "granary.not_touching";
            if (!core.isKeep()) return "granary.already";
        } else if (!core.isKeep() && needBanner && !Banners.near(level, at)) {
            return "granary.no_banner";
        }
        if (commit) {
            if (site == null) site = book().create(core.id, dimension);
            site.containers.add(pos);
            reconcile(site, null);
        }
        return null;
    }

    private static boolean touching(Pos a, Pos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()) == 1;
    }
}
