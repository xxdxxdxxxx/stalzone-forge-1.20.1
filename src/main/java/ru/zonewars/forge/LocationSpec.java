package ru.zonewars.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

record LocationSpec(String world, double x, double y, double z, float yaw, float pitch) {
    static LocationSpec from(ServerPlayer player) {
        String world = player.serverLevel().dimension() == Level.OVERWORLD ? "world" : player.serverLevel().dimension().location().toString();
        return new LocationSpec(world, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    static LocationSpec fromBlock(ServerPlayer player, BlockPos pos) {
        String world = player.serverLevel().dimension() == Level.OVERWORLD ? "world" : player.serverLevel().dimension().location().toString();
        return new LocationSpec(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), player.getXRot());
    }
}
