package education.poiesis.createbattlebots.compat.computercraft;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import education.poiesis.createbattlebots.block.BattleBlocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Loaded only after the entry point has confirmed ComputerCraft is installed. */
public final class ComputerCraftCompat {
    public static void register(IEventBus modBus) {
        modBus.addListener(ComputerCraftCompat::capabilities);
    }

    private static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), BattleBlocks.NODE_ENTITY.get(),
                (node, side) -> node.getBlockState().is(BattleBlocks.TRANSPONDER.get())
                        ? new BattleTransponderPeripheral(node) : null);
    }

    private ComputerCraftCompat() {}
}
