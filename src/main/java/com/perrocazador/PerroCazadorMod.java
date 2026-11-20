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
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Perro Cazador: solo los lobos domados que tu marques (agachado + clic derecho con un hueso)
 * cazan cerdos y ovejas, acumulan la carne y la XP y, cuando llevan bastante, te la traen y la sueltan.
 * No recogen lana ni cuero y no se teletransportan a ti.
 */
public class PerroCazadorMod implements ModInitializer {
	public static final String HUNTER_TAG = "perro_cazador";

	/** Presas permitidas. */
	private static final Set<EntityType<?>> PREY = Set.of(
			EntityType.PIG, EntityType.SHEEP);

	/** Unicos items que el perro recoge (lo demas, como la lana, se queda en el suelo). */
	private static final Set<Item> MEAT = Set.of(
			Items.PORKCHOP, Items.COOKED_PORKCHOP,
			Items.MUTTON, Items.COOKED_MUTTON);

	private static final double OWNER_RADIUS = 2000.0;
	private static final double SEARCH_RADIUS = 2000.0;
	private static final double FOLLOW_RANGE = 128.0; // hasta donde puede trazar ruta el perro
	private static final int CHECK_INTERVAL = 20; // ticks
	private static final int DELIVER_ITEMS = 16; // cuando lleva esta cantidad de items, viene a entregarlos
	private static final double DELIVER_DISTANCE = 3.0; // bloques: a esta distancia del dueno suelta el botin
	private static final int DELIVER_TIMEOUT = 1200; // ticks (60 s) desde que sale a entregar
	private static final double IDLE_RETURN_DISTANCE = 12.0; // sin presas, vuelve andando si esta mas lejos

	/** Botin que cada perro lleva encima hasta entregarlo (clave: UUID del perro). */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private static final class Pending {
		final List<ItemStack> items = new ArrayList<>();
		int xp;
		boolean delivering;
		long deliverStartTick;

		int itemCount() {
			int total = 0;
			for (ItemStack s : items) {
				total += s.getCount();
			}
			return total;
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
					// Si llevaba botin, lo suelta aqui.
					Pending carried = PENDING.remove(dog.getUUID());
					if (carried != null && dog.level() instanceof ServerLevel sl) {
						dropPending(sl, dog, carried, null);
					}
					// Devuelve el comportamiento normal de seguirte (con su teletransporte).
					GoalSelector goals = goalsOf(dog);
					if (goals != null) {
						goals.addGoal(6, new FollowOwnerGoal(dog, 1.0, 10.0F, 2.0F));
					}
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
			Pending pending = PENDING.computeIfAbsent(dog.getUUID(), k -> new Pending());
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
		long now = level.getServer().getTickCount();
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

			// Quita el objetivo de "seguir al dueno" del lobo: es el que lo teletransporta.
			// Se repite porque al recargar el mundo el lobo recupera sus objetivos por defecto.
			GoalSelector goals = goalsOf(dog);
			if (goals != null) {
				goals.removeAllGoals(goal -> goal instanceof FollowOwnerGoal);
			}

			Pending pending = PENDING.get(dog.getUUID());

			// Sentado: si lleva botin, lo suelta donde esta.
			if (tamable.isOrderedToSit()) {
				if (pending != null) {
					PENDING.remove(dog.getUUID());
					dropPending(level, dog, pending, player);
				}
				continue;
			}

			if (pending != null) {
				// Lleva suficiente: empieza a ir hacia ti.
				if (!pending.delivering && pending.itemCount() >= DELIVER_ITEMS) {
					pending.delivering = true;
					pending.deliverStartTick = now;
				}
				if (pending.delivering) {
					if (now - pending.deliverStartTick > DELIVER_TIMEOUT
							|| dog.distanceTo(player) < DELIVER_DISTANCE) {
						PENDING.remove(dog.getUUID());
						dropPending(level, dog, pending, player);
					} else {
						dog.setTarget(null);
						dog.getNavigation().moveTo(player, 1.3);
					}
					continue;
				}
			}

			// Si esta peleando (por ejemplo defendiendote), no interferir.
			if (dog.getTarget() != null && dog.getTarget().isAlive()) {
				continue;
			}
			// Si esta herido, que descanse y te siga andando.
			if (dog.getHealth() < dog.getMaxHealth() * 0.4f) {
				followOnFoot(dog, player);
				continue;
			}

			Mob prey = level.getEntitiesOfClass(Mob.class,
					dog.getBoundingBox().inflate(OWNER_RADIUS),
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
			} else {
				followOnFoot(dog, player);
			}
		}
	}

	private static java.lang.reflect.Field goalSelectorField;

	/** goalSelector es protected en Mob: se lee por reflexion buscando el primer campo de tipo GoalSelector. */
	private static GoalSelector goalsOf(Mob mob) {
		try {
			if (goalSelectorField == null) {
				for (java.lang.reflect.Field f : Mob.class.getDeclaredFields()) {
					if (f.getType() == GoalSelector.class) {
						f.setAccessible(true);
						goalSelectorField = f;
						break;
					}
				}
			}
			return goalSelectorField == null ? null : (GoalSelector) goalSelectorField.get(mob);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Seguir al dueno andando (sin teletransporte) cuando no hay nada que cazar. */
	private static void followOnFoot(Mob dog, ServerPlayer player) {
		if (dog.distanceTo(player) > IDLE_RETURN_DISTANCE) {
			dog.getNavigation().moveTo(player, 1.2);
		}
	}

	/** El perro "recoge" solo la carne de los drops; la lana y lo demas se quedan en el suelo. */
	private static void collectLoot(ServerLevel level, LivingEntity victim, Pending pending) {
		List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
				victim.getBoundingBox().inflate(3.0),
				item -> item.isAlive() && item.tickCount < 20 && MEAT.contains(item.getItem().getItem()));
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
