package pocketdungeons;

import java.util.List;

/**
 * M45 seam: the staging family's room templates.
 *
 * <p>The hidden staging room, and whatever stands with it.
 *
 * <p>Empty until M55 fills it. It exists now so that milestone can add rooms
 * without opening {@link RoomTemplateGenerator}, which every other template
 * milestone would be opening at the same time.
 */
final class StagingSpecs {

    private StagingSpecs() {}

    /** The staging family's templates. Empty until M55. */
    static List<RoomSpec> list() {
        return List.of();
    }
}
