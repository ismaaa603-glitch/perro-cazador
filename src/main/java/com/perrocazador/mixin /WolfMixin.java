package com.perrocazador.mixin;

import com.perrocazador.PerroCazadorMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Apuntamos a Mob.class porque ahí es donde está el método setTarget
@Mixin(Mob.class) 
public abstract class WolfMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void perroCazador$blockInvalidTargets(LivingEntity target, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        
        // Comprobamos si el mob es un lobo domado con nuestra etiqueta
        if (self instanceof TamableAnimal tamable 
                && tamable.getType() == EntityType.WOLF 
                && self.getTags().contains(PerroCazadorMod.HUNTER_TAG)) {
            
            // Si el objetivo no es válido (o está bajo tierra / descartado), bloqueamos la orden
            if (target != null && !PerroCazadorMod.canTargetStatic(target, self.getUUID())) {
                ci.cancel();
            }
        }
    }
}
