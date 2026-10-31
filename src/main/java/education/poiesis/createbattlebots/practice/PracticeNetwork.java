package education.poiesis.createbattlebots.practice;

import education.poiesis.createbattlebots.CreateBattleBots;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.function.Consumer;

/** Small versioned packets. Clients submit input, never body IDs, poses, scores, or damage. */
public final class PracticeNetwork {
    public static Consumer<State> clientState = ignored -> {};
    public record Action(int code) implements CustomPacketPayload {
        public static final Type<Action> TYPE = typeOf("practice_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = new StreamCodec<>() {
            public Action decode(RegistryFriendlyByteBuf b) { return new Action(b.readUnsignedByte()); }
            public void encode(RegistryFriendlyByteBuf b, Action v) { b.writeByte(v.code); }
        };
        @Override public Type<Action> type() { return TYPE; }
    }
    public record Input(int flags) implements CustomPacketPayload {
        public static final Type<Input> TYPE = typeOf("practice_input");
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = new StreamCodec<>() {
            public Input decode(RegistryFriendlyByteBuf b) { return new Input(b.readUnsignedByte()); }
            public void encode(RegistryFriendlyByteBuf b, Input v) { b.writeByte(v.flags); }
        };
        @Override public Type<Input> type() { return TYPE; }
    }
    public record State(int phase, String objective, String feedback, int health, int enemyHealth,
                        int lost, int enemyLost, int seconds, int wins, int losses,
                        int design, int tune, boolean easy, int rpm) implements CustomPacketPayload {
        public static final Type<State> TYPE = typeOf("practice_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) {
                return new State(b.readUnsignedByte(), b.readUtf(512), b.readUtf(512), b.readVarInt(), b.readVarInt(),
                        b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                        b.readUnsignedByte(), b.readUnsignedByte(), b.readBoolean(), b.readVarInt());
            }
            public void encode(RegistryFriendlyByteBuf b, State s) {
                b.writeByte(s.phase); b.writeUtf(s.objective, 512); b.writeUtf(s.feedback, 512);
                for (int value : new int[]{s.health, s.enemyHealth, s.lost, s.enemyLost, s.seconds, s.wins, s.losses}) b.writeVarInt(value);
                b.writeByte(s.design); b.writeByte(s.tune); b.writeBoolean(s.easy); b.writeVarInt(s.rpm);
            }
        };
        public static State closed() { return new State(0, "", "", 100, 100, 0, 0, 90, 0, 0, 0, 0, true, 0); }
        @Override public Type<State> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("practice-1");
        registrar.playToServer(Action.TYPE, Action.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                try { PracticeService.get(player.server).action(player, packet.code); }
                catch (RuntimeException error) { player.sendSystemMessage(Component.literal("Battle Bots: " + error.getMessage())); }
            }
        }));
        registrar.playToServer(Input.TYPE, Input.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && PracticeService.isWorkshop(player.level()))
                PracticeService.get(player.server).input(player, packet.flags);
        }));
        registrar.playToClient(State.TYPE, State.CODEC, (packet, context) -> context.enqueueWork(() -> clientState.accept(packet)));
    }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CreateBattleBots.MOD_ID, name));
    }
    private PracticeNetwork() {}
}
