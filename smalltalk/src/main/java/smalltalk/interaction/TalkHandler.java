package smalltalk.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import smalltalk.dialogue.LineSelector;
import smalltalk.dialogue.Situation;
import smalltalk.identity.Identity;
import smalltalk.identity.IdentityDeriver;
import smalltalk.social.FamiliarityAttachment;
import smalltalk.social.FamiliarityEntry;
import smalltalk.social.SharedKnowledge;
import smalltalk.task.RequestDialogs;
import smalltalk.task.RequestOfferer;
import smalltalk.task.Task;
import smalltalk.task.TaskRegistry;
import smalltalk.task.TaskState;

import java.util.Optional;

/**
 * The talk verb (SPEC.md sections 0.1, 4, 6.1): right-click a resident,
 * empty hand. Opens a Dialog whose greeting is the highest-weighted passing
 * context, right now -- nothing is pre-computed or ticked toward this.
 *
 * <p>SPEC.md section 12.3's daily-cadence roll happens here too, before the
 * ambient greeting is computed: a resident with an OFFERED or ACCEPTED task
 * for this player shows that request dialog instead of (never alongside)
 * the ambient greeting (SPEC.md section 12.2).
 */
public final class TalkHandler {

    /** Not specified by SPEC.md -- a modest default for "counts toward familiarity." */
    private static final int TALK_FAMILIARITY_AWARD = 2;

    private TalkHandler() {}

    public static void open(Villager villager, ServerPlayer player) {
        long tick = player.level().getGameTime();

        // SPEC.md section 12.3: the roll is lazy, on talk, never on a tick --
        // and always before the ambient-context greeting below is computed.
        RequestOfferer.maybeOffer(villager, player, tick);

        boolean firstMeeting = !FamiliarityAttachment.allOf(villager).containsKey(player.getUUID());
        FamiliarityEntry entry = FamiliarityAttachment.current(villager, player.getUUID(), tick);

        DialogHold.hold(villager, player);

        Optional<Task> pending = TaskRegistry.of(player.level())
                .activeTask(villager.getUUID(), player.getUUID(), tick);
        if (pending.isPresent() && pending.get().state() == TaskState.OFFERED) {
            RequestDialogs.openOffer(player, villager, pending.get());
        } else if (pending.isPresent() && pending.get().state() == TaskState.ACCEPTED) {
            RequestDialogs.openCompletion(player, villager, pending.get());
        } else {
            Identity identity = IdentityDeriver.derive(villager);
            Optional<SharedKnowledge.Mention> mention = SharedKnowledge.pick(
                    villager, player.getUUID(), tick, player.level().getRandom());
            Situation situation = new Situation(
                    villager, player.level(), player.isOnFire(), firstMeeting, entry.tier(), mention);
            String line = LineSelector.select(identity.personality(), situation, player.level().getRandom());
            line = line.replace("{player}", player.getName().getString());
            if (mention.isPresent()) {
                line = line.replace("{other}", mention.get().otherPlayerName());
            } else {
                line = line.replace("{other}", "someone");
            }
            player.openDialog(DialogScreens.greeting(villager, line));
        }

        boolean alreadyCountedToday = !firstMeeting && FamiliarityAttachment.sameDay(entry.lastInteractionTick(), tick);
        int award = alreadyCountedToday ? 0 : TALK_FAMILIARITY_AWARD;
        String memory = firstMeeting ? "first_meeting" : null;
        FamiliarityAttachment.recordInteraction(villager, player.getUUID(), tick, award, memory);
    }
}
