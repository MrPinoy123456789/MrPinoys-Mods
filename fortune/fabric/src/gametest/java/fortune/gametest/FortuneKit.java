package fortune.gametest;

import fortune.core.Machine;
import fortune.core.Settings;
import fortune.mc.Fortune;
import fortune.mc.FortuneService;
import fortune.mc.StakeBank;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared setup for the Fortune gametests. Every test runs against the one live service, so every test installs the
 * same settings and the same fake bank: no first spin wait, and a payout command that tags the player, so a test can
 * see the real console payout path run without Charter Currency on the classpath.
 */
final class FortuneKit {

    static final String PAID_TAG = "fortune_paid";
    static final FakeBank BANK = new FakeBank();

    private FortuneKit() {
    }

    static FortuneService service(GameTestHelper helper) {
        FortuneService service = Fortune.service();
        helper.assertTrue(service != null, "Fortune did not start with the server");
        synchronized (FortuneKit.class) {
            if (service.bank() != BANK) {
                Settings settings = new Settings();
                settings.firstSpinMinutes = 0;
                settings.joinPrompt = false;
                settings.payoutCommand = "tag %uuid% add " + PAID_TAG;
                settings.milestoneCommands = new ArrayList<>();
                service.useMachine(new Machine(settings));
                service.useBank(BANK);
            }
        }
        return service;
    }

    static ServerPlayer player(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    /** A click exactly as a client sends it, through the packet handler sgui hooks. */
    static void click(ServerPlayer player, int slot, int button, ContainerInput input) {
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(player.containerMenu.containerId,
                player.containerMenu.getStateId(), (short) slot, (byte) button, input,
                new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
        player.connection.handleContainerClick(packet);
    }

    /** Banked Coin per player, in the bank's unit (64 Marks to a Sovereign), and every charge made. Never deposits: there is nothing a stake pays back. */
    static final class FakeBank implements StakeBank {
        final Map<UUID, Long> balances = new ConcurrentHashMap<>();
        final List<String> reasons = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<UUID> charges = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public long balance(UUID player) {
            return balances.getOrDefault(player, 0L);
        }

        @Override
        public boolean charge(UUID player, String name, long marks, String reason) {
            long have = balance(player);
            if (have < marks) return false;
            balances.put(player, have - marks);
            charges.add(player);
            reasons.add(reason);
            return true;
        }

        long chargesFor(UUID player) {
            synchronized (charges) {
                return charges.stream().filter(player::equals).count();
            }
        }
    }
}
