package ru.zonewars.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.render.MinimapRendererHelper;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.render.WaypointMapRenderer;

@Mixin(value = WaypointMapRenderer.class, remap = false)
public abstract class ZoneWarsXaeroWaypointMixin {
    private static final ResourceLocation BASE = new ResourceLocation("zonewars", "textures/gui/map/base.png");
    private static final ResourceLocation TENT = new ResourceLocation("zonewars", "textures/gui/map/tent.png");
    private static final ResourceLocation OUTPOST = new ResourceLocation("zonewars", "textures/gui/map/outpost.png");

    @Inject(method = "drawIconOnGUI(Lnet/minecraft/client/gui/GuiGraphics;Lxaero/common/minimap/render/MinimapRendererHelper;Lxaero/common/minimap/waypoints/Waypoint;IILnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void zonewars$iconShort(GuiGraphics graphics, MinimapRendererHelper helper, Waypoint waypoint, int x, int y, MultiBufferSource.BufferSource buffers, VertexConsumer first, VertexConsumer second, CallbackInfo ci) {
        if (zonewars$draw(graphics, waypoint, x, y)) ci.cancel();
    }

    @Inject(method = "drawIconOnGUI(Lnet/minecraft/client/gui/GuiGraphics;Lxaero/common/minimap/render/MinimapRendererHelper;Lxaero/common/minimap/waypoints/Waypoint;IIILnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void zonewars$iconLong(GuiGraphics graphics, MinimapRendererHelper helper, Waypoint waypoint, int x, int y, int size, MultiBufferSource.BufferSource buffers, VertexConsumer first, VertexConsumer second, CallbackInfo ci) {
        if (zonewars$draw(graphics, waypoint, x, y)) ci.cancel();
    }

    private static boolean zonewars$draw(GuiGraphics graphics, Waypoint waypoint, int x, int y) {
        String name = waypoint.getName();
        if (name == null || !name.startsWith("ZW_")) return false;
        if (name.startsWith("ZW_POINT_")) {
            String label = name.substring("ZW_POINT_".length());
            int color = 0xFFE7CA60;
            graphics.fill(x - 6, y - 6, x + 6, y + 6, 0xD8101614);
            graphics.renderOutline(x - 6, y - 6, 12, 12, color);
            graphics.drawCenteredString(Minecraft.getInstance().font, label, x, y - 4, color);
            return true;
        }
        ResourceLocation texture = name.startsWith("ZW_TENT_") ? TENT : (name.startsWith("ZW_OUTPOST_") || name.startsWith("ZW_RALLY_") ? OUTPOST : BASE);
        int color = name.endsWith("_BLUE") ? 0xFF51A7FF : 0xFFE84855;
        float red = ((color >> 16) & 255) / 255.0f;
        float green = ((color >> 8) & 255) / 255.0f;
        float blue = (color & 255) / 255.0f;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(red, green, blue, 1.0f);
        graphics.blit(texture, x - 8, y - 8, 0.0f, 0.0f, 16, 16, 16, 16);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        return true;
    }
}