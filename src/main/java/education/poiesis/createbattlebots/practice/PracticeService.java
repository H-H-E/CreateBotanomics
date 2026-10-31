package education.poiesis.createbattlebots.practice;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import education.poiesis.createbattlebots.CreateBattleBots;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import education.poiesis.createbattlebots.config.BattleConfig;
import education.poiesis.createbattlebots.server.BattleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Disposable stock-machine playground, isolated from all player worlds and inventory contents. */
public final class PracticeService {
    public static final ResourceKey<Level> WORLD = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(CreateBattleBots.MOD_ID, "workshop"));
    public static final String RETURN = "createbattlebots_practice_return";
    private static final String COMPLETED = "createbattlebots_tutorial_done";
    public static final int ENTER = 0, FIGHT = 1, REPAIR = 2, EXIT = 3, DESIGN = 4,
            DIFFICULTY = 5, SKIP = 6, PAUSE = 7, RESUME = 8, TUNE = 9, SURRENDER = 10;
    public static final int TUTORIAL = 1, GARAGE = 2, COUNTDOWN = 3, ACTIVE = 4,
            REPAIRING = 5, RESULTS = 6, PAUSED = 7, ERROR = 8;
    private static final Map<MinecraftServer, PracticeService> SERVICES = new IdentityHashMap<>();
    private final ServerLevel level;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final SablePhysicsAdapter physics = new SablePhysicsAdapter();
    private long ticks;
    public static PracticeService get(MinecraftServer server) {
        ServerLevel world = server.getLevel(WORLD);
        if (world == null) throw new IllegalStateException("The Battle Bots workshop dimension is unavailable. Restart this world after installing the mod.");
        return SERVICES.computeIfAbsent(server, ignored -> new PracticeService(world));
    }
    private PracticeService(ServerLevel level) { this.level = level; }
    public static boolean isWorkshop(Level level) { return level.dimension().equals(WORLD); }

    public static final class Session {
        public final UUID owner;
        public final int slot;
        public final BlockPos center;
        public PracticeBot playerBot, opponent;
        public final PracticeRules.Brain brain = new PracticeRules.Brain();
        public int phase, beforePause, tutorial, turning, design, tune, wins, losses, roundTicks, countdown, restoreWait;
        public boolean easy = true;
        public long lastInput = -100;
        public PracticeRules.Input input = PracticeRules.Input.STOP;
        public String headline = "Learn the controls", feedback = "Your robot is green. Scrapbot is red.";
        public Vec3 tutorialStart;
        Session(UUID owner, int slot) {
            this.owner = owner; this.slot = slot; center = new BlockPos(slot * 96, 64, 0);
        }
        public boolean canDrive() { return phase == TUTORIAL || phase == GARAGE || phase == ACTIVE; }
        public boolean canChange() { return phase == GARAGE || phase == RESULTS || phase == ERROR; }
    }

    public void action(ServerPlayer player, int action) {
        if (action < ENTER || action > SURRENDER) return;
        long tick = player.server.getTickCount();
        Long last = lastAction.get(player.getUUID());
        if (last != null && tick - last < 6) return;
        lastAction.put(player.getUUID(), tick);
        try {
            if (action == EXIT) { exit(player); return; }
            if (action == ENTER) {
                if (!sessions.containsKey(player.getUUID())) enter(player);
                Session s = sessions.get(player.getUUID());
                if (s != null) send(player, s);
                return;
            }
            Session s = session(player);
            if (s == null) return;
            switch (action) {
                case FIGHT -> {
                    if (s.phase != GARAGE && s.phase != RESULTS) return;
                    restore(s); s.phase = COUNTDOWN; s.countdown = PracticeRules.COUNTDOWN_TICKS;
                    s.headline = "Ready?"; s.feedback = "Destroy the core, remove the running gear, or push Scrapbot off the edge.";
                }
                case REPAIR -> {
                    if (s.phase == ACTIVE || s.phase == COUNTDOWN || s.phase == PAUSED) return;
                    restore(s); s.phase = s.tutorial < 3 ? TUTORIAL : GARAGE; s.playerBot.release();
                    s.feedback = "All original parts reattached. Test your controls, then fight.";
                }
                case DESIGN -> {
                    if (!s.canChange()) return;
                    s.design = (s.design + 1) % 3; replaceBots(s); s.phase = GARAGE; s.playerBot.release();
                    s.feedback = "Fresh starter design. Your ordinary Create builds remain untouched.";
                }
                case TUNE -> {
                    if (!s.canChange()) return;
                    s.tune = (s.tune + 1) % 3; s.playerBot.tune(s.tune);
                    s.feedback = switch (s.tune) { case 1 -> "Armored: reduced core damage; slower weapon.";
                        case 2 -> "Overclocked: faster weapon; lower driving speed."; default -> "Balanced: standard drive and weapon speeds."; };
                }
                case DIFFICULTY -> { if (!s.canChange()) return; s.easy = !s.easy; }
                case SKIP -> { if (s.phase != TUTORIAL) return; completeTutorial(player, s); }
                case PAUSE -> {
                    if (s.phase == PAUSED || s.phase == ERROR) return;
                    s.beforePause = s.phase; s.phase = PAUSED; s.input = PracticeRules.Input.STOP;
                    s.playerBot.freeze(); s.opponent.freeze();
                }
                case RESUME -> {
                    if (s.phase != PAUSED) return;
                    s.phase = s.beforePause; s.lastInput = -100;
                    if (s.canDrive()) s.playerBot.release();
                    if (s.phase == ACTIVE) s.opponent.release();
                }
                case SURRENDER -> { if (s.phase == ACTIVE) finish(s, "You surrendered", false, false); }
                default -> { return; }
            }
            send(player, s);
        } catch (RuntimeException error) {
            CreateBattleBots.LOGGER.warn("Practice action failed for {}", player.getUUID(), error);
            Session s = sessions.get(player.getUUID());
            if (s == null) {
                restorePlayer(player);
                player.sendSystemMessage(Component.literal("Battle Bots: " + safe(error.getMessage())));
                PacketDistributor.sendToPlayer(player, PracticeNetwork.State.closed());
            } else {
                s.phase = ERROR; s.feedback = safe(error.getMessage());
                try { s.playerBot.freeze(); s.opponent.freeze(); } catch (RuntimeException ignored) { }
                send(player, s);
            }
        }
    }
    public void input(ServerPlayer player, int flags) {
        Session s = session(player);
        if (s == null || !s.canDrive() || s.lastInput == ticks) return;
        try { s.input = PracticeRules.decode(flags); s.lastInput = ticks; }
        catch (IllegalArgumentException ignored) { s.input = PracticeRules.Input.STOP; }
    }
    private Session session(ServerPlayer player) {
        return isWorkshop(player.level()) && player.getPersistentData().contains(RETURN)
                ? sessions.get(player.getUUID()) : null;
    }
    private void enter(ServerPlayer player) {
        if (!BattleConfig.PRACTICE_ENABLED.get()) throw new IllegalStateException("Solo practice is disabled by this server.");
        if (isWorkshop(player.level())) { restorePlayer(player); throw new IllegalStateException("Recovered your previous visit. Open practice again."); }
        if (!player.isAlive() || player.isPassenger() || player.isSleeping()) throw new IllegalStateException("Dismount and stand safely before entering practice.");
        if (BattleService.get(player.serverLevel()).isProtected(player.blockPosition())
                || BattleService.get(player.serverLevel()).isAutonomousPilot(player))
            throw new IllegalStateException("Finish your current arena visit before entering practice.");
        int slot = 0;
        while (slot < BattleConfig.PRACTICE_SLOTS.get()) {
            final int candidate = slot;
            if (sessions.values().stream().noneMatch(s -> s.slot == candidate)) break;
            slot++;
        }
        if (slot >= BattleConfig.PRACTICE_SLOTS.get()) throw new IllegalStateException("All practice bays are occupied. Try again after another player exits.");
        Session s = new Session(player.getUUID(), slot);
        sessions.put(s.owner, s); // Track ownership before allocating any native bodies.
        try {
            prepareArena(s.center);
            replaceBots(s);
        } catch (RuntimeException failure) {
            close(s); sessions.remove(s.owner); throw failure;
        }
        CompoundTag home = new CompoundTag();
        home.putString("dimension", player.level().dimension().location().toString());
        home.putDouble("x", player.getX()); home.putDouble("y", player.getY()); home.putDouble("z", player.getZ());
        home.putFloat("yaw", player.getYRot()); home.putFloat("pitch", player.getXRot());
        home.putInt("mode", player.gameMode.getGameModeForPlayer().getId());
        player.getPersistentData().put(RETURN, home);
        // Save the return marker before changing dimension or game mode. Never replay an inventory.
        player.server.getPlayerList().saveAll();
        sessions.put(s.owner, s);
        s.tutorial = player.getPersistentData().getBoolean(COMPLETED) ? 3 : 0;
        s.phase = s.tutorial == 3 ? GARAGE : TUTORIAL;
        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(level, s.center.getX() + 0.5, 104, -27, Set.of(), 0, 54);
        s.playerBot.release();
        s.tutorialStart = s.playerBot.position();
    }
    private void prepareArena(BlockPos center) {
        // Only our dedicated dimension is ever generated/modified by this path.
        if (!isWorkshop(level)) throw new IllegalStateException("Practice may not modify a player world");
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            level.setChunkForced((center.getX() >> 4) + x, z, true);
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
            boolean edge = Math.abs(x) == 24 || Math.abs(z) == 24;
            var floor = edge ? Blocks.YELLOW_CONCRETE : (Math.abs(x) == 23 || Math.abs(z) == 23)
                    ? Blocks.BLACK_CONCRETE : ((x / 4 + z / 4) & 1) == 0 ? Blocks.GRAY_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE;
            level.setBlock(center.offset(x, 0, z), floor.defaultBlockState(), 3);
        }
    }
    private void releaseChunks(Session s) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            level.setChunkForced((s.center.getX() >> 4) + x, z, false);
    }
    private void replaceBots(Session s) {
        if (s.playerBot != null) s.playerBot.remove();
        if (s.opponent != null) s.opponent.remove();
        try {
            s.playerBot = new PracticeBot(level, s.owner, s.center.offset(0, 3, -11), false, s.design);
            s.opponent = new PracticeBot(level, s.owner, s.center.offset(0, 3, 11), true, PracticeBot.SAW);
            s.playerBot.tune(s.tune); s.brain.reset(); s.input = PracticeRules.Input.STOP;
        } catch (RuntimeException error) {
            if (s.playerBot != null) s.playerBot.remove();
            if (s.opponent != null) s.opponent.remove();
            releaseChunks(s); throw error;
        }
    }
    private void restore(Session s) {
        s.playerBot.repair(); s.opponent.repair(); s.brain.reset();
        s.roundTicks = 0; s.input = PracticeRules.Input.STOP; s.lastInput = -100;
        s.playerBot.tune(s.tune);
    }
    public void tick() {
        ticks++;
        if (ticks % 100 == 1) cleanOrphans();
        List<SablePhysicsAdapter.ImpactContact> contacts = physics.drainContacts(level, 512);
        for (Session s : new ArrayList<>(sessions.values())) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(s.owner);
            if (player == null || player.serverLevel() != level) {
                close(s); sessions.remove(s.owner); lastAction.remove(s.owner);
                if (player != null) { restorePlayer(player); PacketDistributor.sendToPlayer(player, PracticeNetwork.State.closed()); }
                continue;
            }
            try {
                if (s.lastInput < ticks - PracticeRules.INPUT_TIMEOUT) s.input = PracticeRules.Input.STOP;
                if (s.phase == COUNTDOWN) {
                    if (s.countdown % 20 == 0) sound(s, SoundEvents.NOTE_BLOCK_HAT.value(), 0.6f, 1);
                    if (--s.countdown <= 0) {
                        s.phase = ACTIVE; s.playerBot.release(); s.opponent.release(); s.headline = "FIGHT";
                        sound(s, SoundEvents.ANVIL_LAND, 0.4f, 1.5f);
                    }
                }
                if (s.canDrive()) s.playerBot.drive(s.input);
                if (s.phase == TUTORIAL) tutorial(player, s);
                if (s.phase == ACTIVE) {
                    Vec3 p = s.playerBot.position(), e = s.opponent.position();
                    s.opponent.drive(s.brain.tick(e.x, e.z, s.opponent.yaw(), p.x, p.z,
                            s.center.getX(), s.center.getZ(), s.easy));
                    for (var contact : contacts) processHit(s, contact);
                    s.roundTicks++;
                    boolean playerOut = !s.playerBot.healthy() || ringOut(s, s.playerBot) || runningGearGone(s.playerBot);
                    boolean enemyOut = !s.opponent.healthy() || ringOut(s, s.opponent) || runningGearGone(s.opponent);
                    if (playerOut || enemyOut) finish(s, playerOut && enemyOut ? "DOUBLE KNOCKOUT" : enemyOut ? "YOU WIN" : "SCRAPBOT WINS", enemyOut && !playerOut, enemyOut && playerOut);
                    else if (s.roundTicks >= PracticeRules.ROUND_TICKS) {
                        double margin = s.playerBot.coreHealth - s.opponent.coreHealth + 15 * (s.opponent.lostParts() - s.playerBot.lostParts());
                        finish(s, Math.abs(margin) < 0.01 ? "DRAW" : margin > 0 ? "YOU WIN ON DAMAGE" : "SCRAPBOT WINS ON DAMAGE", margin > 0, Math.abs(margin) < 0.01);
                    }
                }
                if ((s.phase == GARAGE || s.phase == TUTORIAL) && ringOut(s, s.playerBot)) {
                    restore(s); s.playerBot.release(); s.feedback = "Off the edge? Your bot is back. No penalty in practice.";
                }
                if (s.phase == REPAIRING && --s.restoreWait <= 0) { restore(s); s.phase = RESULTS; }
                if (ticks % 4 == 0) send(player, s);
            } catch (RuntimeException error) {
                s.phase = ERROR; s.feedback = safe(error.getMessage());
                CreateBattleBots.LOGGER.warn("Practice bay {} stopped safely", s.slot, error);
                try { s.playerBot.freeze(); s.opponent.freeze(); } catch (RuntimeException ignored) { }
                send(player, s);
            }
        }
    }
    private boolean runningGearGone(PracticeBot bot) {
        return bot.parts.stream().skip(1).noneMatch(p -> !p.weapon && p.attached());
    }
    private boolean ringOut(Session s, PracticeBot bot) {
        Vec3 p = bot.position(); return p.y < 61 || Math.abs(p.x - s.center.getX()) > 27 || Math.abs(p.z) > 27;
    }
    private void processHit(Session s, SablePhysicsAdapter.ImpactContact hit) {
        UUID target = physics.resolve(level, hit.self()).orElse(null), source = physics.resolve(level, hit.other()).orElse(null);
        if (target == null || source == null) return;
        PracticeBot damaged, attacking;
        if (s.playerBot.contains(target) && s.opponent.contains(source)) { damaged = s.playerBot; attacking = s.opponent; }
        else if (s.opponent.contains(target) && s.playerBot.contains(source)) { damaged = s.opponent; attacking = s.playerBot; }
        else return;
        // A loose fragment may hit something physically, but cannot farm damage points forever.
        if (attacking.parts.stream().skip(1).anyMatch(p -> p.body.getUniqueId().equals(source) && !p.attached())) return;
        double damage = PracticeRules.damage(hit.speed());
        if (damage == 0) return;
        String broken = damaged.hit(target, damage, ticks);
        if (!broken.isEmpty()) {
            s.feedback = (damaged == s.playerBot ? "You lost your " : "Scrapbot lost its ") + broken + "!";
            Vec3 point = physics.worldPosition(level, hit.self());
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 14, .4, .4, .4, .1);
            sound(s, SoundEvents.ANVIL_BREAK, .6f, 1.2f);
        } else if (ticks % 5 == 0) {
            Vec3 point = physics.worldPosition(level, hit.self());
            level.sendParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 3, .1, .1, .1, .1);
        }
    }
    private void tutorial(ServerPlayer player, Session s) {
        if (s.tutorial == 0 && s.playerBot.position().distanceTo(s.tutorialStart) > 3) s.tutorial++;
        if (s.tutorial == 1 && Math.abs(s.input.turn()) > .1 && ++s.turning >= 15) s.tutorial++;
        if (s.tutorial == 2 && s.playerBot.weaponSpeed > 4) completeTutorial(player, s);
    }
    private void completeTutorial(ServerPlayer player, Session s) {
        s.tutorial = 3; s.phase = GARAGE; s.headline = "READY TO FIGHT";
        s.feedback = "Keep your green core safe. Hit Scrapbot's wheels or red chassis. Choose Fight AI.";
        player.getPersistentData().putBoolean(COMPLETED, true);
        sound(s, SoundEvents.EXPERIENCE_ORB_PICKUP, .7f, 1.3f);
    }
    private void finish(Session s, String headline, boolean win, boolean draw) {
        s.headline = headline;
        s.feedback = "Parts lost: you " + s.playerBot.lostParts() + " / Scrapbot " + s.opponent.lostParts()
                + ". Repairing automatically; change your setup or rematch.";
        if (win) s.wins++; else if (!draw) s.losses++;
        s.phase = REPAIRING; s.restoreWait = 50;
        s.playerBot.freeze(); s.opponent.freeze();
        sound(s, win ? SoundEvents.PLAYER_LEVELUP : SoundEvents.IRON_GOLEM_DEATH, .7f, 1);
    }
    private void sound(Session s, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        ServerPlayer listener = level.getServer().getPlayerList().getPlayer(s.owner);
        if (listener != null) listener.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }
    private void send(ServerPlayer player, Session s) {
        String task = s.phase == TUTORIAL ? switch (s.tutorial) {
            case 0 -> "1 / 3: Hold W to drive your GREEN bot forward.";
            case 1 -> "2 / 3: Hold A or D to steer. S reverses.";
            default -> "3 / 3: Hold Space to spin up your weapon.";
        } : s.phase == COUNTDOWN ? "Fight begins in " + ((s.countdown + 19) / 20)
                : s.phase == PAUSED ? "Paused. Resume when ready."
                : s.phase == ACTIVE ? "Destroy the core, remove the running gear, or win by ring-out."
                : s.phase == ERROR ? "Practice stopped. Choose a fresh design or return to your world."
                : s.headline;
        PacketDistributor.sendToPlayer(player, new PracticeNetwork.State(s.phase, task, s.feedback,
                s.playerBot == null ? 0 : (int)s.playerBot.coreHealth, s.opponent == null ? 0 : (int)s.opponent.coreHealth,
                s.playerBot == null ? 0 : s.playerBot.lostParts(), s.opponent == null ? 0 : s.opponent.lostParts(),
                Math.max(0, (PracticeRules.ROUND_TICKS - s.roundTicks) / 20), s.wins, s.losses,
                s.design, s.tune, s.easy, s.playerBot == null ? 0 : (int)(s.playerBot.weaponSpeed * 60 / (2 * Math.PI))));
    }
    private void exit(ServerPlayer player) {
        Session s = sessions.remove(player.getUUID());
        try { if (s != null) close(s); } finally {
            restorePlayer(player); PacketDistributor.sendToPlayer(player, PracticeNetwork.State.closed());
        }
    }
    private void close(Session s) {
        try { if (s.playerBot != null) s.playerBot.remove(); }
        finally { try { if (s.opponent != null) s.opponent.remove(); } finally { releaseChunks(s); } }
    }
    private void cleanOrphans() {
        Set<UUID> live = new HashSet<>();
        for (Session s : sessions.values()) {
            if (s.playerBot != null) live.addAll(s.playerBot.ids());
            if (s.opponent != null) live.addAll(s.opponent.ids());
        }
        var container = SubLevelContainer.getContainer(level);
        for (var body : List.copyOf(container.getAllSubLevels())) {
            CompoundTag tag = body.getUserDataTag();
            if (tag != null && tag.hasUUID(PracticeBot.OWNER_TAG) && !live.contains(body.getUniqueId())) {
                container.removeForceLoadTicket(body, PracticeBot.TICKET, tag.getUUID(PracticeBot.OWNER_TAG));
                container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            }
        }
        container.processSubLevelRemovals();
    }
    public static void restorePlayer(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(RETURN)) return;
        CompoundTag home = data.getCompound(RETURN);
        ResourceLocation id = ResourceLocation.tryParse(home.getString("dimension"));
        ServerLevel destination = id == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        double x = home.getDouble("x"), y = home.getDouble("y"), z = home.getDouble("z");
        if (destination == null || destination.dimension().equals(WORLD) || !Double.isFinite(x + y + z)) {
            destination = player.server.overworld(); var pos = destination.getSharedSpawnPos(); x=pos.getX()+.5; y=pos.getY()+1; z=pos.getZ()+.5;
        }
        player.teleportTo(destination, x, y, z, Set.of(), home.getFloat("yaw"), home.getFloat("pitch"));
        player.setGameMode(GameType.byId(home.getInt("mode")));
        data.remove(RETURN); player.server.getPlayerList().saveAll();
    }
    public static void shutdown(MinecraftServer server) {
        PracticeService service = SERVICES.remove(server);
        if (service == null) return;
        for (Session s : new ArrayList<>(service.sessions.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(s.owner);
            service.close(s); if (player != null) restorePlayer(player);
        }
        service.sessions.clear(); service.lastAction.clear();
    }
    private static String safe(String message) { return message == null ? "Unexpected practice error" : message.substring(0, Math.min(240, message.length())); }
}
