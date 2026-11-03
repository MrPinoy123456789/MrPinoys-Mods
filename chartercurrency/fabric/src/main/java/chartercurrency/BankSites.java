package chartercurrency;

import chartercurrency.core.Banks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Banks in the world ({@code banks.json}): {@link Banks} plus saving. Keys are the
 * same form as {@link MintSites}: {@code namespace:dimension x y z}.
 */
final class BankSites {

    /** The shape of {@code banks.json}: site to kingdom id, "" for a Bank that is nobody's. */
    public static final class File {
        public Map<String, String> banks = new LinkedHashMap<>();

        public File() {}
    }

    private final Banks banks;
    private final Runnable onChange;

    BankSites(File initial, Runnable onChange) {
        this.banks = new Banks(initial == null ? null : initial.banks);
        this.onChange = onChange;
    }

    static String key(Level level, BlockPos pos) {
        return MintSites.key(level, pos);
    }

    static String dimension(Level level) {
        return level.dimension().identifier().toString();
    }

    synchronized Banks.Placement check(boolean operator, Optional<String> playerKingdom, boolean lord,
                                       boolean landKnown, Optional<String> landOwner) {
        return banks.check(operator, playerKingdom, lord, landKnown, landOwner);
    }

    synchronized boolean isBank(Level level, BlockPos pos) {
        return banks.isBank(key(level, pos));
    }

    synchronized boolean add(Level level, BlockPos pos, Optional<String> kingdom) {
        boolean added = banks.add(key(level, pos), kingdom);
        if (added) onChange.run();
        return added;
    }

    synchronized boolean remove(String site) {
        boolean removed = banks.remove(site);
        if (removed) onChange.run();
        return removed;
    }

    synchronized Optional<String> kingdomOf(Level level, BlockPos pos) {
        return banks.kingdomOf(key(level, pos));
    }

    synchronized Optional<String> siteOf(String kingdom) {
        return banks.siteOf(kingdom);
    }

    /** Whether a Bank stands in the chunk of this block, which makes a smithing table there a Mint. */
    synchronized boolean inChunkOf(Level level, BlockPos pos) {
        return banks.inChunk(dimension(level), pos.getX() >> 4, pos.getZ() >> 4);
    }

    synchronized Map<String, String> snapshot() {
        return banks.snapshot();
    }

    synchronized List<String> list() {
        return banks.siteList();
    }

    /** JSON for the state file. */
    synchronized String json() {
        File f = new File();
        f.banks = banks.snapshot();
        return JsonStateFile.toJson(f);
    }
}
