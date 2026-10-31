package education.poiesis.createbattlebots.practice;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import education.poiesis.createbattlebots.block.BattleBlocks;
import education.poiesis.createbattlebots.block.BattleNodeEntity;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import education.poiesis.createbattlebots.core.ArcadeInput;
import education.poiesis.createbattlebots.core.DamageResolver;
import net.minecraft.world.phys.AABB;
import education.poiesis.createbattlebots.block.BattleNodeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;

/**
 * Six original, persistent native bodies, joined by five owned rotary constraints.
 * Only these explicitly owned joints may break. No cloning, inventory replay or
 * arbitrary third-party structure deletion occurs during repair.
 */
public final class PracticeRig {
    public static final SubLevelLoadingTicketType<UUID> TICKET = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath("createbattlebots", "practice"), UUIDUtil.CODEC);
    public static final DamageResolver.Settings DAMAGE_SETTINGS =
            new DamageResolver.Settings(.65,.8,65,8,2,2048,10000,40);
    private final ServerLevel level;
    private final SablePhysicsAdapter physics = new SablePhysicsAdapter();
    public final UUID owner, root;
    public final BlockPos core;
    public final boolean walking;
    private final List<Module> modules = new ArrayList<>();
    private CompoundTag checkpoint;
    private double phase;

    private PracticeRig(ServerLevel level, UUID owner, UUID root, BlockPos core, boolean walking) {
        this.level=level; this.owner=owner; this.root=root; this.core=core; this.walking=walking;
    }
    public static void init() { /* Initializes the loading ticket before native world deserialization. */ }

    public static PracticeRig create(ServerLevel level, UUID owner, BlockPos origin, boolean walking, double yaw) {
        Map<BlockPos, BlockState> chassis = new LinkedHashMap<>();
        for (int x=-1; x<=1; x++) for (int z=-1; z<=1; z++)
            chassis.put(origin.offset(x,0,z), x==0 && z==0 ? BattleBlocks.TRANSPONDER.get().defaultBlockState()
                    : BattleBlocks.ARMOR.get().defaultBlockState());
        chassis.put(origin.above(), Blocks.WAXED_CUT_COPPER_SLAB.defaultBlockState());
        ServerSubLevel base=assemble(level, owner, origin, chassis);
        PracticeRig rig=new PracticeRig(level,owner,base.getUniqueId(),base.getPlot().getCenterBlock(),walking);
        try {
            for (int side : new int[]{-1,1}) for (int front : new int[]{-1,1}) {
                BlockPos anchor=origin.offset(side*2,walking?0:-1,front);
                Map<BlockPos,BlockState> parts=new LinkedHashMap<>();
                parts.put(anchor, walking ? BattleBlocks.RELAY.get().defaultBlockState() : BattleBlocks.WHEEL.get().defaultBlockState());
                if (walking) {
                    parts.put(anchor.below(), Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                    parts.put(anchor.below().offset(0,0,front), BattleBlocks.WHEEL.get().defaultBlockState());
                }
                ServerSubLevel child=assemble(level,owner,anchor,parts);
                rig.modules.add(new Module(child.getUniqueId(), child.getPlot().getCenterBlock(),
                        side*2,walking?0:-1,front,side,false,rig.core.offset(side,0,front)));
            }
            BlockPos rotor=origin.north(3);
            Map<BlockPos,BlockState> blades=new LinkedHashMap<>();
            blades.put(rotor, BattleBlocks.RELAY.get().defaultBlockState());
            blades.put(rotor.east(), BattleBlocks.SAW.get().defaultBlockState());
            blades.put(rotor.west(), BattleBlocks.SAW.get().defaultBlockState());
            ServerSubLevel blade=assemble(level,owner,rotor,blades);
            rig.modules.add(new Module(blade.getUniqueId(),blade.getPlot().getCenterBlock(),0,0,-3,0,true,rig.core.north()));
            if (Math.abs(yaw)>0.001) {
                Vector3d pivot=new Vector3d(origin.getX()+.5,origin.getY()+.5,origin.getZ()+.5);
                Quaterniond rotation=new Quaterniond().rotateY(yaw);
                for (UUID id:rig.ids()) {
                    ServerSubLevel body=rig.body(id);
                    Vector3d p=new Vector3d(body.logicalPose().position()).sub(pivot);
                    rotation.transform(p).add(pivot);
                    SubLevelPhysicsSystem.require(level).getPipeline().teleport(body,p,
                            new Quaterniond(rotation).mul(body.logicalPose().orientation()));
                    SubLevelPhysicsSystem.require(level).updatePose(body);
                    body.updateLastPose(); body.updateBoundingBox();
                }
            }
            for (UUID id:rig.ids()) for (BlockPos pos:rig.physics.plotBlocks(level,id,512))
                if(level.getBlockEntity(pos) instanceof BattleNodeEntity node) { node.setOwner(owner); node.setBot(rootId(rig)); }
            rig.connect(); rig.capture(); rig.freeze(true);
            return rig;
        } catch (RuntimeException failure) {
            rig.disposeStarter(); throw failure;
        }
    }
    private static UUID rootId(PracticeRig rig) { return rig.root; }

    private static ServerSubLevel assemble(ServerLevel level, UUID owner, BlockPos anchor, Map<BlockPos,BlockState> blocks) {
        for (BlockPos pos:blocks.keySet()) if (!level.getBlockState(pos).isAir())
            throw new IllegalStateException("Starter assembly area is occupied; no existing blocks were overwritten.");
        blocks.forEach(level::setBlockAndUpdate);
        ServerSubLevel body=SubLevelAssemblyHelper.assembleBlocks(level,anchor,new ArrayList<>(blocks.keySet()),BoundingBox3i.from(blocks.keySet()));
        body.updateBoundingBox();
        SubLevelContainer.getContainer(level).addForceLoadTicket(body,TICKET,owner);
        return body;
    }
    public Set<UUID> ids() {
        Set<UUID> result=new LinkedHashSet<>(); result.add(root); modules.forEach(m->result.add(m.body)); return result;
    }
    public boolean isAssemblyAnchor(BlockPos pos) {
        return core.equals(pos) || modules.stream().anyMatch(m->m.anchor.equals(pos));
    }
    public boolean owns(UUID id) { return root.equals(id) || modules.stream().anyMatch(m->m.body.equals(id)); }
    public boolean loaded() { return ids().stream().allMatch(id->physics.bodyInfo(level,id).isPresent()); }
    private ServerSubLevel body(UUID id) {
        var container=SubLevelContainer.getContainer(level);
        var value=container==null?null:container.getSubLevel(id);
        if (!(value instanceof ServerSubLevel nativeBody) || nativeBody.isRemoved())
            throw new IllegalStateException("A practice body is missing; its saved design was retained.");
        return nativeBody;
    }
    public void holdLoaded() {
        for(UUID id:ids()) SubLevelContainer.getContainer(level).addForceLoadTicket(body(id),TICKET,owner);
    }
    public void releaseLoaded() {
        for(UUID id:ids()) if(physics.loaded(level,id))
            SubLevelContainer.getContainer(level).removeForceLoadTicket(body(id),TICKET,owner);
    }
    public Vec3 position() { return physics.worldPosition(level,core); }
    public Vector3d forward() { return body(root).logicalPose().orientation().transform(new Vector3d(0,0,-1)); }
    public double heading() { var f=forward(); return Math.atan2(-f.x,-f.z); }
    public double speed() { return physics.bodyInfo(level,root).map(i->i.linearVelocity().length()).orElse(0.0); }
    public double weaponSpeed() {
        return modules.stream().filter(m->m.weapon && !m.detached).findFirst()
                .flatMap(m->physics.bodyInfo(level,m.body)).map(i->i.angularVelocity().length()).orElse(0.0);
    }
    public double health() { return level.getBlockEntity(core) instanceof BattleNodeEntity node ? node.health() : 0; }
    public int attachedLegs() { return (int)modules.stream().filter(m->!m.weapon && !m.detached).count(); }
    public int detachedCount() { return (int)modules.stream().filter(m->m.detached).count(); }
    public boolean jointsValid() { return modules.stream().allMatch(m->m.detached || m.joint!=null && m.joint.isValid()); }

    public void freeze(boolean freeze) {
        if(freeze) for(UUID id:ids()) {if(physics.bodyInfo(level,id).isPresent())physics.freeze(level,Set.of(id));}
        else physics.unfreeze(level,ids());
    }

    public void drive(ArcadeInput input, long tick) {
        if(!loaded() || health()<=0) return;
        Vector3d forward=forward();
        Vector3d up=body(root).logicalPose().orientation().transform(new Vector3d(0,1,0));
        var info=physics.bodyInfo(level,root).orElseThrow();
        int left=0,right=0;
        for(Module module:modules) if(!module.weapon && !module.detached) { if(module.side<0) left++; else right++; }
        double traction=(left+right)/4.0;
        double steer=Math.max(-1,Math.min(1,input.steering()+input.throttle()*(right-left)*.17));
        RigidBodyHandle chassis=RigidBodyHandle.of(body(root));
        // Limited arcade traction assists the real wheels/legs. Gravity, roll, pitch,
        // collisions and detached debris remain the native solver's responsibility.
        if(up.y>.25 && info.bounds().minY<68 && traction>0) {
            Vector3d horizontal=new Vector3d(forward.x,0,forward.z);
            if(horizontal.lengthSquared()>.01) horizontal.normalize();
            Vector3d velocity=new Vector3d(info.linearVelocity().x,0,info.linearVelocity().z);
            Vector3d delta=horizontal.mul(input.throttle()*(walking?5.5:7.0)).sub(velocity).mul(.14*traction);
            if(delta.length()>.55) delta.normalize(.55);
            // Sable impulses are local-space; velocity targets above are world-space.
            body(root).logicalPose().orientation().transformInverse(delta);
            chassis.applyLinearImpulse(delta.mul(Math.min(info.mass(),5000)));
            double turn=(steer*1.6-info.angularVelocity().y)*.16*traction;
            Vector3d yawImpulse=new Vector3d(0,Math.max(-.45,Math.min(.45,turn))*Math.min(info.mass(),5000)*10,0);
            body(root).logicalPose().orientation().transformInverse(yawImpulse);
            chassis.applyAngularImpulse(yawImpulse);
        }
        phase+=input.throttle()*.25;
        int index=0;
        for(Module module:modules) {
            if(module.detached || module.joint==null || !module.joint.isValid()) { index++; continue; }
            if(module.weapon) {
                if(input.weapon()) module.angle+=.5;
                module.joint.setMotor(RotaryConstraintHandle.DEFAULT_AXIS,Math.IEEEremainder(module.angle,Math.PI*2),
                        input.weapon()?18000:1000,input.weapon()?900:200,true,18000);
            } else if(walking) {
                double stride=input.throttle()+steer*module.side*.7;
                module.angle=Math.sin(phase+(index==0 || index==3 ? 0:Math.PI))*.75*Math.abs(stride);
                module.joint.setMotor(RotaryConstraintHandle.DEFAULT_AXIS,module.angle,11000,650,true,9000);
            } else {
                module.angle-=(input.throttle()+steer*module.side*.65)*.35;
                module.joint.setMotor(RotaryConstraintHandle.DEFAULT_AXIS,Math.IEEEremainder(module.angle,Math.PI*2),5000,350,true,6000);
            }
            index++;
        }
    }

    /** Shared native-contact damage path for live sessions and gameplay integration tests. */
    public CombatResult hit(PracticeRig attacker,SablePhysicsAdapter.ImpactContact contact,
                            DamageResolver resolver,long tick,AABB arena) {
        UUID self=physics.resolve(level,contact.self()).orElse(null),other=physics.resolve(level,contact.other()).orElse(null);
        if(attacker==this || !owns(self) || !attacker.owns(other)
                || !(level.getBlockEntity(contact.self()) instanceof BattleNodeEntity node) || node.health()<=0)return null;
        Vec3 point=physics.worldPosition(level,contact.self());
        if(!arena.contains(point) || !arena.contains(physics.worldPosition(level,contact.other())))return null;
        var a=physics.bodyInfo(level,other);var b=physics.bodyInfo(level,self);
        if(a.isEmpty()||b.isEmpty())return null;
        double mass=1/(1/a.get().mass()+1/b.get().mass());
        double damage=resolver.process(attacker.root,root,contact.self().toShortString(),tick,contact.speed(),0,mass,
                ((BattleNodeBlock)node.getBlockState().getBlock()).toughness(),true);
        if(damage<=0)return null;
        if(damage>=node.health()&&resolver.claimDestruction(tick,1)==0)damage=Math.max(0,node.health()-.01);
        if(damage<=0)return null;
        boolean failed=node.hurt(damage),detached=failed&&detachAt(contact.self());
        boolean aggressive=a.get().linearVelocity().dot(b.get().position().subtract(a.get().position()))>.05
                || a.get().angularVelocity().lengthSqr()>.25;
        return new CombatResult(point,damage,failed,detached,aggressive);
    }
    public record CombatResult(Vec3 point,double damage,boolean failed,boolean detached,boolean aggressive){}

    /** Called only after an opponent-caused hit has exhausted a registered node. */
    public boolean detachAt(BlockPos hit) {
        UUID hitBody=physics.resolve(level,hit).orElse(null);
        for(Module module:modules) if(!module.detached && (module.mount.equals(hit) || module.body.equals(hitBody))) {
            module.detached=true;
            if(module.joint!=null && module.joint.isValid()) module.joint.remove();
            module.joint=null;
            // Existing momentum is preserved. This is the same colliding physical body, not particles.
            return true;
        }
        return false;
    }
    private void connect() {
        for(Module module:modules) if(!module.detached && (module.joint==null || !module.joint.isValid())) {
            Vector3d axis=module.weapon?new Vector3d(0,1,0):new Vector3d(1,0,0);
            module.joint=SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(body(root),body(module.body),
                    new RotaryConstraintConfiguration(center(core).add(module.dx,module.dy,module.dz),center(module.anchor),axis,axis));
            if(module.joint==null) throw new IllegalStateException("Native practice joint could not be created.");
            module.joint.setContactsEnabled(false);
        }
    }
    private static Vector3d center(BlockPos pos) { return new Vector3d(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5); }

    /** Capture all current geometry, including creative workshop edits, before a round. */
    public void capture() {
        if(!loaded() || !jointsValid()) throw new IllegalStateException("Load and repair every starter module before fighting.");
        CompoundTag saved=new CompoundTag(); ListTag poses=new ListTag(), blocks=new ListTag(); int count=0,cores=0;
        for(UUID id:ids()) {
            poses.add(physics.capturePose(level,id));
            if(!ids().containsAll(physics.connectedBodies(level,id))) throw new IllegalStateException("Extra connected bodies require the open-workshop registration workflow.");
            for(BlockPos pos:physics.plotBlocks(level,id,512)) {
                if(++count>512) throw new IllegalStateException("Practice bot exceeds 512 blocks; use an open workshop instead.");
                BlockState state=level.getBlockState(pos);
                if(state.is(BattleBlocks.TRANSPONDER.get())) { cores++; if(!pos.equals(core)) throw new IllegalStateException("Keep one original transponder."); }
                CompoundTag block=new CompoundTag();block.putLong("Pos",pos.asLong());block.putUUID("Body",id);
                block.putString("Block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                if(level.getBlockEntity(pos) instanceof BattleNodeEntity node) block.putDouble("Health",node.health());
                blocks.add(block);
            }
        }
        if(cores!=1) throw new IllegalStateException("Restore the original transponder before fighting.");
        saved.put("Poses",poses);saved.put("Blocks",blocks);checkpoint=saved;
    }

    /** Same bodies, same items; only owned constraints, poses and combat-node integrity are restored. */
    public void restore() {
        if(checkpoint==null || !loaded()) throw new IllegalStateException("Practice recovery is waiting for its original bodies.");
        Map<UUID,Integer> counts=new HashMap<>();
        for(Tag entry:checkpoint.getList("Blocks",Tag.TAG_COMPOUND)) {
            CompoundTag block=(CompoundTag)entry;BlockPos pos=BlockPos.of(block.getLong("Pos"));UUID id=block.getUUID("Body");
            if(!physics.resolve(level,pos).filter(id::equals).isPresent() || !BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(block.getString("Block")))
                throw new IllegalStateException("A structural block changed outside combat at "+pos.toShortString()+" (expected "+block.getString("Block")+"). Restore that block, then retry; your design was preserved.");
            counts.merge(id,1,Integer::sum);
        }
        for(UUID id:ids()) if(physics.plotBlocks(level,id,512).size()!=counts.getOrDefault(id,-1))
            throw new IllegalStateException("Block count changed outside combat. Repair the edit before retrying recovery.");
        freeze(true);
        for(Module module:modules) { if(module.joint!=null && module.joint.isValid()) module.joint.remove(); module.joint=null; }
        for(Tag entry:checkpoint.getList("Blocks",Tag.TAG_COMPOUND)) {
            CompoundTag block=(CompoundTag)entry;
            if(block.contains("Health") && level.getBlockEntity(BlockPos.of(block.getLong("Pos"))) instanceof BattleNodeEntity node) node.restore(block.getDouble("Health"));
        }
        for(Tag entry:checkpoint.getList("Poses",Tag.TAG_COMPOUND)) {
            CompoundTag pose=(CompoundTag)entry;
            if(!physics.restorePose(level,pose.getUUID("body"),pose)) throw new IllegalStateException("Native practice pose failed validation.");
        }
        for(Module module:modules) { module.detached=false;module.angle=0; }
        phase=0;connect();
        if(!jointsValid()) throw new IllegalStateException("A repaired joint is invalid.");
    }
    public void verifySaved() { if(!physics.verifiedSave(level,ids())) throw new IllegalStateException("Practice recovery could not be saved."); }

    public CompoundTag save() {
        CompoundTag tag=new CompoundTag();tag.putUUID("Owner",owner);tag.putUUID("Root",root);tag.putLong("Core",core.asLong());tag.putBoolean("Walking",walking);
        if(checkpoint!=null)tag.put("Checkpoint",checkpoint.copy());
        ListTag list=new ListTag();
        for(Module module:modules) {
            CompoundTag m=new CompoundTag();m.putUUID("Body",module.body);m.putLong("Anchor",module.anchor.asLong());m.putLong("Mount",module.mount.asLong());
            m.putInt("Dx",module.dx);m.putInt("Dy",module.dy);m.putInt("Dz",module.dz);m.putInt("Side",module.side);m.putBoolean("Weapon",module.weapon);m.putBoolean("Detached",module.detached);list.add(m);
        }
        tag.put("Modules",list);return tag;
    }
    public static PracticeRig load(ServerLevel level,CompoundTag tag) {
        if(!tag.hasUUID("Owner") || !tag.hasUUID("Root")) throw new IllegalStateException("Invalid practice identity.");
        PracticeRig rig=new PracticeRig(level,tag.getUUID("Owner"),tag.getUUID("Root"),BlockPos.of(tag.getLong("Core")),tag.getBoolean("Walking"));
        var list=tag.getList("Modules",Tag.TAG_COMPOUND);
        if(list.size()!=5) throw new IllegalStateException("Invalid practice module count.");
        for(Tag value:list) {
            CompoundTag m=(CompoundTag)value;
            if(!m.hasUUID("Body") || Math.abs(m.getInt("Dx"))>8 || Math.abs(m.getInt("Dy"))>8 || Math.abs(m.getInt("Dz"))>8)
                throw new IllegalStateException("Invalid practice module attachment.");
            Module module=new Module(m.getUUID("Body"),BlockPos.of(m.getLong("Anchor")),m.getInt("Dx"),m.getInt("Dy"),m.getInt("Dz"),m.getInt("Side"),m.getBoolean("Weapon"),BlockPos.of(m.getLong("Mount")));
            module.detached=m.getBoolean("Detached");rig.modules.add(module);
        }
        if(rig.ids().size()!=6)throw new IllegalStateException("Duplicate practice body identity.");
        rig.checkpoint=tag.getCompound("Checkpoint").copy();return rig;
    }
    /** Only used to roll back a newly generated, never-edited prefab after failed construction. */
    private void disposeStarter() {
        for(Module module:modules) if(module.joint!=null && module.joint.isValid())module.joint.remove();
        freeze(false);var container=SubLevelContainer.getContainer(level);
        for(UUID id:ids()) if(physics.loaded(level,id)) {
            ServerSubLevel body=body(id);container.removeForceLoadTicket(body,TICKET,owner);container.removeSubLevel(body,SubLevelRemovalReason.REMOVED);
        }
        container.processSubLevelRemovals();
    }
    private static final class Module {
        final UUID body; final BlockPos anchor,mount;final int dx,dy,dz,side;final boolean weapon;
        boolean detached;double angle;RotaryConstraintHandle joint;
        Module(UUID body,BlockPos anchor,int dx,int dy,int dz,int side,boolean weapon,BlockPos mount) {
            this.body=body;this.anchor=anchor;this.dx=dx;this.dy=dy;this.dz=dz;this.side=side;this.weapon=weapon;this.mount=mount;
        }
    }
}
