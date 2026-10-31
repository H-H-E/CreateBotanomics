package education.poiesis.createbattlebots.gametest;

import education.poiesis.createbattlebots.block.BattleNodeEntity;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import education.poiesis.createbattlebots.core.ArcadeInput;
import education.poiesis.createbattlebots.practice.PracticeRig;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder("createbattlebots_test")
@PrefixGameTestTemplate(false)
public final class PracticeGameTests {
    private static final TicketType<String> FLOOR_TICKET=TicketType.create("battlebots_practice_test",String::compareTo);
    private static void floor(GameTestHelper h,BlockPos origin,String name){
        var level=h.getLevel();level.getChunkSource().addRegionTicket(FLOOR_TICKET,new ChunkPos(origin),3,name);
        for(int x=-24;x<=24;x++)for(int z=-24;z<=24;z++)level.setBlockAndUpdate(new BlockPos(origin.getX()+x,63,origin.getZ()+z),Blocks.STONE.defaultBlockState());
    }
    private static void check(GameTestHelper h,boolean condition,String message){if(!condition)h.fail(message);}
    private static void cleanup(GameTestHelper h,PracticeRig rig){
        rig.freeze(false);rig.releaseLoaded();
        var container=SubLevelContainer.getContainer(h.getLevel());
        for(UUID id:rig.ids()) {
            var body=container.getSubLevel(id);
            if(body!=null)container.removeSubLevel(body,dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
        }
        container.processSubLevelRemovals();
    }

    @GameTest(template="empty",batch="battlebots_practice_drive",timeoutTicks=180)
    public static void starterActuallyDrivesTurnsAndSpins(GameTestHelper h){
        BlockPos origin=new BlockPos(240,66,64);floor(h,origin,"drive");
        PracticeRig rig=PracticeRig.create(h.getLevel(),UUID.randomUUID(),origin,false,0);
        check(h,h.getLevel().getBlockState(rig.core.above()).is(Blocks.WAXED_CUT_COPPER_SLAB),"Starter decoration must not oxidize between capture and repair");
        Vec3[] start={rig.position()};double[] heading={0};double[] fastest={0};
        h.runAtTickTime(5,()->{check(h,rig.jointsValid(),"All five native starter joints must exist");rig.freeze(false);start[0]=rig.position();});
        for(int t=6;t<75;t++){final int tick=t;h.runAtTickTime(t,()->{rig.drive(new ArcadeInput(1,0,true),tick);fastest[0]=Math.max(fastest[0],rig.weaponSpeed());});}
        h.runAtTickTime(75,()->{
            check(h,rig.position().z<start[0].z-3,"Actual native starter did not drive three blocks: "+rig.position());
            check(h,fastest[0]>2,"Saw never reached tutorial speed: "+fastest[0]);heading[0]=rig.heading();
        });
        for(int t=76;t<116;t++){final int tick=t;h.runAtTickTime(t,()->rig.drive(new ArcadeInput(.2,1,true),tick));}
        h.runAtTickTime(117,()->{
            check(h,Math.abs(Math.IEEEremainder(rig.heading()-heading[0],Math.PI*2))>.6,"Actual native starter did not turn enough: before="+heading[0]+" after="+rig.heading()+" pose="+rig.position()+" body="+new SablePhysicsAdapter().bodyInfo(h.getLevel(),rig.root));
            rig.restore();check(h,rig.jointsValid(),"Driving broke a starter joint without combat");
            cleanup(h,rig);h.succeed();
        });
    }
    @GameTest(template="empty",batch="battlebots_practice_opponent",timeoutTicks=260)
    public static void scrapbotActuallyApproachesAndCollides(GameTestHelper h){
        BlockPos origin=new BlockPos(528,66,64);floor(h,origin,"opponent");
        var level=h.getLevel();var adapter=new SablePhysicsAdapter();
        PracticeRig human=PracticeRig.create(level,UUID.randomUUID(),origin.south(8),false,0);
        PracticeRig enemy=PracticeRig.create(level,UUID.randomUUID(),origin.north(8),false,Math.PI);
        var pilot=new education.poiesis.createbattlebots.core.ScrapbotPilot();
        double initial=human.position().distanceTo(enemy.position());
        double[] closest={initial};int[] contacts={0};
        var damage=new education.poiesis.createbattlebots.core.DamageResolver(human.root,enemy.root,PracticeRig.DAMAGE_SETTINGS);
        var arena=new net.minecraft.world.phys.AABB(origin).inflate(24);
        h.runAtTickTime(5,()->{enemy.freeze(false);human.freeze(false);});
        for(int t=6;t<200;t++){final int tick=t;h.runAtTickTime(t,()->{
            var pos=enemy.position();var target=human.position();var forward=enemy.forward();
            var input=pilot.decide(tick,pos.x,pos.z,forward.x,forward.z,target.x,target.z,origin.getX()+.5,origin.getZ()+.5);
            enemy.drive(input,tick);human.drive(new ArcadeInput(0,0,true),tick);
            closest[0]=Math.min(closest[0],pos.distanceTo(target));
            for(var contact:adapter.drainContacts(level,512)) {
                UUID self=adapter.resolve(level,contact.self()).orElse(null),other=adapter.resolve(level,contact.other()).orElse(null);
                if(human.owns(self)&&enemy.owns(other)){contacts[0]++;human.hit(enemy,contact,damage,tick,arena);}
                if(enemy.owns(self)&&human.owns(other)){contacts[0]++;enemy.hit(human,contact,damage,tick,arena);}
            }
        });}
        h.runAtTickTime(201,()->{
            int detached=human.detachedCount()+enemy.detachedCount();
            human.restore();enemy.restore();
            boolean repaired=human.detachedCount()==0&&enemy.detachedCount()==0&&human.jointsValid()&&enemy.jointsValid();
            cleanup(h,human);cleanup(h,enemy);
            check(h,detached>0,"Combat contacts never detached a real module");
            check(h,repaired,"Combat damage could not be repaired");
            check(h,closest[0]<initial-6,"Scrapbot did not close the gap: "+closest[0]);
            check(h,contacts[0]>0,"Scrapbot never made actual native contact with the opponent");
            h.succeed();
        });
    }

    @GameTest(template="empty",batch="battlebots_practice_repair",timeoutTicks=140)
    public static void twentyDetachRepairCyclesPreserveBodiesAndInventories(GameTestHelper h){
        BlockPos origin=new BlockPos(336,66,64);floor(h,origin,"repair");
        PracticeRig rig=PracticeRig.create(h.getLevel(),UUID.randomUUID(),origin,false,0);
        var adapter=new SablePhysicsAdapter();
        BlockPos chest=rig.core.above(2);h.getLevel().setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());
        var chestEntity=(net.minecraft.world.level.block.entity.ChestBlockEntity)h.getLevel().getBlockEntity(chest);
        chestEntity.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,17));
        rig.capture();
        int count=SubLevelContainer.getContainer(h.getLevel()).getAllSubLevels().size();
        Set<UUID> ids=Set.copyOf(rig.ids());
        for(int i=1;i<=20;i++){final int cycle=i;
            h.runAtTickTime(i*4L,()->{
                check(h,rig.detachAt(rig.core.offset(-1,0,-1)),"Wheel must really detach");
                check(h,rig.attachedLegs()==3&&rig.detachedCount()==1,"Detached wheel must reduce traction");
                ((BattleNodeEntity)h.getLevel().getBlockEntity(rig.core.offset(-1,0,-1))).hurt(10000);
                rig.freeze(false);
            });
            h.runAtTickTime(i*4L+2,()->{
                rig.restore();check(h,rig.jointsValid(),"Restored native joint invalid on cycle "+cycle);
                check(h,rig.ids().equals(ids),"Repair replaced original physics bodies");
                check(h,rig.attachedLegs()==4,"Wheel not reattached");
                check(h,SubLevelContainer.getContainer(h.getLevel()).getAllSubLevels().size()==count,"Repair spawned duplicate/orphan bodies: expected="+count+" actual="+SubLevelContainer.getContainer(h.getLevel()).getAllSubLevels().size());
                var item=((net.minecraft.world.level.block.entity.ChestBlockEntity)h.getLevel().getBlockEntity(chest)).getItem(0);
                check(h,item.is(net.minecraft.world.item.Items.DIAMOND)&&item.getCount()==17,"Repair changed the user's inventory");
                if(cycle==20){rig.verifySaved();cleanup(h,rig);h.succeed();}
            });
        }
    }
    @GameTest(template="empty",batch="battlebots_practice_walker",timeoutTicks=180)
    public static void walkerMovesAndCanLoseARealLeg(GameTestHelper h){
        BlockPos origin=new BlockPos(432,66,64);floor(h,origin,"walker");
        PracticeRig rig=PracticeRig.create(h.getLevel(),UUID.randomUUID(),origin,true,0);Vec3 start=rig.position();
        h.runAtTickTime(5,()->rig.freeze(false));
        for(int t=6;t<85;t++){final int tick=t;h.runAtTickTime(t,()->rig.drive(new ArcadeInput(1,0,true),tick));}
        h.runAtTickTime(85,()->{
            check(h,rig.position().z<start.z-3,"Walking starter did not make forward progress: "+rig.position());
            check(h,rig.detachAt(rig.core.offset(1,0,-1)),"Leg did not detach");
            check(h,rig.attachedLegs()==3,"Damaged leg did not affect drive capacity");
        });
        for(int t=86;t<110;t++){final int tick=t;h.runAtTickTime(t,()->rig.drive(new ArcadeInput(.5,.5,true),tick));}
        h.runAtTickTime(111,()->{
            rig.restore();check(h,rig.jointsValid()&&rig.attachedLegs()==4,"Walker leg did not return safely");
            CompoundTag saved=rig.save();PracticeRig copy=PracticeRig.load(h.getLevel(),saved);
            check(h,copy.ids().equals(rig.ids()),"Reload must retain native body identity");
            cleanup(h,rig);h.succeed();
        });
    }
}
