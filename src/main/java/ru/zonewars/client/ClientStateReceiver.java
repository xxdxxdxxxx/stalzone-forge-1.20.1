package ru.zonewars.client;

import net.minecraft.client.Minecraft;
import ru.zonewars.client.state.ZoneWarsState;
import ru.zonewars.client.ui.ZoneMapScreen;

public final class ClientStateReceiver {
    private ClientStateReceiver() {}

    public static void accept(String state) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            ZoneWarsState.update(state);
            ZoneWarsState.Snapshot snapshot = ZoneWarsState.snapshot();
            if (snapshot.respawnPrompt() && !(minecraft.screen instanceof ZoneMapScreen screen && screen.respawnMode())) {
                minecraft.setScreen(new ZoneMapScreen(true));
            }
        });
    }
}
