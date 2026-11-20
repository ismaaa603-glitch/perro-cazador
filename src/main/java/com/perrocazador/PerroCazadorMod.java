package com.perrocazador;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Perro Cazador: solo los lobos domados que tu marques (agachado + clic derecho con un hueso)
 * cazan animales de granja cerca de ti y te entregan el botin y algo de experiencia.
 */
public class PerroCazadorMod implements ModInitializer {
	public static final String HUNTER_TAG = "perro_cazador";

	/** Presas permitidas. */
	private static final Set<EntityType<?>> PREY = Set.of(
			EntityType.PIG, EntityType.SHEEP);

	private static final double OWNER_RADIUS = 24.0;
	private static final double SEARCH_RADIUS = 48.0;
	private static final int CHECK_INTERVAL = 20; // ticks

	@Override
	public void onInitialize() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (!player.isShiftKeyDown()) {
				return InteractionResult.PASS;
			}
			if (!player.getItemInHand(hand).is(Items.BONE)) {
				return InteractionResult.PASS;
			}
			if (!(entity instanceof TamableAnimal dog) || dog.getType() != EntityType.WOLF
					|| !dog.isTame() || !dog.isOwnedBy(player)) {
				return InteractionResult.PASS;
			}
			if (!level.isClientSide()) {
				if (dog.getTags().contains(HUNTER_TAG)) {
					dog.removeTag(HUNTER_TAG);
					dog.setTarget(null);
					player.displayClientMessage(Component.literal("Tu perro ya no caza."), true);
				} else {
					dog.addTag(HUNTER_TAG);
					player.displayClientMessage(Component.literal("¡Tu perro ahora es cazador!"), true);
				}
			}
			return InteractionResult.SUCCESS;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % CHECK_INTERVAL != 0) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.isSpectator() || !player.isAlive()) {
					continue;
				}
				tickPlayer(player);
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
			if (!PREY.contains(victim.getType())) {
				return;
			}
			Entity killer = source.getEntity();
			if (!(killer instanceof TamableAnimal dog) || !dog.getTags().contains(HUNTER_TAG)) {
				return;
			}
			if (!(dog.getOwner() instanceof ServerPlayer owner) || !(victim.level() instanceof ServerLevel level)) {
				return;
			}
			deliverLoot(level, victim, owner);
			owner.giveExperiencePoints(1 + owner.getRandom().nextInt(3));
		});
	}

	private static void tickPlayer(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		List<Mob> dogs = level.getEntitiesOfClass(Mob.class,
				player.getBoundingBox().inflate(SEARCH_RADIUS),
				e -> e instanceof TamableAnimal t
						&& e.getType() == EntityType.WOLF
						&& t.isTame()
						&& t.isOwnedBy(player)
						&& e.getTags().contains(HUNTER_TAG));

		for (Mob dog : dogs) {
			TamableAnimal tamable = (TamableAnimal) dog;
			if (tamable.isOrderedToSit()) {
				continue;
			}
			// Si esta peleando (por ejemplo defendiendote), no interferir.
			if (dog.getTarget() != null && dog.getTarget().isAlive()) {
				continue;
			}
			// Si esta herido, que descanse y te siga.
			if (dog.getHealth() < dog.getMaxHealth() * 0.4f) {
				continue;
			}
			if (dog.distanceTo(player) > SEARCH_RADIUS) {
				continue;
			}

			Mob prey = level.getEntitiesOfClass(Mob.class,
					player.getBoundingBox().inflate(OWNER_RADIUS),
					e -> PREY.contains(e.getType())
							&& e.isAlive()
							&& !e.isBaby()
							&& !e.hasCustomName()
							&& e != dog)
					.stream()
					.min(Comparator.comparingDouble(e -> e.distanceToSqr(dog)))
					.orElse(null);

			if (prey != null) {
				dog.setTarget(prey);
			}
		}
	}

	private static void deliverLoot(ServerLevel level, LivingEntity victim, ServerPlayer owner) {
		List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
				victim.getBoundingBox().inflate(3.0),
				item -> item.isAlive() && item.tickCount < 20);
		for (ItemEntity drop : drops) {
			ItemStack stack = drop.getItem().copy();
			drop.discard();
			owner.getInventory().add(stack);
			if (!stack.isEmpty()) {
				owner.drop(stack, false);
			}
		}
	}
}
