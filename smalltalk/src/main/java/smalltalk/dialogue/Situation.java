package smalltalk.dialogue;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import smalltalk.social.FamiliarityTier;

/**
 * The snapshot of world/player/relationship state a {@link DialogueContext}
 * predicate is evaluated against. Built once, at dialog-open time -- there
 * is no tick loop building this repeatedly (SPEC.md section 0).
 */
public record Situation(Villager villager, ServerLevel level, boolean playerOnFire,
                         boolean firstMeeting, FamiliarityTier tier) {
}
