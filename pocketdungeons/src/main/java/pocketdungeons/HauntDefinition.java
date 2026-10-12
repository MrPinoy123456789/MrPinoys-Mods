package pocketdungeons;

import java.util.Set;

/**
 * One data driven haunt (docs/haunts-draft-2026-10-11.md): a floor wide creature with a rule the player can learn.
 * A definition says who it is, where it walks, which of the closed {@link Behaviour} kinds drives it, what body
 * it wears and how it announces itself. The numbers a tuner moves (pressure, look cone, damage, retreat) are
 * {@link PocketDungeonsConfig} knobs shared by every haunt.
 *
 * @param id           the namespaced resource id, {@code pocketdungeons:the_counted}
 * @param label        the haunt name, also the custom name its body wears
 * @param dungeons     the dungeon ids that carry it (for example {@code ossuary})
 * @param minCompass   the lowest keystone level (compass) a run needs for the haunt to walk
 * @param behaviour    the rule that moves it
 * @param body         the vanilla mob type id of its body
 * @param tellParticle the particle id sent from the server around the body
 * @param tellSound    the sound id played at the body
 * @param ruleLine     the one chat line that states the rule, written at the first real tell
 * @param diary        the diary entry id that tells its story, or an empty string
 */
record HauntDefinition(String id, String label, Set<String> dungeons, int minCompass, Behaviour behaviour,
                       String body, String tellParticle, String tellSound, String ruleLine, String diary) {

    /**
     * The closed set of behaviour kinds. A kind is a few lines of server tick code in {@link Haunts}; the other
     * kinds the draft names (HUNT_IN_WATER, DRAWN_BY_SOUND, FOLLOWS_LIGHT) join this list with their code.
     */
    enum Behaviour {
        /** Moves only while no member looks at it; strikes if it reaches one. */
        STALK_WHEN_UNSEEN
    }

    HauntDefinition {
        dungeons = Set.copyOf(dungeons);
    }
}
