package chartercurrency;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * The bound Mint blocks ({@code mints.json}). A site is a dimension and a block
 * position, stored as {@code namespace:dimension x y z}.
 */
final class MintSites {

    /** The shape of {@code mints.json}. */
    public static final class File {
        public List<String> sites = new ArrayList<>();

        public File() {}
    }

    private final File file;
    private final Runnable onChange;

    MintSites(File initial, Runnable onChange) {
        this.file = initial == null ? new File() : initial;
        if (this.file.sites == null) this.file.sites = new ArrayList<>();
        this.onChange = onChange;
    }

    static String key(Level level, BlockPos pos) {
        return level.dimension().identifier() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    synchronized boolean isBound(Level level, BlockPos pos) {
        return file.sites.contains(key(level, pos));
    }

    synchronized boolean bind(Level level, BlockPos pos) {
        String key = key(level, pos);
        if (file.sites.contains(key)) return false;
        file.sites.add(key);
        onChange.run();
        return true;
    }

    synchronized boolean unbind(Level level, BlockPos pos) {
        boolean removed = file.sites.remove(key(level, pos));
        if (removed) onChange.run();
        return removed;
    }

    synchronized List<String> list() {
        return List.copyOf(file.sites);
    }

    /** JSON for the state file; call through {@link #json()}. */
    synchronized String json() {
        return JsonStateFile.toJson(file);
    }
}
