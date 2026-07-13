package ru.zonewars.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import ru.zonewars.client.ClientStateReceiver;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class ZoneWarsNetwork {
    private static final String PROTOCOL = "2";
    private static final int MAX_ACTION_LENGTH = 256;
    private static final int MAX_STATE_LENGTH = 262_144;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ZoneWarsForge.MOD_ID, "main"),
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals
    );
    private static BiConsumer<ServerPlayer, String> actionHandler;
    private static int id;

    private ZoneWarsNetwork() {}

    public static void register(BiConsumer<ServerPlayer, String> handler) {
        actionHandler = handler;
        CHANNEL.messageBuilder(ActionMessage.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ActionMessage::encode).decoder(ActionMessage::decode)
            .consumerMainThread(ActionMessage::handle).add();
        CHANNEL.messageBuilder(StateMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(StateMessage::encode).decoder(StateMessage::decode)
            .consumerMainThread(StateMessage::handle).add();
    }

    public static void sendAction(String action) {
        CHANNEL.sendToServer(new ActionMessage(action));
    }

    public static void sendState(ServerPlayer player, String state) {
        if (state == null || state.length() > MAX_STATE_LENGTH) {
            System.err.println("[ZoneWars] Refusing oversized state packet for " + player.getName().getString());
            return;
        }
        CHANNEL.sendTo(new StateMessage(state), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    public record ActionMessage(String action) {
        static void encode(ActionMessage message, FriendlyByteBuf buffer) { buffer.writeUtf(message.action, MAX_ACTION_LENGTH); }
        static ActionMessage decode(FriendlyByteBuf buffer) { return new ActionMessage(buffer.readUtf(MAX_ACTION_LENGTH)); }
        static void handle(ActionMessage message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer sender = context.getSender();
            if (sender != null && actionHandler != null) actionHandler.accept(sender, message.action);
            context.setPacketHandled(true);
        }
    }

    public record StateMessage(String state) {
        static void encode(StateMessage message, FriendlyByteBuf buffer) { buffer.writeUtf(message.state, MAX_STATE_LENGTH); }
        static StateMessage decode(FriendlyByteBuf buffer) { return new StateMessage(buffer.readUtf(MAX_STATE_LENGTH)); }
        static void handle(StateMessage message, Supplier<NetworkEvent.Context> supplier) {
            ClientStateReceiver.accept(message.state);
            supplier.get().setPacketHandled(true);
        }
    }
}
