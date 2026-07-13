package ru.zonewars.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;
import ru.zonewars.client.net.ZoneWarsNetworking;
import ru.zonewars.client.ui.ZoneMapScreen;
import ru.zonewars.client.ui.ZoneWarsHud;
import ru.zonewars.forge.ZoneWarsForge;

@Mod.EventBusSubscriber(modid = ZoneWarsForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ZoneWarsClient {
    private static final String CATEGORY = "key.categories.zonewars";
    private static final KeyMapping MAP = key("key.zonewars.map", GLFW.GLFW_KEY_M);
    private static final KeyMapping SQUAD = key("key.zonewars.squad", GLFW.GLFW_KEY_O);
    private static final KeyMapping PING = key("key.zonewars.ping", GLFW.GLFW_KEY_TAB);
    private static final KeyMapping INVENTORY = key("key.zonewars.inventory", GLFW.GLFW_KEY_I);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(MAP); event.register(SQUAD); event.register(PING); event.register(INVENTORY);
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.addListener(ZoneWarsClient::clientTick);
        ZoneWarsHud.register();
        ru.zonewars.client.map.XaeroWaypointBridge.register();
        event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
            ru.zonewars.forge.menu.ZoneWarsMenus.TACTICAL_INVENTORY.get(),
            ru.zonewars.client.ui.ZoneInventoryContainerScreen::new));
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && !minecraft.player.isAlive() && minecraft.screen instanceof DeathScreen) {
            minecraft.setScreen(new ZoneMapScreen(true));
            ZoneWarsNetworking.requestState();
        }
        while (MAP.consumeClick()) { minecraft.setScreen(new ZoneMapScreen()); ZoneWarsNetworking.requestState(); }
        while (SQUAD.consumeClick()) ZoneWarsNetworking.openSquadMenu();
        while (PING.consumeClick()) if (minecraft.player != null)
            ZoneWarsNetworking.sendPing("DANGER", minecraft.player.getBlockX(), minecraft.player.getBlockZ());
        while (INVENTORY.consumeClick()) ZoneWarsNetworking.openTacticalInventory();
    }
}
