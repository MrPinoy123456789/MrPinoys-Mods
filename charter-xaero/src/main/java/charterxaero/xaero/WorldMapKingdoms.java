package charterxaero.xaero;

import charterxaero.CharterMapsClient;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.highlight.ChunkHighlighter;

import java.util.List;

/**
 * The kingdoms on Xaero's World Map: each held chunk tinted in its kingdom's colour, a stronger rim where the kingdom
 * ends, the keep's core chunk a little brighter, and the kingdom's name under the cursor. Xaero calls this from its map
 * threads; every method reads one immutable index.
 */
public final class WorldMapKingdoms extends ChunkHighlighter {

    public WorldMapKingdoms() {
        // Drawn over undiscovered map too: kingdoms are a political map, and their land is public on the server.
        super(true);
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> dimension, int regionX, int regionZ) {
        return CharterMapsClient.STORE.index().regionHash(Draw.dimension(dimension), regionX, regionZ);
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
        return CharterMapsClient.STORE.index().regionDrawn(Draw.dimension(dimension), regionX, regionZ);
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return CharterMapsClient.STORE.index().drawn(Draw.dimension(dimension), chunkX, chunkZ);
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return Draw.colours(CharterMapsClient.STORE.index(), Draw.dimension(dimension), chunkX, chunkZ, resultStore);
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return Draw.subtle(CharterMapsClient.STORE.index(), Draw.dimension(dimension), chunkX, chunkZ);
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return Draw.blunt(CharterMapsClient.STORE.index(), Draw.dimension(dimension), chunkX, chunkZ);
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> dimension, int blockX, int blockZ, int width) {
        Component line = Draw.blunt(CharterMapsClient.STORE.index(), Draw.dimension(dimension), blockX >> 4, blockZ >> 4);
        if (line != null) list.add(line);
    }
}
