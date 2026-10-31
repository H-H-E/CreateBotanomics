package education.poiesis.createbattlebots.gametest;

import dev.ryanhcode.sable.platform.SableEventPlatform;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import net.neoforged.fml.common.Mod;

/** Included only in the gametest source set; never shipped in the gameplay jar. */
@Mod(BattleBotsTestMod.MOD_ID)
public final class BattleBotsTestMod {
    public static final String MOD_ID = "createbattlebots_test";

    public BattleBotsTestMod() {
        SableEventPlatform.INSTANCE.onPostPhysicsTick((system, step) -> {
            PhysicsGameTests.ContactProbe probe = PhysicsGameTests.contactProbe;
            if (probe != null && probe.level() == system.getLevel()) {
                for (SablePhysicsAdapter.ImpactContact contact : probe.adapter().drainContacts(system.getLevel(), 1024)) {
                    if ((contact.self().equals(probe.left()) && contact.other().equals(probe.right()))
                            || (contact.self().equals(probe.right()) && contact.other().equals(probe.left()))) {
                        probe.contacts().add(contact);
                    }
                }
            }
        });
    }
}
