package education.poiesis.createbattlebots.gametest.practice;

import education.poiesis.createbattlebots.practice.PracticeBot;
import education.poiesis.createbattlebots.practice.PracticeRules;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

@GameTestHolder("createbattlebots_test")
@PrefixGameTestTemplate(false)
public final class PracticeGameTests {
    public static volatile ServerLevel contactLevel;
    public static final Queue<SablePhysicsAdapter.ImpactContact> contacts=new ConcurrentLinkedQueue<>();
    public static void capture(SubLevelPhysicsSystem system) {
        if(system.getLevel()==contactLevel) contacts.addAll(new SablePhysicsAdapter().drainContacts(contactLevel,1024));
    }
    @GameTest(template="empty",batch="practice_restore",timeoutTicks=150)
    public static void twentyDetachRepairCyclesReuseEveryBodyAndJoint(GameTestHelper h) {
        var level=h.getLevel(); BlockPos anchor=new BlockPos(400,67,0); floor(level,400);
        var bot=new PracticeBot(level,UUID.randomUUID(),anchor,false,PracticeBot.SPINNER);
        Set<UUID> ids=bot.ids(); int count=SubLevelContainer.getContainer(level).getAllSubLevels().size();
        for(int cycle=1;cycle<=20;cycle++) {
            final int c=cycle;
            h.runAtTickTime(10+cycle*3,()-> {
                var wheel=bot.parts.get(1); var blade=bot.parts.getLast();
                bot.hit(wheel.body.getUniqueId(),100,c*30L);
                bot.hit(blade.body.getUniqueId(),100,c*30L);
                h.assertTrue(!wheel.attached() && !blade.attached(),"Damage must remove two real native joints");
                h.assertTrue(!wheel.body.isRemoved() && !blade.body.isRemoved(),"Debris must remain physical bodies");
            });
            h.runAtTickTime(11+cycle*3,()-> {
                bot.repair();
                h.assertTrue(bot.ids().equals(ids),"Repair cloned a body");
                h.assertTrue(bot.lostParts()==0,"A joint was not recreated");
                h.assertTrue(SubLevelContainer.getContainer(level).getAllSubLevels().size()==count,"Duplicate/orphan body after repair");
                h.assertTrue(bot.position().distanceTo(Vec3.atCenterOf(anchor))<.01,"Chassis did not return to its starting pose");
                if(c==20) { bot.remove(); h.succeed(); }
            });
        }
    }
    @GameTest(template="empty",batch="practice_drive",timeoutTicks=220)
    public static void stockBotsDriveAndNativeContactsDamageParts(GameTestHelper h) {
        var level=h.getLevel(); floor(level,500);
        var left=new PracticeBot(level,UUID.randomUUID(),new BlockPos(500,67,-11),false,PracticeBot.SPINNER);
        var right=new PracticeBot(level,UUID.randomUUID(),new BlockPos(500,67,11),true,PracticeBot.SAW);
        Vec3 initial=left.position();
        contactLevel=level; contacts.clear(); boolean[] hit={false}; double[] maxSpeed={0};
        h.runAtTickTime(10,()->{left.release();right.release();});
        for(int tick=11;tick<180;tick++) {
            final int t=tick;
            h.runAtTickTime(tick,()-> {
                left.drive(new PracticeRules.Input(1,0,true,false));
                right.drive(new PracticeRules.Input(1,0,true,false));
                SablePhysicsAdapter.ImpactContact c;
                while((c=contacts.poll())!=null) {
                    var target=left.physics.resolve(level,c.self()).orElse(null);
                    var other=left.physics.resolve(level,c.other()).orElse(null);
                    if(target!=null && other!=null && right.contains(target) && left.contains(other)) {
                        right.hit(target,PracticeRules.damage(c.speed()),t); hit[0]=true; maxSpeed[0]=Math.max(maxSpeed[0],c.speed());
                    }
                }
            });
        }
        h.runAtTickTime(181,()-> {
            contactLevel=null;
            h.assertTrue(left.position().distanceTo(initial)>2,"W input did not produce physical motion: "+left.position());
            h.assertTrue(hit[0],"Driving starter machines did not generate native opponent contact");
            h.assertTrue(right.parts.stream().anyMatch(p -> p.health<65) || right.coreHealth<100,"Native contacts did not damage the opponent; max contact speed="+maxSpeed[0]);
            left.repair();right.repair();left.remove();right.remove();h.succeed();
        });
    }
    @GameTest(template="empty",batch="practice_walker",timeoutTicks=180)
    public static void crankLegsAreRealBodiesAndLosingOneChangesSupport(GameTestHelper h) {
        var level=h.getLevel();floor(level,600);
        var bot=new PracticeBot(level,UUID.randomUUID(),new BlockPos(600,67,0),false,PracticeBot.CRAWLER);
        h.assertTrue(bot.parts.size()==8,"Crawler must have chassis, six physical legs, and weapon");
        Vec3 start=bot.position();h.runAtTickTime(10,bot::release);
        for(int tick=11;tick<100;tick++) h.runAtTickTime(tick,()->bot.drive(new PracticeRules.Input(1,0,true,false)));
        h.runAtTickTime(101,()-> {
            h.assertTrue(bot.position().distanceTo(start)>1,"Crawler controls did not move its native jointed body; start="+start+" end="+bot.position()+" motion="+bot.physics.bodyInfo(level,bot.parts.getFirst().body.getUniqueId()));
            var leg=bot.parts.get(1); bot.hit(leg.body.getUniqueId(),100,200);
            h.assertTrue(!leg.attached(),"Crank leg must really separate, not merely lose HUD health");
        });
        h.runAtTickTime(110,()-> {bot.repair();h.assertTrue(bot.lostParts()==0,"Leg did not reattach");bot.remove();h.succeed();});
    }
    private static void floor(ServerLevel level,int x) {
        for(int cx=(x-30)>>4;cx<=(x+30)>>4;cx++) for(int z=-2;z<=2;z++) level.setChunkForced(cx,z,true);
        for(BlockPos p:BlockPos.betweenClosed(new BlockPos(x-28,64,-28),new BlockPos(x+28,64,28))) level.setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());
    }
}
