package ru.zonewars.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class ZoneInventoryScreen extends Screen {
    private static final int BACKDROP = 0xD8080C10;
    private static final int PANEL = 0xF20D1217;
    private static final int SLOT = 0xFF11171C;
    private static final int SLOT_HOVER = 0xFF182630;
    private static final int STROKE = 0xFF46545E;
    private static final int TEXT = 0xFFEAF0F4;
    private static final int MUTED = 0xFF8FA0AC;
    private static final int BLUE = 0xFF65B2FF;
    private static final int GREEN = 0xFF67D38B;
    private static final int YELLOW = 0xFFE6C766;
    private static final List<ShopEntry> SHOP = List.of(
        new ShopEntry("ak101", "AK-101 FIELD KIT", Items.CROSSBOW, 650),
        new ShopEntry("ak103", "AK-103 FIELD KIT", Items.CROSSBOW, 700),
        new ShopEntry("pkp", "PKP SUPPORT KIT", Items.CROSSBOW, 1000),
        new ShopEntry("ammo", "AMMUNITION", Items.ARROW, 100),
        new ShopEntry("medkit", "MEDKIT", Items.GOLDEN_APPLE, 150),
        new ShopEntry("tent", "FIELD TENT", Items.CAMPFIRE, 500),
        new ShopEntry("outpost", "SQUAD OUTPOST", Items.LODESTONE, 1000)
    );

    private final List<ShopTarget> shopTargets = new ArrayList<>();
    private boolean shopMode;
    private int selectedShop = -1;

    public ZoneInventoryScreen() {
        super(Component.literal("ZoneWars Inventory"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BACKDROP);
        int panelWidth = Math.min(width - 36, 920);
        int panelHeight = Math.min(height - 30, 520);
        int x = (width - panelWidth) / 2;
        int y = (height - panelHeight) / 2;
        int split = x + panelWidth * 44 / 100;
        graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL);
        graphics.renderOutline(x, y, panelWidth, panelHeight, STROKE);
        graphics.fill(split, y, split + 1, y + panelHeight, STROKE);

        drawHeader(graphics, x, y, split, panelWidth);
        if (shopMode) drawShop(graphics, x, y, split, mouseX, mouseY);
        else drawEquipment(graphics, x, y, split, mouseX, mouseY);
        drawInventory(graphics, split, y, x + panelWidth, mouseX, mouseY);
        graphics.drawCenteredString(font, "TAB MODE  ·  LMB PURCHASE  ·  E CLOSE", width / 2, y + panelHeight - 16, MUTED);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawHeader(GuiGraphics graphics, int x, int y, int split, int panelWidth) {
        graphics.drawString(font, shopMode ? "FIELD MERCHANT" : "GEAR", x + 12, y + 12, TEXT, false);
        graphics.drawString(font, shopMode ? "Purchase equipment for current deployment" : "Personal combat equipment", x + 12, y + 27, MUTED, false);
        graphics.drawString(font, "INVENTORY", split + 12, y + 12, TEXT, false);
        Minecraft client = Minecraft.getInstance();
        if (minecraft.player != null) {
            graphics.drawString(font, minecraft.player.getName().getString(), split + 12, y + 27, GREEN, false);
        }
        int toggleX = x + panelWidth - 104;
        graphics.fill(toggleX, y + 9, toggleX + 92, y + 32, 0xFF17232B);
        graphics.renderOutline(toggleX, y + 9, 92, 23, BLUE);
        graphics.drawCenteredString(font, shopMode ? "PERSONAL" : "SHOP", toggleX + 46, y + 17, BLUE);
    }

    private void drawShop(GuiGraphics graphics, int x, int y, int split, int mouseX, int mouseY) {
        shopTargets.clear();
        int startX = x + 12;
        int startY = y + 54;
        int slot = 44;
        int gap = 3;
        for (int index = 0; index < SHOP.size(); index++) {
            int column = index % 7;
            int row = index / 7;
            int sx = startX + column * (slot + gap);
            int sy = startY + row * (slot + gap);
            boolean hover = inside(mouseX, mouseY, sx, sy, slot, slot);
            graphics.fill(sx, sy, sx + slot, sy + slot, hover || selectedShop == index ? SLOT_HOVER : SLOT);
            graphics.renderOutline(sx, sy, slot, slot, selectedShop == index ? BLUE : STROKE);
            ItemStack stack = new ItemStack(SHOP.get(index).item());
            graphics.renderItem(stack, sx + 13, sy + 9);
            graphics.drawString(font, String.valueOf(SHOP.get(index).price()), sx + 3, sy + 33, YELLOW, false);
            shopTargets.add(new ShopTarget(index, sx, sy, slot, slot));
        }
        int detailsY = startY + 94;
        graphics.fill(startX, detailsY, split - 12, detailsY + 92, SLOT);
        graphics.renderOutline(startX, detailsY, split - startX - 12, 92, STROKE);
        if (selectedShop >= 0 && selectedShop < SHOP.size()) {
            ShopEntry entry = SHOP.get(selectedShop);
            graphics.drawString(font, entry.name(), startX + 10, detailsY + 12, BLUE, false);
            graphics.drawString(font, "Price: " + entry.price(), startX + 10, detailsY + 30, YELLOW, false);
            graphics.drawString(font, "Server validates balance and delivery.", startX + 10, detailsY + 48, MUTED, false);
            graphics.drawString(font, "Click selected slot again to purchase.", startX + 10, detailsY + 64, MUTED, false);
        }
    }

    private void drawEquipment(GuiGraphics graphics, int x, int y, int split, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        if (minecraft.player == null) return;
        int startX = x + 18;
        int startY = y + 58;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND};
        for (int index = 0; index < slots.length; index++) {
            int column = index % 2;
            int row = index / 2;
            int sx = startX + column * 104;
            int sy = startY + row * 72;
            graphics.fill(sx, sy, sx + 92, sy + 62, SLOT);
            graphics.renderOutline(sx, sy, 92, 62, STROKE);
            ItemStack stack = minecraft.player.getItemBySlot(slots[index]);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, sx + 10, sy + 10);
                graphics.renderItemDecorations(font, stack, sx + 10, sy + 10);
            }
            graphics.drawString(font, slots[index].getName(), sx + 8, sy + 43, MUTED, false);
        }
        int statX = startX + 214;
        graphics.drawString(font, "COMBAT STATUS", statX, startY, TEXT, false);
        graphics.drawString(font, "Health  " + Math.round(minecraft.player.getHealth()), statX, startY + 23, GREEN, false);
        graphics.drawString(font, "Armor   " + minecraft.player.getArmorValue(), statX, startY + 40, BLUE, false);
        graphics.drawString(font, "Mode    " + (minecraft.player.isCreative() ? "CREATIVE" : "SURVIVAL"), statX, startY + 57, MUTED, false);
    }

    private void drawInventory(GuiGraphics graphics, int split, int y, int right, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        if (minecraft.player == null) return;
        int slot = 38;
        int gap = 2;
        int gridWidth = slot * 9 + gap * 8;
        int startX = split + Math.max(12, (right - split - gridWidth) / 2);
        int startY = y + 56;
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 9; column++) {
                int inventoryIndex = row == 3 ? column : 9 + row * 9 + column;
                int sx = startX + column * (slot + gap);
                int sy = startY + row * (slot + gap);
                boolean hover = inside(mouseX, mouseY, sx, sy, slot, slot);
                graphics.fill(sx, sy, sx + slot, sy + slot, hover ? SLOT_HOVER : SLOT);
                graphics.renderOutline(sx, sy, slot, slot, hover ? BLUE : STROKE);
                ItemStack stack = minecraft.player.getInventory().getItem(inventoryIndex);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, sx + 10, sy + 8);
                    graphics.renderItemDecorations(font, stack, sx + 10, sy + 8);
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int panelWidth = Math.min(width - 36, 920);
        int panelHeight = Math.min(height - 30, 520);
        int x = (width - panelWidth) / 2;
        int y = (height - panelHeight) / 2;
        int toggleX = x + panelWidth - 104;
        if (inside(mouseX, mouseY, toggleX, y + 9, 92, 23)) {
            shopMode = !shopMode;
            return true;
        }
        if (shopMode) {
            for (ShopTarget target : shopTargets) {
                if (target.contains(mouseX, mouseY)) {
                    if (selectedShop == target.index()) purchase(SHOP.get(target.index()).id());
                    else selectedShop = target.index();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void purchase(String itemId) {
        Minecraft client = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand("zw buy " + itemId);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            shopMode = !shopMode;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_E) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record ShopEntry(String id, String name, Item item, int price) {
    }

    private record ShopTarget(int index, int x, int y, int width, int height) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }
}
