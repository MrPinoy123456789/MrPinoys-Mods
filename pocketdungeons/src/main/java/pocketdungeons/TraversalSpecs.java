package pocketdungeons;

import java.util.List;

/**
 * M45 seam: the traversal family's room templates.
 *
 * <p>Rooms whose situation is getting across: gaps, water, ice, a drop with no
 * obvious way down.
 *
 * <p>Empty until M50 fills it. It exists now so that milestone can add rooms
 * without opening {@link RoomTemplateGenerator}, which every other template
 * milestone would be opening at the same time.
 */
final class TraversalSpecs {

    private TraversalSpecs() {}

    /** The traversal family's templates. Empty until M50. */
    static List<RoomSpec> list() {
        return List.of();
    }
}
