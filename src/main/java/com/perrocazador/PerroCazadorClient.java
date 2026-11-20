package com.perrocazador;

import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

public class PerroCazadorClient implements ClientModInitializer {

	private static volatile List<DogsPayload.Entry> dogs = List.of();
	private static KeyMapping recallKey;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(DogsPayload.TYPE,
				(payload, context) -> dogs = payload.dogs());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> dogs = List.of());
		HudRenderCallback.EVENT.register(PerroCazadorClient::render);

		// Registrar la tecla 'ñ' (GLFW_KEY_SEMICOLON en teclados US es la misma tecla que 'ñ' en ES)
		recallKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.perro_cazador.recall",
				GLFW.GLFW_KEY_SEMICOLON,
				"category.perro_cazador"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (recallKey.consumeClick()) {
				if (client.player != null) {
					// Solo funciona si tienes un hueso en la mano principal o secundaria
					if (client.player.getMainHandItem().is(Items.BONE) || client.player.getOffhandItem().is(Items.BONE)) {
						ClientPlayNetworking.send(new RecallPayload());
					}
				}
			}
		});
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
