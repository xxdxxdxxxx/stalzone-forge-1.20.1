package ru.zonewars.forge.menu;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Server-backed tactical inventory. Every slot is a view over the player's own
 * inventory, so all drag/drop and shift-click moves are executed and validated
 * by the vanilla server container logic (no client-authoritative item writes).
 */
public final class ZoneInventoryMenu extends AbstractContainerMenu {
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    public static final int MAIN_START = 0;
    public static final int MAIN_END = 27;
    public static final int HOTBAR_START = 27;
    public static final int HOTBAR_END = 36;
    public static final int ARMOR_START = 36;
    public static final int ARMOR_END = 40;
    public static final int OFFHAND = 40;

    public ZoneInventoryMenu(int windowId, Inventory inventory) {
        super(ZoneWarsMenus.TACTICAL_INVENTORY.get(), windowId);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 142));
        }
        for (int index = 0; index < ARMOR.length; index++) {
            final EquipmentSlot equipment = ARMOR[index];
            addSlot(new Slot(inventory, 39 - index, 8, 8 + index * 18) {
                @Override public int getMaxStackSize() { return 1; }
                @Override public boolean mayPlace(ItemStack stack) {
                    return stack.canEquip(equipment, inventory.player);
                }
            });
        }
        addSlot(new Slot(inventory, 40, 77, 62));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean moved;
        if (index < MAIN_END) {
            moved = moveItemStackTo(stack, HOTBAR_START, HOTBAR_END, false);
        } else if (index < HOTBAR_END) {
            moved = moveItemStackTo(stack, MAIN_START, MAIN_END, false);
        } else {
            moved = moveItemStackTo(stack, MAIN_START, HOTBAR_END, false);
        }
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }
}