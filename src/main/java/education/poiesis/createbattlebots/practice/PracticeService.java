package education.poiesis.createbattlebots.practice;

import education.poiesis.createbattlebots.CreateBattleBots;
import education.poiesis.createbattlebots.block.BattleNodeBlock;
import education.poiesis.createbattlebots.block.BattleNodeEntity;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import education.poiesis.createbattlebots.core.*;
import education.poiesis.createbattlebots.network.PracticeNetwork;
import education.poiesis.createbattlebots.server.BattleService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** The new-player vertical slice; completely separate from users' ordinary worlds and tournament registries. */
public final class PracticeService {
    public static final ResourceKey<Level> DIMENSION=ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(CreateBattleBots.MOD_ID,"workshop"));
    public static final String RETURN="createbattlebots:practice_return";
    public static final int TUTORIAL=1,WORKSHOP=2,COUNTDOWN=3,ACTIVE=4,RESULT=5,RECOVERY=6,DRIVING=7,LOADING=8;
    private static final Map<ServerLevel,PracticeService> SERVICES=new IdentityHashMap<>();
    private static final Map<UUID,Long> ACTIONS=new HashMap<>();
    private static final TicketType<UUID> CHUNK_TICKET=TicketType.create("battlebots_practice",UUID::compareTo,100);
    private final ServerLevel level;
    private final PracticeStore store;
    private final SablePhysicsAdapter physics=new SablePhysicsAdapter();
    private final Map<UUID,Session> sessions=new LinkedHashMap<>();
    private final Map<String,Session> parked=new LinkedHashMap<>();
    private final String storeError;

    private PracticeService(ServerLevel level){
        this.level=level;PracticeStore loaded=null;String error="";
        try {
            loaded=new PracticeStore(level);
            for(var entry:loaded.slots.entrySet()) {
                var record=entry.getValue();
                if(!record.getBoolean("Building"))parked.put(entry.getKey(),new Session(record,
                        PracticeRig.load(level,record.getCompound("Human")),PracticeRig.load(level,record.getCompound("Enemy")),now()));
            }
        }catch(RuntimeException failure){
            error=String.valueOf(failure.getMessage());parked.clear();
            CreateBattleBots.LOGGER.error("Practice disabled to preserve unreadable saved data",failure);
        }
        store=loaded;storeError=error;
    }
    private void requireHealthy(){if(!storeError.isEmpty()||store==null)throw new IllegalStateException("Practice is unavailable: "+storeError);}
    public static PracticeService get(ServerLevel level){return SERVICES.computeIfAbsent(level,PracticeService::new);}
    public static boolean isPractice(Level level){return level.dimension().equals(DIMENSION);}
    public static void action(ServerPlayer player,int action) {
        if(action<0||action>8||!player.isAlive())return;
        long now=player.server.overworld().getGameTime();
        if(now-ACTIONS.getOrDefault(player.getUUID(),-100L)<5)return;
        ACTIONS.put(player.getUUID(),now);
        try {
            ServerLevel world=player.server.getLevel(DIMENSION);
            if(world==null)throw new IllegalStateException("The practice dimension is unavailable. Restart after installing the mod.");
            PracticeService service=get(world);
            if(action!=5)service.requireHealthy();
            if(action<=1){service.enter(player,action==1);return;}
            if(action==5){service.leave(player);return;}
            Session session=service.sessions.get(player.getUUID());
            if(session==null||player.serverLevel()!=world)throw new IllegalArgumentException("Enter a starter workshop first.");
            switch(action) {
                case 2 -> service.start(session,player);
                case 3 -> { service.reset(session); service.start(session,player); }
                case 4 -> {
                    if(session.phase==ACTIVE||session.phase==COUNTDOWN)throw new IllegalArgumentException("Finish or reset the current round before editing.");
                    if(session.phase==RECOVERY||session.phase==LOADING)throw new IllegalArgumentException("Finish loading/recovery before editing.");
                    if(session.phase==TUTORIAL||session.phase==DRIVING)service.reset(session);
                    session.phase=WORKSHOP;session.human.freeze(true);session.enemy.freeze(true);session.input=ArcadeInput.IDLE;
                    service.balcony(player,session,true);service.save(session);
                }
                case 6 -> {if(session.phase==TUTORIAL){session.tutorial.skip();session.tutorialDone=true;service.reset(session);service.start(session,player);}}
                case 7 -> {service.reset(session);session.phase=WORKSHOP;service.balcony(player,session,false);}
                case 8 -> {
                    if(session.phase!=WORKSHOP&&session.phase!=RESULT)throw new IllegalArgumentException("Finish the round first.");
                    service.prepare(session);session.phase=DRIVING;session.human.freeze(false);service.balcony(player,session,false);service.save(session);
                }
                default -> {}
            }
            service.send(session,player);
        }catch(IllegalArgumentException|IllegalStateException error){
            player.sendSystemMessage(Component.literal(error.getMessage()).withStyle(ChatFormatting.RED));
        }
    }
    private void enter(ServerPlayer player,boolean walking) {
        requireHealthy();
        if(!education.poiesis.createbattlebots.config.BattleConfig.PRACTICE_ENABLED.get())throw new IllegalArgumentException("Starter practice is disabled by this server.");
        if(player.isPassenger())throw new IllegalArgumentException("Dismount before entering practice.");
        if(sessions.containsKey(player.getUUID()))throw new IllegalArgumentException("Leave the current workshop before choosing another starter. Your edits are saved.");
        if(sessions.size()>=education.poiesis.createbattlebots.config.BattleConfig.MAX_PRACTICE_SESSIONS.get())throw new IllegalArgumentException("This server has reached its active practice limit. Try again after a session finishes.");
        for(ServerLevel other:player.server.getAllLevels()) if(!isPractice(other)&&BattleService.get(other).isPilotLocked(player.getUUID()))
            throw new IllegalArgumentException("Finish your current tournament match before entering practice.");
        String key=PracticeStore.key(player.getUUID(),walking);
        CompoundTag record=store.slots.get(key);
        if(record==null) {
            Set<Integer> used=new HashSet<>();store.slots.values().forEach(r->used.add(r.getInt("Cell")));
            int cell=0;while(used.contains(cell))cell++;
            if(cell>=16)throw new IllegalArgumentException("This server's 16 saved starter workshops are full. Existing designs were preserved.");
            record=new CompoundTag();record.putUUID("Owner",player.getUUID());record.putBoolean("Walking",walking);record.putInt("Cell",cell);record.putBoolean("Building",true);
            store.slots.put(key,record);store.flush();
            BlockPos center=center(cell);holdChunks(player.getUUID(),center);buildFloor(center);
            PracticeRig human=PracticeRig.create(level,player.getUUID(),center.offset(0,3,8),walking,0);
            record.put("Human",human.save());store.flush();
            PracticeRig enemy=PracticeRig.create(level,player.getUUID(),center.offset(0,3,-8),false,Math.PI);
            record.put("Enemy",enemy.save());record.putBoolean("Building",false);store.flush();
            Session session=new Session(record,human,enemy,now());
            parked.put(key,session);sessions.put(player.getUUID(),session);
        } else {
            if(record.getBoolean("Building"))throw new IllegalStateException("An interrupted starter build was preserved in cell "+record.getInt("Cell")+". Ask the operator to inspect it; it will not be overwritten.");
            Session existing=parked.get(key);
            if(existing==null)throw new IllegalStateException("This workshop requires operator recovery; its saved bodies were retained.");
            existing.phase=LOADING;existing.loadUntil=now()+200;sessions.put(player.getUUID(),existing);
        }
        Session session=sessions.get(player.getUUID());
        if(!isPractice(player.level())) {
            CompoundTag back=new CompoundTag();back.putString("Dimension",player.level().dimension().location().toString());
            back.putDouble("X",player.getX());back.putDouble("Y",player.getY());back.putDouble("Z",player.getZ());back.putFloat("Yaw",player.getYRot());back.putFloat("Pitch",player.getXRot());
            player.getPersistentData().put(RETURN,back);player.server.getPlayerList().saveAll();
        }
        balcony(player,session,false);send(session,player);
    }
    private static BlockPos center(int cell){return new BlockPos((cell%4)*96,63,(cell/4)*96);}
    private void buildFloor(BlockPos center) {
        for(int x=-23;x<=23;x++)for(int z=-23;z<=23;z++) {
            var state=(Math.abs(x)==20||Math.abs(z)==20)?Blocks.YELLOW_CONCRETE.defaultBlockState()
                    :((Math.floorDiv(x,4)+Math.floorDiv(z,4))&1)==0?Blocks.GRAY_CONCRETE.defaultBlockState():Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
            level.setBlock(center.offset(x,0,z),state,3);
        }
        for(int x=-3;x<=3;x++)for(int z=-2;z<=2;z++) {
            level.setBlock(center.offset(x,0,z+8),Blocks.CYAN_CONCRETE.defaultBlockState(),3);
            level.setBlock(center.offset(x,0,z-8),Blocks.ORANGE_CONCRETE.defaultBlockState(),3);
        }
        // No solid wall: the marked perimeter is a real ring-out edge, with a three-block safety apron.
        for(int x=-4;x<=4;x++)level.setBlock(center.offset(x,1,23),Blocks.GLASS.defaultBlockState(),3);
    }
    private void holdChunks(UUID owner,BlockPos center) {
        ChunkPos middle=new ChunkPos(center);
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)
            level.getChunkSource().addRegionTicket(CHUNK_TICKET,new ChunkPos(middle.x+x,middle.z+z),2,owner);
    }
    private void balcony(ServerPlayer player,Session session,boolean editing) {
        Vec3 position=editing&&session.human.loaded()?session.human.position().add(0,3,4):Vec3.atCenterOf(session.center).add(0,2,21);
        player.stopRiding();player.closeContainer();player.stopUsingItem();
        player.teleportTo(level,position.x,position.y,position.z,Set.of(),180,15);player.fallDistance=0;
    }
    public void input(ServerPlayer player,int bits) {
        Session session=sessions.get(player.getUUID());
        if(session==null||player.serverLevel()!=level||bits<0||bits>31||session.inputTick==now())return;
        if(session.phase!=ACTIVE&&session.phase!=TUTORIAL&&session.phase!=DRIVING)return;
        session.input=ArcadeInput.buttons(bits);session.inputTick=now();
    }
    private void prepare(Session session) {
        if(!session.human.loaded()||!session.enemy.loaded())throw new IllegalStateException("The workshop is still loading.");
        if(session.phase==RECOVERY||session.phase==LOADING)throw new IllegalStateException("Finish recovery before fighting.");
        if(session.human.detachedCount()>0||session.enemy.detachedCount()>0)reset(session);
        session.human.capture();session.enemy.capture();save(session);
    }
    private void start(Session session,ServerPlayer player) {
        if(session.phase==ACTIVE||session.phase==COUNTDOWN)throw new IllegalArgumentException("A round is already running.");
        // Tutorial/free-drive can leave the staging pad; a round always starts from the saved staging pose.
        if(session.phase==TUTORIAL||session.phase==DRIVING)reset(session);
        prepare(session);
        if(!session.bounds.contains(session.human.position())||!session.bounds.contains(session.enemy.position()))
            throw new IllegalArgumentException("Both robots must be inside the yellow arena boundary.");
        session.engine=new MatchEngine(UUID.randomUUID(),session.human.root,session.enemy.root,ControlMode.HUMAN,
                MatchRules.defaults().withTiming(60,2400));
        session.damage=new DamageResolver(session.human.root,session.enemy.root,
                PracticeRig.DAMAGE_SETTINGS);
        session.pilot=new ScrapbotPilot();session.input=ArcadeInput.IDLE;session.inputTick=-1;
        session.phase=COUNTDOWN;session.result="";session.hits=0;session.lastHit="";
        session.human.freeze(true);session.enemy.freeze(true);session.tutorialDone=true;
        save(session); // Durable pre-match graph and all original body identities precede movement.
        session.engine.begin(now());balcony(player,session,false);
    }
    private void reset(Session session) {
        session.input=ArcadeInput.IDLE;
        if(session.engine!=null&&(session.phase==ACTIVE||session.phase==COUNTDOWN)) {
            session.engine.surrender(session.human.root,now());session.result="Round forfeited by reset.";
        }
        session.phase=RECOVERY;save(session);
        session.human.restore();session.enemy.restore();
        session.human.verifySaved();session.enemy.verifySaved();
        session.phase=WORKSHOP;session.engine=null;session.message="Both robots repaired. Test drive, edit, or fight again.";save(session);
    }
    public void tick() {
        if(!storeError.isEmpty()||store==null){physics.discardContacts(level);return;}
        // Rebuild our transient native joints after restart, even before the owner reconnects.
        // Keep each rig object while parked so re-entry cannot create duplicate constraints.
        for(Session idle:parked.values()) {
            if(sessions.get(idle.owner)==idle || idle.phase!=LOADING)continue;
            try {
                if(now()%40==0)holdChunks(idle.owner,idle.center);
                idle.human.freeze(true);idle.enemy.freeze(true);
                if(idle.human.loaded()&&idle.enemy.loaded()) {
                    idle.human.restore();idle.enemy.restore();idle.human.verifySaved();idle.enemy.verifySaved();
                    idle.phase=WORKSHOP;save(idle);idle.human.releaseLoaded();idle.enemy.releaseLoaded();
                }else if(now()>idle.loadUntil) {
                    idle.phase=RECOVERY;idle.message="Saved bodies need recovery; re-enter this workshop to retry.";
                    CreateBattleBots.LOGGER.warn("Practice cell {} retained for recovery",idle.record.getInt("Cell"));
                }
            }catch(RuntimeException failure){
                idle.phase=RECOVERY;idle.message=failure.getMessage();
                CreateBattleBots.LOGGER.error("Preserved parked workshop recovery",failure);
            }
            break; // At most one expensive checkpoint/save in a server tick.
        }
        if(sessions.isEmpty()) {physics.discardContacts(level);return;}
        List<SablePhysicsAdapter.ImpactContact> contacts=physics.drainContacts(level,512);
        for(Session session:List.copyOf(sessions.values())) {
            ServerPlayer player=level.getServer().getPlayerList().getPlayer(session.owner);
            try {
                if(player==null||player.serverLevel()!=level) {
                    try{suspend(session);}finally{sessions.remove(session.owner);}continue;
                }
                if(now()%40==0)holdChunks(session.owner,session.center);
                if(player.getY()<50||!session.bounds.inflate(12).contains(player.position()))balcony(player,session,false);
                if(session.phase==LOADING) {
                    if(session.human.loaded()&&session.enemy.loaded()) {
                        session.human.holdLoaded();session.enemy.holdLoaded();session.human.restore();session.enemy.restore();
                        session.phase=session.tutorialDone?WORKSHOP:TUTORIAL;
                        if(!session.tutorialDone)session.human.freeze(false);
                        save(session);
                    } else if(now()>session.loadUntil)throw new IllegalStateException("Some saved bodies are not loaded. Their data was retained; use Retry recovery or leave.");
                }
                if(session.phase==TUTORIAL||session.phase==DRIVING) {
                    ArcadeInput input=ArcadeInput.fresh(session.input,session.inputTick,now());session.human.drive(input,now());
                    if(!session.bounds.contains(session.human.position())) {
                        session.human.restore();session.human.freeze(false);session.message="Ring-out! Restored to your pad. Try steering before the yellow edge.";
                    }
                    if(session.phase==TUTORIAL) {
                        Vec3 p=session.human.position();session.tutorial.observe(p.x,p.z,session.human.heading(),session.human.weaponSpeed());
                        if(session.tutorial.step()==3) {session.tutorialDone=true;reset(session);start(session,player);}
                    }
                }
                if(session.phase==ACTIVE||session.phase==COUNTDOWN) {
                    if(session.phase==ACTIVE) {
                        for(var contact:contacts)impact(session,contact);
                        ArcadeInput input=ArcadeInput.fresh(session.input,session.inputTick,now());session.human.drive(input,now());
                        Vec3 p=session.enemy.position(),target=session.human.position();var f=session.enemy.forward();
                        if(now()%4==0)session.ai=session.pilot.decide(now(),p.x,p.z,f.x,f.z,target.x,target.z,session.center.getX()+.5,session.center.getZ()+.5);
                        session.enemy.drive(session.ai,now());
                    }
                    session.engine.tick(now(),observe(session.human,session,player.isAlive(),Math.abs(session.input.throttle())>.1),
                            observe(session.enemy,session,true,true));
                    if(session.engine.state()==MatchState.ACTIVE&&session.phase==COUNTDOWN) {
                        session.phase=ACTIVE;session.human.freeze(false);session.enemy.freeze(false);
                        level.playSound(null,session.center,SoundEvents.NOTE_BLOCK_BELL.value(),SoundSource.BLOCKS,1,1.5f);
                    }
                    if(session.engine.state()==MatchState.RESTORING) {
                        session.phase=RESULT;session.restoreAt=now()+40;
                        var result=session.engine.result().orElseThrow();
                        session.result=result.winner().map(id->id.equals(session.human.root)?"VICTORY":"DEFEAT").orElse("DRAW")
                                +" - "+result.reason().name().replace('_',' ')+" | "+session.hits+" impacts, "+(session.human.detachedCount()+session.enemy.detachedCount())+" parts detached";
                        session.human.freeze(true);session.enemy.freeze(true);save(session);
                        player.sendSystemMessage(Component.literal(session.result+". Press B for rematch or workshop.").withStyle(ChatFormatting.GOLD));
                    }
                    session.engine.drainEvents();
                }
                if(session.phase==RESULT&&session.engine!=null&&now()>=session.restoreAt) {
                    String result=session.result;reset(session);session.result=result;session.phase=RESULT;save(session);
                }
                if(now()%2==0)send(session,player);
            }catch(RuntimeException error) {
                session.phase=RECOVERY;session.input=ArcadeInput.IDLE;session.message=error.getMessage()==null?"Practice paused safely.":error.getMessage();
                session.human.freeze(true);session.enemy.freeze(true);
                try{save(session);}catch(RuntimeException ignored){}
                CreateBattleBots.LOGGER.error("Practice paused for {}: {}",session.owner,session.message);
                if(player!=null)send(session,player);
            }
        }
    }
    private BotStatus observe(PracticeRig rig,Session session,boolean connected,boolean commanded) {
        return new BotStatus(rig.health()>0,session.bounds.contains(rig.position()),connected,commanded,rig.attachedLegs()>0,rig.loaded()&&rig.jointsValid());
    }
    private void impact(Session session,SablePhysicsAdapter.ImpactContact contact) {
        UUID self=physics.resolve(level,contact.self()).orElse(null),other=physics.resolve(level,contact.other()).orElse(null);
        PracticeRig victim=session.human.owns(self)?session.human:session.enemy.owns(self)?session.enemy:null;
        PracticeRig attacker=session.human.owns(other)?session.human:session.enemy.owns(other)?session.enemy:null;
        if(victim==null||attacker==null||victim==attacker)return;
        var result=victim.hit(attacker,contact,session.damage,now(),session.bounds);
        if(result==null)return;
        Vec3 point=result.point();double damage=result.damage();boolean detached=result.detached();session.hits++;
        if(result.failed())session.engine.recordComponentFailure(attacker.root,victim.root,contact.self().toShortString(),detached?6:3,now());
        if(result.aggressive())session.engine.recordEngagement(attacker.root,victim.root,now());
        session.lastHit=detached?(victim==session.human?"YOUR MODULE DETACHED":"ENEMY MODULE DETACHED")
                :"Impact "+Math.round(contact.speed())+" m/s | "+String.format(java.util.Locale.ROOT,"%.1f",damage)+" damage";
        session.hitUntil=now()+35;
        level.sendParticles(detached?ParticleTypes.SMOKE:ParticleTypes.ELECTRIC_SPARK,point.x,point.y,point.z,detached?18:7,.3,.3,.3,.05);
        if(now()-session.soundTick>3) {
            level.playSound(null,point.x,point.y,point.z,detached?SoundEvents.ANVIL_BREAK:SoundEvents.ANVIL_LAND,SoundSource.BLOCKS,.6f,detached?.8f:1.6f);session.soundTick=now();
        }
    }
    private void save(Session session) {
        CompoundTag record=session.record;record.put("Human",session.human.save());record.put("Enemy",session.enemy.save());
        record.putBoolean("TutorialDone",session.tutorialDone);record.putInt("Phase",session.phase);record.putString("Result",session.result);
        store.slots.put(PracticeStore.key(session.owner,session.human.walking),record);store.flush();
    }
    private void send(Session session,ServerPlayer player) {
        String message=switch(session.phase) {
            case TUTORIAL -> switch(session.tutorial.step()) {case 0->"1/3 - Hold W: drive three blocks. S reverses.";case 1->"2/3 - Hold A or D: turn your robot.";default->"3/3 - Hold Space: spin up your saw, then fight!";};
            case WORKSHOP -> player.isCreative()?"Edit your cyan-pad robot. Press B to test drive or fight.":"Press B to test drive or fight. Creative mode enables starter editing.";
            case DRIVING -> "Free practice - WASD drive, Space saw. Press B to fight or edit.";
            case COUNTDOWN -> "Get ready - W/S drive, A/D steer, hold Space for saw.";
            case ACTIVE -> now()<session.hitUntil?session.lastHit:"Break their core, disable their legs, or push them outside yellow.";
            case RESULT -> session.result;
            case LOADING -> "Loading your saved robot bodies...";
            default -> session.message;
        };
        Vec3 p=session.human.loaded()?session.human.position():Vec3.atCenterOf(session.center);double heading=session.human.loaded()?session.human.heading():0;
        int seconds=session.engine==null?0:(int)((session.phase==COUNTDOWN?session.engine.countdownRemainingTicks(now()):session.engine.timeRemainingTicks(now()))+19)/20;
        PracticeNetwork.send(player,new PracticeNetwork.State(session.phase,seconds,(int)session.human.health(),(int)session.enemy.health(),
                session.human.detachedCount(),message.length()>350?message.substring(0,350):message,p.x,p.y,p.z,heading,
                session.phase==TUTORIAL||session.phase==ACTIVE||session.phase==COUNTDOWN||session.phase==DRIVING||session.phase==RESULT,
                session.phase==WORKSHOP||session.phase==DRIVING||session.phase==RESULT&&session.engine==null));
    }
    public void leave(ServerPlayer player) {
        if(!isPractice(player.level()) && !player.getPersistentData().contains(RETURN))return;
        Session session=sessions.remove(player.getUUID());
        if(session!=null)try{suspend(session);}catch(RuntimeException error){
            CreateBattleBots.LOGGER.error("Preserved failed practice recovery while returning player",error);
            player.sendSystemMessage(Component.literal("Your workshop needs recovery; the saved design was retained."));
        }
        CompoundTag back=player.getPersistentData().getCompound(RETURN);
        var id=ResourceLocation.tryParse(back.getString("Dimension"));
        ServerLevel destination=id==null?player.server.overworld():player.server.getLevel(ResourceKey.create(Registries.DIMENSION,id));
        if(destination==null||isPractice(destination))destination=player.server.overworld();
        BlockPos spawn=destination.getSharedSpawnPos();
        double x=back.contains("X")?back.getDouble("X"):spawn.getX()+.5;
        double y=back.contains("Y")?back.getDouble("Y"):spawn.getY()+1;
        double z=back.contains("Z")?back.getDouble("Z"):spawn.getZ()+.5;
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>29999984||Math.abs(z)>29999984){x=spawn.getX()+.5;y=spawn.getY()+1;z=spawn.getZ()+.5;}
        player.teleportTo(destination,x,y,z,Set.of(),back.getFloat("Yaw"),back.getFloat("Pitch"));player.fallDistance=0;
        player.getPersistentData().remove(RETURN);PracticeNetwork.send(player,PracticeNetwork.State.closed());
    }
    private void suspend(Session session) {
        try {
            if(session.phase!=WORKSHOP&&session.phase!=RECOVERY&&session.human.loaded()&&session.enemy.loaded())reset(session);
            else if(session.phase==WORKSHOP){session.human.capture();session.enemy.capture();}
            session.human.freeze(true);session.enemy.freeze(true);save(session);
        }finally{session.human.releaseLoaded();session.enemy.releaseLoaded();}
    }
    public static void logout(ServerPlayer player) { ACTIONS.remove(player.getUUID()); }
    public static void shutdown() {
        for(var service:SERVICES.values())for(var session:service.parked.values())try{service.suspend(session);}catch(RuntimeException error){CreateBattleBots.LOGGER.error("Preserved practice recovery",error);}
    }
    public static void clear(){SERVICES.clear();ACTIONS.clear();}
    public boolean canBreak(ServerPlayer player,BlockPos pos) {
        Session session=sessions.get(player.getUUID());
        return session!=null && canEdit(player,pos) && !session.human.isAssemblyAnchor(pos);
    }
    public boolean canEdit(ServerPlayer player,BlockPos pos) {
        Session session=sessions.get(player.getUUID());
        if(session==null||session.phase!=WORKSHOP||!player.isCreative())return false;
        UUID id=physics.resolve(level,pos).orElse(null);
        return id!=null&&session.human.owns(id);
    }
    public boolean isDriving(ServerPlayer player) {
        Session s=sessions.get(player.getUUID());return s!=null&&s.phase!=WORKSHOP;
    }
    private long now(){return level.getGameTime();}
    private static final class Session {
        final UUID owner;final BlockPos center;final AABB bounds;final CompoundTag record;final PracticeRig human,enemy;
        final PracticeTutorial tutorial=new PracticeTutorial();ScrapbotPilot pilot=new ScrapbotPilot();
        ArcadeInput input=ArcadeInput.IDLE,ai=ArcadeInput.IDLE;long inputTick=-1,loadUntil,restoreAt,hitUntil,soundTick=-100;
        int phase=LOADING,hits;boolean tutorialDone;String result="",message="",lastHit="";MatchEngine engine;DamageResolver damage;
        Session(CompoundTag record,PracticeRig human,PracticeRig enemy,long now) {
            this.record=record;this.owner=record.getUUID("Owner");this.center=center(record.getInt("Cell"));
            this.bounds=new AABB(center.getX()-20,62,center.getZ()-20,center.getX()+21,95,center.getZ()+21);
            this.human=human;this.enemy=enemy;this.tutorialDone=record.getBoolean("TutorialDone");this.loadUntil=now+200;
        }
    }
}
