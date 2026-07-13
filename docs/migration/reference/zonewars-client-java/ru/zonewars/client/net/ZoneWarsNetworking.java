package ru.zonewars.client.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import ru.zonewars.client.state.ZoneWarsState;
import ru.zonewars.client.ui.ZoneMapScreen;

public final class ZoneWarsNetworking {

    private ZoneWarsNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ZoneStatePayload.ID, ZoneStatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ZoneActionPayload.ID, ZoneActionPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(ZoneStatePayload.ID, (payload, context) ->
            context.client().execute(() -> {
                ZoneWarsState.update(payload.state());
                ZoneWarsState.Snapshot snapshot = ZoneWarsState.snapshot();
                if (snapshot.respawnPrompt() && !(context.client().currentScreen instanceof ZoneMapScreen screen && screen.respawnMode())) {
                    context.client().setScreen(new ZoneMapScreen(true));
                }
            }));
    }

    public static void requestState() {
        if (ClientPlayNetworking.canSend(ZoneActionPayload.ID)) {
            ClientPlayNetworking.send(new ZoneActionPayload("request_state"));
        }
    }

    public static void chooseRespawn(String kind) {
        if (ClientPlayNetworking.canSend(ZoneActionPayload.ID)) {
            ClientPlayNetworking.send(new ZoneActionPayload("respawn:" + kind));
        }
    }

    public static void confirmRespawn() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.requestRespawn();
        }
    }

    public static void sendPing(String type, int x, int z) {
        if (ClientPlayNetworking.canSend(ZoneActionPayload.ID)) {
            ClientPlayNetworking.send(new ZoneActionPayload("ping:" + type + ":" + x + ":" + z));
        }
    }

    public static void setWaypoint(int x, int z) {
        if (ClientPlayNetworking.canSend(ZoneActionPayload.ID)) {
            ClientPlayNetworking.send(new ZoneActionPayload("waypoint:" + x + ":" + z));
        }
    }

    public static void openSquadMenu() {
        if (ClientPlayNetworking.canSend(ZoneActionPayload.ID)) {
            ClientPlayNetworking.send(new ZoneActionPayload("squad:menu"));
        }
    }
}
