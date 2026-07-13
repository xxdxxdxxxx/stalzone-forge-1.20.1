package ru.zonewars.client.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ZoneStatePayload(String state) implements CustomPayload {

    public static final Id<ZoneStatePayload> ID = new Id<>(Identifier.of("zonewars", "state"));
    public static final PacketCodec<RegistryByteBuf, ZoneStatePayload> CODEC =
        PacketCodec.tuple(PacketCodecs.STRING, ZoneStatePayload::state, ZoneStatePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
