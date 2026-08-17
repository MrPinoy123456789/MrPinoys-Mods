package smalltalk.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import smalltalk.dialogue.LineSelector;
import smalltalk.dialogue.Situation;
import smalltalk.identity.Identity;
import smalltalk.identity.IdentityDeriver;
import smalltalk.social.FamiliarityAttachment;
import smalltalk.social.FamiliarityEntry;

/**
 * The talk verb (SPEC.md sections 0.1, 4, 6.1): right-click a resident,
 * empty hand. Opens a Dialog whose greeting is the highest-weighted passing
 * context, right now -- nothing is pre-computed or ticked toward this.
 */
public final class TalkHandler {

    /** Not specified by SPEC.md -- a modest default for "counts toward familiarity." */
    private static final int TALK_FAMILIARITY_AWARD = 2;

    private TalkHandler() {}

    public static void open(Villager villager, ServerPlayer player) {
        long tick = player.level().getGameTime();
        boolean firstMeeting = !FamiliarityAttachment.allOf(villager).containsKey(player.getUUID());
        FamiliarityEntry entry = FamiliarityAttachment.current(villager, player.getUUID(), tick);

        Identity identity = IdentityDeriver.derive(villager.getUUID());
        Situation situation = new Situation(villager, player.level(), player.isOnFire(), firstMeeting, entry.tier());
        String line = LineSelector.select(identity.personality(), situation, player.level().getRandom());

        DialogHold.hold(villager, player);
        player.openDialog(DialogScreens.greeting(villager, line));

        boolean alreadyCountedToday = !firstMeeting && FamiliarityAttachment.sameDay(entry.lastInteractionTick(), tick);
        int award = alreadyCountedToday ? 0 : TALK_FAMILIARITY_AWARD;
        String memory = firstMeeting ? "first_meeting" : null;
        FamiliarityAttachment.recordInteraction(villager, player.getUUID(), tick, award, memory);
    }
}
