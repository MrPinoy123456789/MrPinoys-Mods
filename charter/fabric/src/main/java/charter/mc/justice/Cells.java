package charter.mc.justice;

import charter.contract.Justice;
import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Marking, removing and finding cells (PLAN.md §7.4). A faction marks a cell by
 * standing in it and naming it; the space around the marker is flood filled
 * ({@link CellFill}) and must close in by itself, inside the faction's keep, under
 * {@link #MAX_BLOCKS}. Marking needs the judge power over the marker's organization.
 */
public final class Cells {

    /** The largest space a cell may be; a cell is a room, not a hall. */
    public static final int MAX_BLOCKS = 150;
    public static final int MAX_NAME = 24;

    private Cells() {
    }

    /** What marking or removing said: a line for the player and whether it worked. */
    public record Done(boolean ok, String text) {
    }

    private static Done no(String key, Object... pairs) {
        return new Done(false, Text.get(key, pairs));
    }

    public static CellBook book() {
        return CellBook.of(CharterServer.runtime().state);
    }

    /** Marks the space the player stands in as a cell of the keep they stand in. */
    public static Done mark(ServerPlayer player, String rawName) {
        CharterRuntime rt = CharterServer.runtime();
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty() || name.length() > MAX_NAME || name.contains("|")) return no("cell.bad_name", "max", MAX_NAME);
        PlayerId me = new PlayerId(player.getUUID());
        var membership = rt.state.membership(me);
        if (membership.isEmpty()) return no("page.no_org");
        OrgId org = membership.get().org();
        if (!new AuthorityResolver(rt.state, rt.clock.now()).resolve(new Who.AsPlayer(me), org, Power.JUDGE, ScopeNeed.NONE, false).ok()) {
            return no("cell.no_power");
        }
        ServerLevel level = player.level();
        BlockPos feet = player.blockPosition();
        Claim here = Protection.claimAt(level, feet);
        Core keep = here == null || here.core() == null ? null : rt.state.core(here.core()).orElse(null);
        if (keep == null || !keep.isKeep() || !rt.state.root(here.owner).equals(rt.state.root(org))) return no("cell.not_your_keep");
        return markAt(level, keep, feet, name);
    }

    /**
     * Marks the enclosed space around {@code feet} as a cell of {@code keep}, with no
     * check of who asks: the scenario runner builds a cell and marks it this way.
     */
    public static Done markAt(ServerLevel level, Core keep, BlockPos feet, String name) {
        CharterRuntime rt = CharterServer.runtime();
        if (book().get(keep.id, name) != null) return no("cell.exists", "name", name);
        String dimension = Protection.dimension(level);
        CellFill.Result fill = CellFill.fill(new LevelSpace(level, keep.id), new CellBook.Pos(feet.getX(), feet.getY(), feet.getZ()), MAX_BLOCKS);
        if (!fill.ok()) return no(fill.refusal(), "max", MAX_BLOCKS);
        book().add(new CellBook.Cell(keep.id, name, dimension, new CellBook.Pos(feet.getX(), feet.getY(), feet.getZ()), fill.blocks()));
        return new Done(true, Text.get("cell.marked", "name", name, "count", fill.blocks().size(), "site", rt.describe.core(keep.id)));
    }

    /** Removes a cell of the player's faction, unless someone is held in it. */
    public static Done remove(ServerPlayer player, String name) {
        CharterRuntime rt = CharterServer.runtime();
        PlayerId me = new PlayerId(player.getUUID());
        var membership = rt.state.membership(me);
        if (membership.isEmpty()) return no("page.no_org");
        OrgId org = membership.get().org();
        if (!new AuthorityResolver(rt.state, rt.clock.now()).resolve(new Who.AsPlayer(me), org, Power.JUDGE, ScopeNeed.NONE, false).ok()) {
            return no("cell.no_power");
        }
        for (CellBook.Cell cell : ownCells(rt, org)) {
            if (!cell.name.equalsIgnoreCase(name)) continue;
            if (occupied(rt, cell)) return no("cell.occupied", "name", cell.name);
            book().remove(cell.keep, cell.name);
            return new Done(true, Text.get("cell.removed", "name", cell.name));
        }
        return no("cell.unknown", "name", name);
    }

    /** Every cell in a keep owned by this organization's faction. */
    public static java.util.List<CellBook.Cell> ownCells(CharterRuntime rt, OrgId org) {
        OrgId root = rt.state.root(org);
        java.util.List<CellBook.Cell> out = new java.util.ArrayList<>();
        for (CellBook.Cell cell : book().all()) {
            Core keep = rt.state.core(cell.keep).orElse(null);
            if (keep != null && rt.state.root(keep.owner).equals(root)) out.add(cell);
        }
        return out;
    }

    /** True if a live confinement holds someone in this cell now. */
    public static boolean occupied(CharterRuntime rt, CellBook.Cell cell) {
        long now = rt.clock.now();
        for (var c : rt.state.contracts.live()) {
            var held = c.term(charter.contract.Term.Confinement.class);
            if (held == null || c.siegeTimed || !c.subject.isPlayer()) continue;
            if (cell.keep.equals(held.keep()) && cell.name.equalsIgnoreCase(held.cell())
                    && Justice.holding(rt.state, rt.knobs, c.subject.player(), now).isPresent()) return true;
        }
        return false;
    }

    /** The world as the flood fill sees it: bodies pass where there is no collision; the keep is the faction's claimed chunks. */
    private record LevelSpace(Level level, CoreId keep) implements CellFill.Space {
        @Override
        public boolean passable(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) return true;
            return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        }

        @Override
        public boolean inKeep(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) return false;
            Claim claim = Protection.claimAt(level, pos);
            return claim != null && keep.equals(claim.core());
        }
    }

    /** The lower case name used to match cells typed by players. */
    public static String norm(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }
}
