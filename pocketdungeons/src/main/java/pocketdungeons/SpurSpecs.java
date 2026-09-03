package pocketdungeons;

import java.util.List;

/**
 * M45 seam: the spur family's room templates.
 *
 * <p>Optional rooms hung off a branch: worth the detour, never on the critical
 * path, and never the reason a run cannot finish.
 *
 * <p>Empty until M53 fills it. It exists now so that milestone can add rooms
 * without opening {@link RoomTemplateGenerator}, which every other template
 * milestone would be opening at the same time.
 */
final class SpurSpecs {

    private SpurSpecs() {}

    /** The spur family's templates. Empty until M53. */
    static List<RoomSpec> list() {
        return List.of();
    }
}
