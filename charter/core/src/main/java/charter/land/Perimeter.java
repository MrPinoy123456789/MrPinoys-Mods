package charter.land;

import charter.id.ChunkKey;
import charter.id.CoreId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The chunks around a core where supplies come in (DESIGN.md §10.4, §10.10), each
 * open or closed. Whether the core is supplied, besieged or encircled is the siege
 * simulation's call; this is only the geometry and who holds each chunk.
 */
public record Perimeter(CoreId core, List<ChunkKey> chunks, Set<ChunkKey> closed) {

    public Perimeter {
        chunks = List.copyOf(chunks);
        closed = Set.copyOf(closed);
    }

    public boolean isClosed(ChunkKey chunk) {
        return closed.contains(chunk);
    }

    public List<ChunkKey> open() {
        List<ChunkKey> out = new ArrayList<>();
        for (ChunkKey chunk : chunks) {
            if (!closed.contains(chunk)) out.add(chunk);
        }
        return out;
    }

    /** True when every side is closed: an encircled keep, a cut off camp. */
    public boolean fullyClosed() {
        return !chunks.isEmpty() && closed.size() == chunks.size();
    }
}
