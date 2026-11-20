package com.perrocazador;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Perro Cazador: los lobos domados que marques (agachado + clic derecho con un hueso) cazan
 * cerdos y ovejas y te traen el botin presa por presa, andando, y te lo sueltan al suelo.
 * Misiones: clic derecho al perro con ojo de ender (endermans), carne de cerdo cruda (cerdos)
 * o carne de oveja cruda (ovejas). Cuando consigue el objeto, vuelve a entregarlo.
 */
public class PerroCazadorMod implements ModInitializer {
	public static final String MOD_ID = "perro_cazador";
	public static final String HUNTER_TAG = "perro_cazador";

	/** Presas del modo normal. */
	private static final Set<EntityType<?>> PREY = Set.of(EntityType.PIG, EntityType.SHEEP);

	// ---- Distancias (ajustadas a 6 chunks de simulacion/renderizado) ----
	private static final int RENDER_CHUNKS = 6;
	/** Solo caza presas que esten a este radio del dueno. */
	private static final double HUNT_RADIUS = RENDER_CHUNKS * 16 - 16; // 80 bloques
	/** Si el perro se aleja mas de esto del dueno, se teletransporta cerca (nunca encima). */
	private static final double LEASH_DISTANCE = RENDER_CHUNKS * 16 - 8; // 88 bloques
	private static final double TELEPORT_MIN = 8.0;
	private static final double TELEPORT_MAX = 12.0;

	private static final double FOLLOW_RANGE = 128.0;
	private static final int CHECK_INTERVAL = 10; // ticks
	private static final double DELIVER_DISTANCE = 3.0; // a esta distancia suelta el botin

	/** Mision que se activa con clic derecho al perro con el objeto indicado. */
	private enum Mission {
		ENDERMAN(EntityType.ENDERMAN, Items.ENDER_EYE, Items.ENDER_PEARL, "endermans"),
		PIG(EntityType.PIG, Items.PORKCHOP, Items.PORKCHOP, "cerdos"),
		SHEEP(EntityType.SHEEP, Items.MUTTON, Items.MUTTON, "ovejas");

		final EntityType<?> target;
		final Item trigger; // objeto con el que se da la orden
		final Item goal; // objeto que tiene que conseguir para volver
		final String label;

		Mission(EntityType<?> target, Item trigger, Item goal, String label) {
			this.target = target;
			this.trigger = trigger;
			this.goal = goal;
			this.label = label;
		}

		static Mission fromStack(ItemStack stack) {
			for (Mission m : values()) {
				if (stack.is(m.trigger)) {
					return m;
				}
			}
			return null;
		}
	}

	private static final class Pending {
		final List<ItemStack> items = new ArrayList<>();
		int xp;
		boolean delivering; // ya va de camino a entregarlo

		boolean has(Item item) {
			for (ItemStack stack : items) {
				if (!stack.isEmpty() && stack.is(item)) {
					return true;
				}
			}
			return false;
		}
	}

	/** Estado que se ensena en la UI. */
	private static final class DogInfo {
		final UUID owner;
		byte state = DogsPayload.ALIVE;
		String name = "Perro";
		double x, y, z;
		float health, maxHealth;
		String dim = "";
		String activity = "";

		DogInfo(UUID owner) {
			this.owner = owner;
		}
	}

	private static final Map<UUID, Pending> PENDING = new HashMap<>(); // botin que lleva cada perro
	private static final Map<UUID, Mission> MISSIONS = new HashMap<>(); // mision activa de cada perro
	private static final Map<UUID, DogInfo> STATUS = new HashMap<>(); // para la UI
	private static final Set<UUID> HUD_ACTIVE = new HashSet<>(); // jugadores a los que ya se les envia la UI

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playS2C().register(DogsPayload.TYPE, DogsPayload.CODEC);

		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (!(entity instanceof TamableAnimal dog) || dog.getType() != EntityType.WOLF
					|| !dog.isTame() || !dog.isOwnedBy(player)) {
				return InteractionResult.PASS;
			}
			ItemStack held = player.getItemInHand(hand);

			// Agachado + hueso: activar / desactivar el modo cazador.
			if (player.isShiftKeyDown() && held.is(Items.BONE)) {
				if (!level.isClientSide()) {
					UUID id = dog.getUUID();
					if (dog.getTags().contains(HUNTER_TAG)) {
						dog.removeTag(HUNTER_TAG);
						dog.setTarget(null);
						MISSIONS.remove(id);
						STATUS.remove(id);
						Pending carried = PENDING.remove(id);
						if (carried != null && level instanceof ServerLevel serverLevel) {
							dropPending(serverLevel, dog, carried, player);
						}
						player.displayClientMessage(Component.literal("Tu perro ya no caza."), true);
					} else {
						dog.addTag(HUNTER_TAG);
						// Si tenias un perro muerto en la UI, se quita al marcar uno nuevo.
						STATUS.values().removeIf(i -> i.owner.equals(player.getUUID())
								&& i.state == DogsPayload.DEAD);
						STATUS.put(id, new DogInfo(player.getUUID()));
						player.displayClientMessage(Component.literal("¡Tu perro ahora es cazador!"), true);
					}
				}
				return InteractionResult.SUCCESS;
			}

