package com.perrocazador;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RecallPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RecallPayload> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath(PerroCazadorMod.MOD_ID, "recall"));
            
    public static final StreamCodec<RegistryFriendlyByteBuf, RecallPayload> CODEC = StreamCodec.unit(new RecallPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
