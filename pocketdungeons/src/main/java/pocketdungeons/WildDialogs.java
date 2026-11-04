package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.DialogBody;

import java.util.ArrayList;
import java.util.List;

/**
 * The screens of wild mode (task 1.3): two buttons each, the second a plain close, so Esc and the second button do the
 * same harmless thing. The first button carries the owner and the lodestone id; {@link DialogRouter} re-checks both on
 * the click, and {@link WildRun} re-checks distance and state, because a payload can be forged.
 */
final class WildDialogs {

    private WildDialogs() {}

    /** Opens the outside screen: the promise, the state, Enter and Not now. */
    static void showOutside(ServerPlayer player, WildState.Lodestone lodestone) {
        DialogKit.show(player, outside(lodestone, WildClock.now(), WildRun.insideCount(lodestone.id), player));
        Chime.menuOpens(player);
    }

    /** Opens the screen of an Exit block inside a dungeon. */
    static void showInside(ServerPlayer player, WildState.Lodestone lodestone, boolean clear) {
        DialogKit.show(player, inside(lodestone, clear, WildClock.now(), player));
        Chime.menuOpens(player);
    }

    static Dialog outside(WildState.Lodestone lodestone, long now, int inside, ServerPlayer viewer) {
        WildDungeons.Offering offering = WildDungeons.byId(lodestone.dungeon);
        String title = offering != null ? offering.name() : lodestone.dungeon;
        String promise = offering != null ? offering.promise() : "";
        List<String> lines = WildText.outsideBody(promise, lodestone.phase, inside,
                lodestone.resetAt - now, lodestone.expiresAt - now, lodestone.ominous);
        if (promise.isEmpty()) {
            lines.remove(0);
        }
        List<DialogBody> body = body(lines);
        // Who cleared it last, in their kingdom's colour (decision 2026-11-04).
        String last = WildText.lastCleared(lodestone.lastClearedBy, now - lodestone.lastClearedAt);
        if (last != null) {
            body.add(DialogKit.text(Component.literal(last).withStyle(style -> lodestone.lastClearedColour >= 0
                    ? style.withColor(lodestone.lastClearedColour) : style.withColor(ChatFormatting.GRAY))));
        }
        return DialogKit.confirm(title, body,
                DialogKit.button(WildText.ENTER, null,
                        DialogKit.submit(DialogScreens.ACTION_WILD_ENTER, context(viewer, lodestone))),
                DialogKit.closeButton(WildText.NOT_NOW));
    }

    static Dialog inside(WildState.Lodestone lodestone, boolean clear, long now, ServerPlayer viewer) {
        String title = clear ? WildText.EXIT_TITLE : WildText.LEAVE_TITLE;
        String line = clear ? WildText.clearExitBody(lodestone.resetAt - now) : WildText.KEEP_WHAT_YOU_CARRY;
        return DialogKit.confirm(title, body(List.of(line)),
                DialogKit.button(WildText.LEAVE, null,
                        DialogKit.submit(DialogScreens.ACTION_WILD_LEAVE, context(viewer, lodestone))),
                DialogKit.closeButton(WildText.STAY));
    }

    private static List<DialogBody> body(List<String> lines) {
        List<DialogBody> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            out.add(i == 0 ? DialogKit.text(line) : DialogKit.text(Component.literal(line).withStyle(ChatFormatting.GRAY)));
        }
        return out;
    }

    private static CompoundTag context(ServerPlayer viewer, WildState.Lodestone lodestone) {
        CompoundTag tag = new CompoundTag();
        tag.putString(DialogScreens.KEY_OWNER, viewer.getUUID().toString());
        tag.putString(DialogScreens.KEY_WILD, lodestone.id);
        return tag;
    }
}
