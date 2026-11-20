package com.perrocazador;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Paquete servidor -> cliente con el estado de los perros cazadores del jugador. */
public record DogsPayload(List<Entry> dogs) implements CustomPacketPayload {

	public static final byte ALIVE = 0;
	public static final byte DEAD = 1;
	public static final byte FAR = 2; // no cargado / sin senal

	public record Entry(String name, byte state, double x, double y, double z,
			float health, float maxHealth, String dim, String activity) {
	}

	public static final CustomPacketPayload.Type<DogsPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(PerroCazadorMod.MOD_ID, "estado"));

	public static final StreamCodec<RegistryFriendlyByteBuf, DogsPayload> CODEC = StreamCodec.of(
			DogsPayload::write, DogsPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, DogsPayload payload) {
		buf.writeVarInt(payload.dogs.size());
		for (Entry e : payload.dogs) {
			buf.writeUtf(e.name);
			buf.writeByte(e.state);
			buf.writeDouble(e.x);
			buf.writeDouble(e.y);
			buf.writeDouble(e.z);
			buf.writeFloat(e.health);
			buf.writeFloat(e.maxHealth);
			buf.writeUtf(e.dim);
			buf.writeUtf(e.activity);
		}
	}

	private static DogsPayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<Entry> list = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			list.add(new Entry(buf.readUtf(), buf.readByte(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
					buf.readFloat(), buf.readFloat(), buf.readUtf(), buf.readUtf()));
		}
		return new DogsPayload(list);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
