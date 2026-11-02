package charterxaero.xaero;

import charterxaero.model.Colours;
import charterxaero.model.KingdomIndex;
import charterxaero.model.Tooltips;
import charterxaero.wire.MapModel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * What both highlighters draw and say for a chunk. Names no Xaero class, so either highlighter loads without the other
 * mod being present.
 */
final class Draw {

    private Draw() {
    }

    static String dimension(ResourceKey<Level> key) {
        return key.identifier().toString();
    }

    /** The five colours of a chunk, or null when nothing is drawn on it. */
    static int[] colours(KingdomIndex index, String dimension, int chunkX, int chunkZ, int[] into) {
        MapModel.Marker marker = index.markerAt(dimension, chunkX, chunkZ);
        if (marker != null) return Colours.markerColours(marker.colour(), into);
        KingdomIndex.Cell cell = index.at(dimension, chunkX, chunkZ);
        if (cell == null) return null;
        int rgb = index.kingdoms().get(cell.kingdom()).colour();
        return Colours.chunkColours(rgb, cell.core(), index.sides(dimension, chunkX, chunkZ), into);
    }

    /** The short line for a chunk (the kingdom's name, or the marker's label), or null. */
    static Component subtle(KingdomIndex index, String dimension, int chunkX, int chunkZ) {
        MapModel.Marker marker = index.markerAt(dimension, chunkX, chunkZ);
        if (marker != null) return coloured(Tooltips.marker(marker), marker.colour());
        MapModel.Kingdom kingdom = index.kingdomAt(dimension, chunkX, chunkZ);
        return kingdom == null ? null : coloured(Tooltips.subtle(kingdom), kingdom.colour());
    }

    /** The full line for a chunk ("Keep of Azrak (Dwarf)"), or null. */
    static Component blunt(KingdomIndex index, String dimension, int chunkX, int chunkZ) {
        MapModel.Marker marker = index.markerAt(dimension, chunkX, chunkZ);
        if (marker != null) return coloured(Tooltips.marker(marker), marker.colour());
        KingdomIndex.Cell cell = index.at(dimension, chunkX, chunkZ);
        if (cell == null) return null;
        MapModel.Kingdom kingdom = index.kingdoms().get(cell.kingdom());
        return coloured(Tooltips.blunt(kingdom, cell), kingdom.colour());
    }

    static Component coloured(String text, int rgb) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }
}
