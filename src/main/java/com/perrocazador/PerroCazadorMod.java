package com.perrocazador;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Perro Cazador: solo los lobos domados que tu marques (agachado + clic derecho con un hueso)
 * cazan animales de granja cerca de ti y te traen el botin y la experiencia y los sueltan a tus pies.
 */
public class PerroCazadorMod implements ModInitializer {
	public static final String HUNTER_TAG = "perro_cazador";

	/** Presas permitidas. */
	private static final Set<EntityType<?>> PREY = Set.of(
			EntityType.PIG, EntityType.SHEEP);

	private static final double OWNER_RADIUS = 2000.0; // practicamente: todos los chunks cargados
	private static final double SEARCH_RADIUS = 2000.0;
	private static final double FOLLOW_RANGE = 128.0; // hasta donde puede trazar ruta el perro
	private static final int CHECK_INTERVAL = 20; // ticks
	private static final double DELIVER_DISTANCE = 3.0; // bloques: a esta distancia del dueno suelta el botin
	private static final int DELIVER_TIMEOUT = 1200; // ticks (60 s): si no llega, suelta el botin donde este

	/** Botin que cada perro lleva encima hasta entregarlo (clave: UUID del perro). */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private static final class Pending {
		final List<ItemStack> items = new ArrayList<>();
		int xp;
		final long startTick;

		Pending(long startTick) {
			this.startTick = startTick;
		}
	}

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
			// Si muere un perro que llevaba botin, lo suelta donde muere.
			Pending carried = PENDING.remove(victim.getUUID());
			if (carried != null && victim.level() instanceof ServerLevel deathLevel) {
				dropPending(deathLevel, victim, carried, null);
			}

			if (!PREY.contains(victim.getType())) {
				return;
			}
			Entity killer = source.getEntity();
			if (!(killer instanceof TamableAnimal dog) || !dog.getTags().contains(HUNTER_TAG)) {
				return;
			}
			if (!(victim.level() instanceof ServerLevel level)) {
				return;
			}
			Pending pending = PENDING.computeIfAbsent(dog.getUUID(), k -> new Pending(level.getServer().getTickCount()));
			collectLoot(level, victim, pending);
			pending.xp += 1 + dog.getRandom().nextInt(3);
		});

		// Al cerrar el mundo, que nadie pierda el botin que llevaba el perro.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Map.Entry<UUID, Pending> entry : new ArrayList<>(PENDING.entrySet())) {
				for (ServerLevel level : server.getAllLevels()) {
					Entity dog = level.getEntity(entry.getKey());
					if (dog != null) {
						dropPending(level, dog, entry.getValue(), null);
						break;
					}
				}
			}
			PENDING.clear();
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
			net.minecraft.world.entity.ai.attributes.AttributeInstance range =
					dog.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
			if (range != null && range.getBaseValue() < FOLLOW_RANGE) {
				range.setBaseValue(FOLLOW_RANGE);
			}
			// Si lleva botin, va hacia ti y lo suelta; no caza hasta entregarlo.
			Pending pending = PENDING.get(dog.getUUID());
			if (pending != null) {
				long waited = level.getServer().getTickCount() - pending.startTick;
				if (tamable.isOrderedToSit() || waited > DELIVER_TIMEOUT || dog.distanceTo(player) < DELIVER_DISTANCE) {
					PENDING.remove(dog.getUUID());
					dropPending(level, dog, pending, player);
				} else {
					dog.getNavigation().moveTo(player, 1.3);
				}
				continue;
			}
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

	/** El perro "recoge" los drops de la presa: se quitan del suelo y se guardan para entregarlos. */
	private static void collectLoot(ServerLevel level, LivingEntity victim, Pending pending) {
		List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
				victim.getBoundingBox().inflate(3.0),
				item -> item.isAlive() && item.tickCount < 20);
		for (ItemEntity drop : drops) {
			pending.items.add(drop.getItem().copy());
			drop.discard();
		}
	}

	/** Suelta el botin (y la XP como orbes) en la posicion del perro, empujando los items hacia el dueno. */
	private static void dropPending(ServerLevel level, Entity dog, Pending pending, Entity owner) {
		for (ItemStack stack : pending.items) {
			ItemEntity drop = new ItemEntity(level, dog.getX(), dog.getY() + 0.5, dog.getZ(), stack);
			drop.setDefaultPickUpDelay();
			if (owner != null) {
				Vec3 push = owner.position().subtract(dog.position()).multiply(1.0, 0.0, 1.0).normalize().scale(0.15);
				drop.setDeltaMovement(push.x, 0.2, push.z);
			}
			level.addFreshEntity(drop);
		}
		if (pending.xp > 0) {
			ExperienceOrb.award(level, dog.position(), pending.xp);
		}
	}
}
