package education.poiesis.createbattlebots.network;

import education.poiesis.createbattlebots.CreateBattleBots;
import education.poiesis.createbattlebots.practice.PracticeService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.function.Consumer;

/** A small versioned protocol. Client supplies buttons only; server owns bodies, force and scoring. */
public final class PracticeNetwork {
    private static Consumer<State> receiver=ignored->{};
    private PracticeNetwork(){}
    public static void clientReceiver(Consumer<State> consumer){receiver=consumer;}
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("2");
        registrar.playToServer(Action.TYPE,Action.CODEC,(packet,context)->context.enqueueWork(()->{
            if(context.player() instanceof ServerPlayer player)PracticeService.action(player,packet.action());
        }));
        registrar.playToServer(Input.TYPE,Input.CODEC,(packet,context)->context.enqueueWork(()->{
            if(context.player() instanceof ServerPlayer player && PracticeService.isPractice(player.level()))
                PracticeService.get(player.serverLevel()).input(player,packet.buttons());
        }));
        registrar.playToClient(State.TYPE,State.CODEC,(packet,context)->context.enqueueWork(()->receiver.accept(packet)));
    }
    public static void send(ServerPlayer player,State state){PacketDistributor.sendToPlayer(player,state);}
    public record Action(int action) implements CustomPacketPayload {
        public static final Type<Action> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateBattleBots.MOD_ID,"practice_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Action> CODEC=new StreamCodec<>(){
            public Action decode(RegistryFriendlyByteBuf b){return new Action(b.readVarInt());}
            public void encode(RegistryFriendlyByteBuf b,Action p){b.writeVarInt(p.action);}
        };
        @Override public Type<Action> type(){return TYPE;}
    }
    public record Input(int buttons) implements CustomPacketPayload {
        public static final Type<Input> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateBattleBots.MOD_ID,"practice_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Input> CODEC=new StreamCodec<>(){
            public Input decode(RegistryFriendlyByteBuf b){return new Input(b.readUnsignedByte());}
            public void encode(RegistryFriendlyByteBuf b,Input p){b.writeByte(p.buttons);}
        };
        @Override public Type<Input> type(){return TYPE;}
    }
    public record State(int phase,int seconds,int health,int enemyHealth,int detached,String message,
                        double x,double y,double z,double heading,boolean driving,boolean ready) implements CustomPacketPayload {
        public static final Type<State> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateBattleBots.MOD_ID,"practice_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=new StreamCodec<>(){
            public State decode(RegistryFriendlyByteBuf b){return new State(b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readUtf(512),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readBoolean(),b.readBoolean());}
            public void encode(RegistryFriendlyByteBuf b,State p){b.writeVarInt(p.phase);b.writeVarInt(p.seconds);b.writeVarInt(p.health);b.writeVarInt(p.enemyHealth);b.writeVarInt(p.detached);b.writeUtf(p.message,512);b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z);b.writeDouble(p.heading);b.writeBoolean(p.driving);b.writeBoolean(p.ready);}
        };
        public static State closed(){return new State(0,0,0,0,0,"",0,0,0,0,false,false);}
        @Override public Type<State> type(){return TYPE;}
    }
}