			// Sin agacharse + ojo de ender / carne de cerdo / carne de oveja: mision.
			if (!player.isShiftKeyDown() && dog.getTags().contains(HUNTER_TAG)) {
				Mission mission = Mission.fromStack(held);
				if (mission != null) {
					if (!level.isClientSide()) {
						MISSIONS.put(dog.getUUID(), mission);
						dog.setTarget(null);
						player.displayClientMessage(Component.literal(
								"Tu perro ira a por " + mission.label + " y te traera lo que suelten."), true);
					}
					return InteractionResult.SUCCESS;
				}
			}
			return InteractionResult.PASS;
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
			UUID victimId = victim.getUUID();

			// Si muere un perro cazador: queda marcado como muerto en la UI.
			if (victim instanceof TamableAnimal && victim.getTags().contains(HUNTER_TAG)) {
				DogInfo info = STATUS.get(victimId);
				if (info != null) {
					info.state = DogsPayload.DEAD;
					info.x = victim.getX();
					info.y = victim.getY();
					info.z = victim.getZ();
					info.health = 0;
					info.activity = "Ha muerto";
				}
				MISSIONS.remove(victimId);
			}

			// Si muere un perro que llevaba botin, lo suelta donde muere.
			Pending carried = PENDING.remove(victimId);
			if (carried != null && victim.level() instanceof ServerLevel deathLevel) {
				dropPending(deathLevel, victim, carried, null);
			}

			Entity killer = source.getEntity();
			if (!(killer instanceof TamableAnimal dog) || !dog.getTags().contains(HUNTER_TAG)) {
				return;
			}
			Mission mission = MISSIONS.get(dog.getUUID());
			boolean wanted = mission != null ? victim.getType() == mission.target
					: PREY.contains(victim.getType());
			if (!wanted || !(victim.level() instanceof ServerLevel level)) {
				return;
			}

			Pending pending = PENDING.computeIfAbsent(dog.getUUID(), k -> new Pending());
			collectLoot(level, victim, pending);
			pending.xp += 1 + dog.getRandom().nextInt(3);

