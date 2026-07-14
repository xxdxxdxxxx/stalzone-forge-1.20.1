package ru.zonewars.client;

import net.minecraft.client.Minecraft;
import ru.zonewars.client.map.CampChatMapOverlay;
import ru.zonewars.client.state.ZoneWarsState;

/**
 * Receives serialized match state from the server. The respawn prompt is
 * routed to the campchat PDA map; the legacy fullscreen map screen is gone.
 */
public final class ClientStateReceiver {
    private ClientStateReceiver() {}

    public static void accept(String state) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            ZoneWarsState.update(state);
            if (ZoneWarsState.snapshot().respawnPrompt()) {
                // openDeployment self-guards: no-op while the PDA is already open.
                CampChatMapOverlay.openDeployment(minecraft);
            }
        });
    }
}