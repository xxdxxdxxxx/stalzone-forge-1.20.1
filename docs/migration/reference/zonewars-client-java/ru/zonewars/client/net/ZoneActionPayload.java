package ru.zonewars.client.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ZoneActionPayload(String action) implements CustomPayload {

    public static final Id<ZoneActionPayload> ID = new Id<>(Identifier.of("zonewars", "action"));
    public static final PacketCodec<RegistryByteBuf, ZoneActionPayload> CODEC =
        PacketCodec.tuple(PacketCodecs.STRING, ZoneActionPayload::action, ZoneActionPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
