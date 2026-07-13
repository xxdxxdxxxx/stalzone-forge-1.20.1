package ru.zonewars.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import ru.zonewars.client.net.ZoneWarsNetworking;
import ru.zonewars.client.ui.ZoneInventoryScreen;
import ru.zonewars.client.ui.ZoneMapScreen;
import ru.zonewars.client.ui.ZoneWarsHud;

public final class ZoneWarsClient implements ClientModInitializer {

    private static final String CATEGORY = "key.categories.zonewars";
    private static KeyBinding mapKey;
    private static KeyBinding squadKey;
    private static KeyBinding pingKey;
    private static KeyBinding inventoryKey;

    @Override
    public void onInitializeClient() {
        ZoneWarsNetworking.register();
        ZoneWarsHud.register();
        mapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.zonewars.map",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            CATEGORY
        ));
        squadKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.zonewars.squad",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY
        ));
        pingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.zonewars.ping",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_TAB,
            CATEGORY
        ));
        inventoryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.zonewars.inventory",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && !client.player.isAlive() && client.currentScreen instanceof DeathScreen) {
                client.setScreen(new ZoneMapScreen(true));
                ZoneWarsNetworking.requestState();
            }
            while (mapKey.wasPressed()) {
                client.setScreen(new ZoneMapScreen());
                ZoneWarsNetworking.requestState();
            }
            while (squadKey.wasPressed()) {
                ZoneWarsNetworking.openSquadMenu();
            }
            while (pingKey.wasPressed()) {
                if (client.player != null) {
                    ZoneWarsNetworking.sendPing("DANGER", client.player.getBlockX(), client.player.getBlockZ());
                }
            }
            while (inventoryKey.wasPressed()) {
                client.setScreen(new ZoneInventoryScreen());
            }
        });
    }
}
