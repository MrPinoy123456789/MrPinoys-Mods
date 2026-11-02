package charterxaero.xaero;

import charterxaero.CharterMapsClient;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.minimap.highlight.ChunkHighlighter;
import xaero.hud.minimap.info.render.compile.InfoDisplayCompiler;

/** The kingdoms on Xaero's Minimap: the same colours and words as on the World Map. Called from the Minimap's threads. */
public final class MinimapKingdoms extends ChunkHighlighter {

    public MinimapKingdoms() {
        super(true);
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
    public void addChunkHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> dimension, int chunkX, int chunkZ, int width) {
        Component line = Draw.blunt(CharterMapsClient.STORE.index(), Draw.dimension(dimension), chunkX, chunkZ);
        if (line != null) compiler.addLine(line);
    }
}
