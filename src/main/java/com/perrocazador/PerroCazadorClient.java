package com.perrocazador;

import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Dibuja en pantalla (arriba a la izquierda) si el perro esta vivo y a cuantos metros esta. */
public class PerroCazadorClient implements ClientModInitializer {

	private static volatile List<DogsPayload.Entry> dogs = List.of();

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(DogsPayload.TYPE,
				(payload, context) -> dogs = payload.dogs());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> dogs = List.of());
		HudRenderCallback.EVENT.register(PerroCazadorClient::render);
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		List<DogsPayload.Entry> list = dogs;
		if (mc.player == null || mc.level == null || list.isEmpty()) {
			return;
		}
		Font font = mc.font;
		String myDim = mc.level.dimension().toString();
		int x = 6;
		int y = 6;

		for (DogsPayload.Entry e : list) {
			boolean sameDim = myDim.equals(e.dim());
			double meters = Math.sqrt(mc.player.distanceToSqr(e.x(), e.y(), e.z()));
			String distText = sameDim ? Math.round(meters) + " m" : "otra dimension";

			String line1;
			int color;
			if (e.state() == DogsPayload.ALIVE) {
				line1 = e.name() + ": VIVO - " + distText;
				color = 0xFF55FF55;
			} else if (e.state() == DogsPayload.DEAD) {
				line1 = e.name() + ": MUERTO - cayo a " + distText;
				color = 0xFFFF5555;
			} else {
				line1 = e.name() + ": SIN SENAL - ultima pos. a " + distText;
				color = 0xFFFFAA00;
			}
			String line2 = e.activity();
			if (e.state() == DogsPayload.ALIVE) {
				line2 += " - Vida " + Math.round(e.health()) + "/" + Math.round(e.maxHealth());
			}

			int w = Math.max(font.width(line1), font.width(line2)) + 8;
			g.fill(x, y, x + w, y + 24, 0x90000000);
			g.drawString(font, line1, x + 4, y + 3, color, true);
			g.drawString(font, line2, x + 4, y + 13, 0xFFCCCCCC, true);
			y += 28;
		}
	}
}