			if (mission == null) {
				// Modo normal: presa por presa, en cuanto mata una vuelve a entregarla.
				pending.delivering = true;
			} else if (pending.has(mission.goal)) {
				// Mision cumplida: ya tiene lo que le pediste, vuelve.
				MISSIONS.remove(dog.getUUID());
				pending.delivering = true;
			}
			// Si es una mision y aun no ha soltado lo que busca (p. ej. perla), sigue cazando.
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
			MISSIONS.clear();
			STATUS.clear();
			HUD_ACTIVE.clear();
		});
	}

	private static void tickPlayer(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		List<Mob> dogs = level.getEntitiesOfClass(Mob.class,
				player.getBoundingBox().inflate(LEASH_DISTANCE + 64),
				e -> e instanceof TamableAnimal t
						&& e.getType() == EntityType.WOLF
						&& t.isTame()
						&& t.isOwnedBy(player)
						&& e.getTags().contains(HUNTER_TAG));

		Set<UUID> seen = new HashSet<>();
		for (Mob dog : dogs) {
			seen.add(dog.getUUID());
			processDog(level, player, dog);
		}

		// Los perros de este jugador que no estan cargados: "sin senal" (los muertos se quedan como muertos).
		for (Map.Entry<UUID, DogInfo> entry : STATUS.entrySet()) {
			DogInfo info = entry.getValue();
			if (info.owner.equals(player.getUUID()) && info.state != DogsPayload.DEAD
					&& !seen.contains(entry.getKey())) {
				info.state = DogsPayload.FAR;
				info.activity = "Fuera de la zona cargada";
			}
		}
		sendStatus(player);
	}

	private static void processDog(ServerLevel level, ServerPlayer player, Mob dog) {
		TamableAnimal tamable = (TamableAnimal) dog;
		UUID id = dog.getUUID();

		AttributeInstance range = dog.getAttribute(Attributes.FOLLOW_RANGE);
		if (range != null && range.getBaseValue() < FOLLOW_RANGE) {
			range.setBaseValue(FOLLOW_RANGE);
		}

		Pending pending = PENDING.get(id);
		Mission mission = MISSIONS.get(id);

		// Si lo mandas sentarse, suelta lo que lleve donde esta y se queda quieto.
		if (tamable.isOrderedToSit()) {
			if (pending != null) {
				PENDING.remove(id);
				dropPending(level, dog, pending, player);
			}
			report(dog, player, "Sentado");
			return;
		}

		// Si te alejas tu (o el) mas de 6 chunks, se teletransporta cerca de ti, pero nunca justo encima.
		if (dog.distanceTo(player) > LEASH_DISTANCE) {
			dog.setTarget(null);
			teleportNearOwner(level, dog, player);
		}
		double dist = dog.distanceTo(player);

		// Entrega: vuelve andando y suelta el botin al suelo a pocos bloques de ti.
		if (pending != null && pending.delivering) {
			if (dist < DELIVER_DISTANCE) {
				PENDING.remove(id);
				dropPending(level, dog, pending, player);
				report(dog, player, "Botin entregado");
			} else {
				dog.setTarget(null);
				dog.getNavigation().moveTo(player, 1.3);
				report(dog, player, "Trayendote el botin");
			}
			return;
		}

		// Si esta peleando (por ejemplo defendiendote), no interferir.
		if (dog.getTarget() != null && dog.getTarget().isAlive()) {
			report(dog, player, mission != null ? "Mision: " + mission.label : "Cazando");
			return;
		}
		// Si esta herido, que descanse y te siga.
		if (dog.getHealth() < dog.getMaxHealth() * 0.4f) {
			report(dog, player, "Descansando (herido)");
			return;
		}

		Mob prey = level.getEntitiesOfClass(Mob.class,
				player.getBoundingBox().inflate(HUNT_RADIUS),
				e -> (mission != null ? e.getType() == mission.target : PREY.contains(e.getType()))
						&& e.isAlive()
						&& !e.isBaby()
						&& !e.hasCustomName()
						&& e != dog
						&& e.distanceTo(player) <= HUNT_RADIUS)
				.stream()
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(dog)))
				.orElse(null);

		if (prey != null) {
			dog.setTarget(prey);
			report(dog, player, mission != null ? "Mision: " + mission.label : "Cazando");
		} else if (mission != null) {
			MISSIONS.remove(id);
			player.displayClientMessage(Component.literal(
					"No hay " + mission.label + " cerca (" + (int) HUNT_RADIUS + " bloques). Mision cancelada."), true);
			report(dog, player, "Siguiendote");
		} else {
			report(dog, player, "Siguiendote");
		}
	}

	/** Actualiza lo que ve el jugador en la UI. */
	private static void report(Mob dog, ServerPlayer player, String activity) {
		DogInfo info = STATUS.computeIfAbsent(dog.getUUID(), k -> new DogInfo(player.getUUID()));
		info.state = DogsPayload.ALIVE;
		info.name = dog.hasCustomName() ? dog.getName().getString() : "Perro";
		info.x = dog.getX();
		info.y = dog.getY();
		info.z = dog.getZ();
		info.health = dog.getHealth();
		info.maxHealth = dog.getMaxHealth();
		info.dim = dog.level().dimension().toString();
		info.activity = activity;
	}

	private static void sendStatus(ServerPlayer player) {
		List<DogsPayload.Entry> list = new ArrayList<>();
		for (DogInfo i : STATUS.values()) {
			if (i.owner.equals(player.getUUID())) {
				list.add(new DogsPayload.Entry(i.name, i.state, i.x, i.y, i.z,
						i.health, i.maxHealth, i.dim, i.activity));
			}
		}
		boolean had = HUD_ACTIVE.contains(player.getUUID());
		if (list.isEmpty() && !had) {
			return;
		}
		if (list.isEmpty()) {
			HUD_ACTIVE.remove(player.getUUID());
		} else {
			HUD_ACTIVE.add(player.getUUID());
		}
		ServerPlayNetworking.send(player, new DogsPayload(list));
	}

	/** Teletransporta al perro a 8-12 bloques del dueno, en suelo firme. Nunca encima de el. */
	private static boolean teleportNearOwner(ServerLevel level, Mob dog, ServerPlayer owner) {
		RandomSource random = dog.getRandom();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int attempt = 0; attempt < 30; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = TELEPORT_MIN + random.nextDouble() * (TELEPORT_MAX - TELEPORT_MIN);
			int x = (int) Math.floor(owner.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(owner.getZ() + Math.sin(angle) * distance);
			for (int y = owner.getBlockY() + 3; y >= owner.getBlockY() - 5; y--) {
				pos.set(x, y, z);
				if (isStandable(level, pos)) {
					if (dog.randomTeleport(x + 0.5, y, z + 0.5, true)) {
						dog.getNavigation().stop();
						return true;
					}
					break;
				}
			}
		}
		return false;
	}

	private static boolean isStandable(ServerLevel level, BlockPos feet) {
		BlockPos below = feet.below();
		BlockPos head = feet.above();
		return !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
				&& level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getBlockState(head).getCollisionShape(level, head).isEmpty()
				&& level.getFluidState(feet).isEmpty();
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

	/** Suelta el botin (y la XP como orbes) en el suelo, en la posicion del perro, empujando los items hacia el dueno. */
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
