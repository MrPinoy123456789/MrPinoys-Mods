package pocketdungeons;

import java.util.List;

/**
 * M45 seam: the knowledge family's room templates.
 *
 * <p>Rooms whose situation is knowing something: a code, a map, a pattern read
 * off one wall and used on another.
 *
 * <p>Empty until M52 fills it. It exists now so that milestone can add rooms
 * without opening {@link RoomTemplateGenerator}, which every other template
 * milestone would be opening at the same time.
 */
final class KnowledgeSpecs {

    private KnowledgeSpecs() {}

    /** The knowledge family's templates. Empty until M52. */
    static List<RoomSpec> list() {
        return List.of();
    }
}
