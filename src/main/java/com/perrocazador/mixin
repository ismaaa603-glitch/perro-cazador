package com.perrocazador.mixin;

import com.perrocazador.PerroCazadorMod;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bloquea el teletransporte "vanilla" de las mascotas (lobos) hacia el dueno
 * (que ocurre a partir de ~12 bloques) para los perros cazadores.
 * Asi solo se teletransportan cuando se alejan 88 bloques, controlado por PerroCazadorMod.
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin {

	@Inject(method = "tryToTeleportToOwner", at = @At("HEAD"), cancellable = true)
	private void perroCazador$blockVanillaTeleport(CallbackInfo ci) {
		TamableAnimal self = (TamableAnimal) (Object) this;
		if (self.getTags().contains(PerroCazadorMod.HUNTER_TAG)) {
			ci.cancel();
		}
	}
}
