package charter.gametest;

import charter.mc.CharterPerms;
import charter.mc.RaceNodes;
import charter.mc.land.SpawnRules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Charter's permission nodes as a permissions mod (LuckPerms) sees them: {@code charter:admin} and
 * {@code charter:bypass} can be given to someone who is not an operator, or taken from an operator, and a permissions
 * mod answering race nodes in the default phase cannot overrule Charter for a member of the race. Also: a mod that
 * answers use entity in the default phase (Serverside CarryOn) is never asked before Charter refuses a stranger in a keep.
 */
public final class HardeningGameTest {

    /** Values a stand in permissions mod gives, per test player only, so no other test sees them. */
    private static final Map<UUID, Map<Identifier, Boolean>> GIVEN = new ConcurrentHashMap<>();

    static {
        PermissionEvents.ON_REQUEST.register(new PermissionEvents.OnRequest() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T handlePermissionRequest(PermissionContext context, PermissionNode<T> node) {
                Map<Identifier, Boolean> mine = GIVEN.get(context.uuid());
                if (mine == null) return null;
                return (T) mine.get(node.key());
            }
        });
    }

    /** Players a stand in carry mod would pick things up for, in the default phase as Serverside CarryOn does. */
    private static final java.util.Set<UUID> CARRIERS = ConcurrentHashMap.newKeySet();

    static {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                CARRIERS.contains(player.getUUID()) ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS);
    }

    private static void give(ServerPlayer player, Identifier node, Boolean value) {
        GIVEN.computeIfAbsent(player.getUUID(), id -> new ConcurrentHashMap<>()).put(node, value);
    }

    private static boolean canUseAdmin(LandKit kit, ServerPlayer player) {
        var charter = kit.server.getCommands().getDispatcher().getRoot().getChild("charter");
        var admin = charter == null ? null : charter.getChild("admin");
        return admin != null && admin.canUse(player.createCommandSourceStack());
    }

    @GameTest(maxTicks = 100)
    public void theAdminNodeOpensTheOperatorCommandsWithoutOperator(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer staff = kit.outsider();
            try {
                helper.assertTrue(!CharterPerms.admin(staff) && !canUseAdmin(kit, staff), "a player who is not an operator has no admin commands");
                give(staff, CharterPerms.ADMIN, true);
                helper.assertTrue(CharterPerms.admin(staff), "the node charter:admin gives the operators' menu");
                helper.assertTrue(canUseAdmin(kit, staff), "and /charter admin, through every merged registration");
                helper.assertTrue(!CharterPerms.bypass(staff) && !SpawnRules.isOp(staff), "but not the operator pass in the world");
                helper.succeed();
            } finally {
                GIVEN.remove(staff.getUUID());
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void theBypassNodeIsTheOperatorPassAndCanBeTakenFromAnOperator(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer builder = kit.outsider();
            ServerPlayer op = kit.outsider();
            try {
                give(builder, CharterPerms.BYPASS, true);
                helper.assertTrue(CharterPerms.bypass(builder) && SpawnRules.isOp(builder), "charter:bypass exempts a builder from the spawn rules");
                kit.server.getPlayerList().op(op.nameAndId(), java.util.Optional.of(
                        net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
                helper.assertTrue(CharterPerms.bypass(op) && CharterPerms.admin(op) && canUseAdmin(kit, op),
                        "with nothing said, an operator keeps both as before");
                give(op, CharterPerms.BYPASS, false);
                give(op, CharterPerms.ADMIN, false);
                helper.assertTrue(!SpawnRules.isOp(op), "a permissions mod can hold an operator to the spawn rules");
                helper.assertTrue(!CharterPerms.admin(op) && !canUseAdmin(kit, op), "and take the admin commands from them");
                helper.succeed();
            } finally {
                GIVEN.remove(builder.getUUID());
                GIVEN.remove(op.getUUID());
                kit.server.getPlayerList().deop(op.nameAndId());
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void charterAnswersRaceNodesBeforeAPermissionsMod(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer dwarf = kit.faction("Dwarf");
            ServerPlayer elf = kit.faction("Elf");
            Identifier dwarfNode = RaceNodes.nodeOf(kit.rt.state.org(kit.orgOf(dwarf)).orElseThrow().name());
            Identifier elfNode = RaceNodes.nodeOf(kit.rt.state.org(kit.orgOf(elf)).orElseThrow().name());
            try {
                give(dwarf, dwarfNode, false);
                give(dwarf, elfNode, true);
                PermissionContextOwner owner = (PermissionContextOwner) dwarf;
                helper.assertTrue(owner.checkPermission(dwarfNode, false), "a stray false in the permissions mod does not take a member's race");
                helper.assertTrue(owner.checkPermission(elfNode, false), "a node Charter leaves undecided can still be granted elsewhere");
                helper.succeed();
            } finally {
                GIVEN.remove(dwarf.getUUID());
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void aCarryModCannotTakeAnEntityOutOfAKeepBeforeCharterSaysNo(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Carry");
            kit.keep(lord);
            ServerPlayer stranger = kit.outsider();
            ArmorStand inside = EntityTypes.ARMOR_STAND.create(kit.level, EntitySpawnReason.COMMAND);
            inside.snapTo(kit.pos(8, 3).getX() + 0.5, kit.y, kit.pos(8, 3).getZ() + 0.5);
            kit.level.addFreshEntity(inside);
            ArmorStand outside = EntityTypes.ARMOR_STAND.create(kit.level, EntitySpawnReason.COMMAND);
            outside.snapTo(kit.pos(-8, 3).getX() + 0.5, kit.y, kit.pos(-8, 3).getZ() + 0.5);
            kit.level.addFreshEntity(outside);
            CARRIERS.add(stranger.getUUID());
            try {
                var use = UseEntityCallback.EVENT.invoker();
                helper.assertTrue(use.interact(stranger, kit.level, InteractionHand.MAIN_HAND, inside, null) == InteractionResult.FAIL,
                        "a stranger's carry is refused inside a keep");
                helper.assertTrue(use.interact(stranger, kit.level, InteractionHand.MAIN_HAND, outside, null) == InteractionResult.SUCCESS_SERVER,
                        "while on neutral ground the other mod still answers");
                helper.succeed();
            } finally {
                CARRIERS.remove(stranger.getUUID());
                inside.discard();
                outside.discard();
            }
        }
    }
}
