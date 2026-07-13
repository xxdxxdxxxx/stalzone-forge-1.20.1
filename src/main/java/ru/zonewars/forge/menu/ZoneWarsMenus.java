package ru.zonewars.forge.menu;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import ru.zonewars.forge.ZoneWarsForge;

public final class ZoneWarsMenus {
    private static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, ZoneWarsForge.MOD_ID);

    public static final RegistryObject<MenuType<ZoneInventoryMenu>> TACTICAL_INVENTORY =
        MENUS.register("tactical_inventory",
            () -> IForgeMenuType.create((windowId, inventory, data) -> new ZoneInventoryMenu(windowId, inventory)));

    private ZoneWarsMenus() {}

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}