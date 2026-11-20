package com.perrocazador.mixin;

import com.perrocazador.PerroCazadorMod;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Wolf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Wolf.class)
public abstract class WolfMixin {

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void perroCazador$blockInvalidTargets(LivingEntity target, CallbackInfo ci) {
        Wolf self = (Wolf) (Object) this;
        if (self.getTags().contains(PerroCazadorMod.HUNTER_TAG)) {
            // Si el objetivo no es nulo y no es una presa válida, bloqueamos la asignación.
            if (target != null && !PerroCazadorMod.isHuntTargetStatic(target, self.getUUID())) {
                ci.cancel();
            }
        }
    }
}
