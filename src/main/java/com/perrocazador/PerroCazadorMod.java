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
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public class PerroCazadorMod implements ModInitializer {
	public static final String MOD_ID = "perro_cazador";
	public static final String HUNTER_TAG = "perro_cazador";

	private static final Set<EntityType<?>> PREY = Set.of(EntityType.PIG, EntityType.SHEEP);

	private static final double HUNT_RADIUS = 128.0; 
	private static final double LEASH_DISTANCE = 144.0; 
	
	private static final double TELEPORT_MIN = 8.0;
	private static final double TELEPORT_MAX = 12.0;

	private static final double FOLLOW_RANGE = 160.0; 
	private static final double DELIVER_DISTANCE = 3.0;

	private enum Mission {
		ENDERMAN(EntityType.ENDERMAN, Items.ENDER_PEARL, Items.ENDER_PEARL, "endermans"),
		PIG(EntityType.PIG, Items.PORKCHOP, Items.PORKCHOP, "cerdos"),
		SHEEP(EntityType.SHEEP, Items.MUTTON, Items.MUTTON, "ovejas");

		final EntityType<?> target;
		final Item trigger;
		final Item goal;
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
		boolean delivering;
		int deliverTicks = 0;

		boolean has(Item item) {
			for (ItemStack stack : items) {
				if (!stack.isEmpty() && stack.is(item)) {
					return true;
				}
			}
			return false;
		}
	}

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

	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final Map<UUID, Mission> MISSIONS = new HashMap<>();
	private static final Map<UUID, DogInfo> STATUS = new HashMap<>();
	private static final Set<UUID> HUD_ACTIVE = new HashSet<>();
	private static final Set<UUID> RECALLING = new HashSet<>();

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playS2C().register(DogsPayload.TYPE, DogsPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RecallPayload.TYPE, RecallPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(RecallPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ServerLevel level = (ServerLevel) player.level();
			List<Mob> dogs = level.getEntitiesOfClass(Mob.class,
					player.getBoundingBox().inflate(LEASH_DISTANCE + 64),
					e -> e instanceof TamableAnimal t && e.getType() == EntityType.WOLF
							&& t.isTame() && t.isOwnedBy(player) && e.getTags().contains(HUNTER_TAG));
			for (Mob dog : dogs) {
				RECALLING.add(dog.getUUID());
				TamableAnimal tamable = (TamableAnimal) dog;
				if (tamable.isOrderedToSit()) {
					tamable.setOrderedToSit(false);
				}
				dog.setTarget(null);
			}
		});

		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (!(entity instanceof TamableAnimal dog) || dog.getType() != EntityType.WOLF
					|| !dog.isTame() || !dog.isOwnedBy(player)) {
				return InteractionResult.PASS;
			}
			ItemStack held = player.getItemInHand(hand);

			if (dog.getHealth() < dog.getMaxHealth() && dog.isFood(held)) {
				return InteractionResult.PASS; 
			}

			if (player.isShiftKeyDown() && held.is(Items.BONE)) {
				if (!level.isClientSide()) {
					UUID id = dog.getUUID();
					if (dog.getTags().contains(HUNTER_TAG)) {
						dog.removeTag(HUNTER_TAG);
						dog.setTarget(null);
						MISSIONS.remove(id);
						STATUS.remove(id);
						RECALLING.remove(id);
						Pending carried = PENDING.remove(id);
						if (carried != null && level instanceof ServerLevel serverLevel) {
							dropPending(serverLevel, dog, carried, player);
						}
						player.displayClientMessage(Component.literal("Tu perro ya no caza."), true);
					} else {
						dog.addTag(HUNTER_TAG);
						STATUS.put(id, new DogInfo(player.getUUID()));
						sendStatus((ServerPlayer) player);
						player.displayClientMessage(Component.literal("¡Tu perro ahora es cazador!"), true);
					}
				}
				return InteractionResult.SUCCESS;
			}

			if (!player.isShiftKeyDown() && dog.getTags().contains(HUNTER_TAG)) {
				Mission mission = Mission.fromStack(held);
				if (mission != null) {
					if (!level.isClientSide()) {
						MISSIONS.put(dog.getUUID(), mission);
						dog.setTarget(null);
						player.displayClientMessage(Component.literal(
								"Tu perro ira exclusivamente a por " + mission.label + " cuando se levante."), true);
					}
					return InteractionResult.SUCCESS;
				}
			}
			return InteractionResult.PASS;
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof TamableAnimal dog && dog.getTags().contains(HUNTER_TAG)) {
				if (source.getEntity() instanceof EnderMan) {
					return false; 
				}
			}

			Entity attacker = source.getEntity();
			if (attacker instanceof TamableAnimal dog && dog.getTags().contains(HUNTER_TAG)) {
				if (entity instanceof LivingEntity livingVictim) {
					Mission mission = MISSIONS.get(dog.getUUID());
					if (!isHuntTarget(livingVictim, mission)) {
						return false;
					}
				}
			}
			return true;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.isSpectator() || !player.isAlive()) {
					continue;
				}
				
				tickPlayerAI(player);

				if (server.getTickCount() % 5 == 0) {
					sendStatus(player);
				}
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
			UUID victimId = victim.getUUID();

			if (victim instanceof TamableAnimal && victim.getTags().contains(HUNTER_TAG)) {
				STATUS.remove(victimId);
				MISSIONS.remove(victimId);
				RECALLING.remove(victimId);
			}

			Pending carried = PENDING.remove(victimId);
			if (carried != null && victim.level() instanceof ServerLevel deathLevel) {
				dropPending(deathLevel, victim, carried, null);
			}

			Entity killer = source.getEntity();
			if (!(killer instanceof TamableAnimal dog) || !dog.getTags().contains(HUNTER_TAG)) {
				return;
			}
			Mission mission = MISSIONS.get(dog.getUUID());
			boolean wanted = isHuntTarget(victim, mission);
			if (!wanted || !(victim.level() instanceof ServerLevel level)) {
				return;
			}

			Pending pending = PENDING.computeIfAbsent(dog.getUUID(), k -> new Pending());
			collectLoot(level, victim, pending);

			if (pending.items.size() >= 16) {
				pending.delivering = true;
			}
			if (mission != null) {
				if (pending.has(mission.goal)) {
					MISSIONS.remove(dog.getUUID());
					pending.delivering = true;
				}
			}
		});

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
			RECALLING.clear();
		});
	}

	private static boolean isHuntTarget(LivingEntity e, Mission mission) {
		if (mission != null) {
			return e.getType() == mission.target;
		}
		return PREY.contains(e.getType());
	}

	public static boolean isHuntTargetStatic(LivingEntity e, UUID dogId) {
		Mission mission = MISSIONS.get(dogId);
		return isHuntTarget(e, mission);
	}

	private static void tickPlayerAI(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		
		STATUS.entrySet().removeIf(entry -> 
				entry.getValue().owner.equals(player.getUUID()) 
				&& entry.getValue().state == DogsPayload.DEAD
		);

		List<Mob> dogs = level.getEntitiesOfClass(Mob.class,
				player.getBoundingBox().inflate(LEASH_DISTANCE + 64),
				e -> e instanceof TamableAnimal t
						&& e.getType() == EntityType.WOLF
						&& t.isTame()
						&& t.isAlive()
						&& t.isOwnedBy(player)
						&& e.getTags().contains(HUNTER_TAG));

		Set<UUID> seen = new HashSet<>();
		for (Mob dog : dogs) {
			seen.add(dog.getUUID());
			processDog(level, player, dog);
		}

		for (Map.Entry<UUID, DogInfo> entry : STATUS.entrySet()) {
			DogInfo info = entry.getValue();
			if (info.owner.equals(player.getUUID()) && info.state != DogsPayload.DEAD
					&& !seen.contains(entry.getKey())) {
				info.state = DogsPayload.FAR;
				info.activity = "Fuera de la zona cargada";
			}
		}
	}

	private static void processDog(ServerLevel level, ServerPlayer player, Mob dog) {
		TamableAnimal tamable = (TamableAnimal) dog;
		UUID id = dog.getUUID();

		updateDogInfo(dog, player);

		AttributeInstance range = dog.getAttribute(Attributes.FOLLOW_RANGE);
		if (range != null && range.getBaseValue() < FOLLOW_RANGE) {
			range.setBaseValue(FOLLOW_RANGE);
		}

		Pending pending = PENDING.get(id);
		Mission mission = MISSIONS.get(id);
		double dist = dog.distanceTo(player);

		if (RECALLING.contains(id)) {
			if (dog.distanceTo(player) < DELIVER_DISTANCE) {
				tamable.setOrderedToSit(true);
				RECALLING.remove(id);
				MISSIONS.remove(id);
				if (pending != null) {
					PENDING.remove(id);
					dropPending(level, dog, pending, player);
				}
				setActivity(dog, "Sentado (Llamado)");
			} else {
				if ((dog.getTarget() == null || !dog.getTarget().isAlive()) && dog.distanceTo(player) > LEASH_DISTANCE) {
					dog.setTarget(null);
					teleportNearOwner(level, dog, player);
				} else {
					dog.setTarget(null);
					if (dog.getNavigation().isDone()) dog.getNavigation().moveTo(player, 1.2);
				}
				setActivity(dog, "Volviendo al dueno");
			}
			return;
		}

		if (tamable.isOrderedToSit()) {
			if (pending != null) {
				PENDING.remove(id);
				dropPending(level, dog, pending, player);
			}
			if (mission != null) {
				setActivity(dog, "Sentado (Mision: " + mission.label + ")");
			} else {
				setActivity(dog, "Sentado");
			}
			return;
		}

		if ((dog.getTarget() == null || !dog.getTarget().isAlive())
				&& dog.distanceTo(player) > LEASH_DISTANCE) {
			dog.setTarget(null);
			teleportNearOwner(level, dog, player);
		}

		if (pending != null && pending.delivering) {
			pending.deliverTicks++;
			if (dist < DELIVER_DISTANCE || pending.deliverTicks > 600) {
				PENDING.remove(id);
				dropPending(level, dog, pending, player);
				setActivity(dog, "Botin entregado");
			} else {
				dog.setTarget(null);
				dog.getNavigation().moveTo(player, 1.3);
				setActivity(dog, "Trayendote el botin");
			}
			return;
		}

		if (dog.getTarget() != null && dog.getTarget().isAlive()) {
			if (!isHuntTarget(dog.getTarget(), mission)) {
				dog.setTarget(null);
				dog.setLastHurtByMob(null); 
			} else {
				setActivity(dog, mission != null ? "Cazando (mision: " + mission.label + ")" : "Cazando");
				return;
			}
		}

		if (dog.getHealth() < dog.getMaxHealth() * 0.4f) {
			setActivity(dog, "Descansando (herido)");
			if (dist > 10.0 && dog.getNavigation().isDone()) {
				dog.getNavigation().moveTo(player, 1.2);
			}
			return;
		}

		Mob prey = level.getEntitiesOfClass(Mob.class,
				player.getBoundingBox().inflate(HUNT_RADIUS),
				e -> isHuntTarget(e, mission)
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
			setActivity(dog, mission != null ? "Cazando (mision: " + mission.label + ")" : "Cazando");
		} else if (mission != null) {
			MISSIONS.remove(id);
			player.displayClientMessage(Component.literal(
					"No hay " + mission.label + " cerca (" + (int) HUNT_RADIUS
							+ " bloques). Mision cancelada."), true);
			if (dist > 10.0 && dog.getNavigation().isDone()) {
				dog.getNavigation().moveTo(player, 1.2);
			}
			setActivity(dog, "Siguiendote");
		} else {
			if (dist > 10.0 && dog.getNavigation().isDone()) {
				dog.getNavigation().moveTo(player, 1.2);
			}
			setActivity(dog, "Siguiendote");
		}
	}

	private static void updateDogInfo(Mob dog, ServerPlayer player) {
		DogInfo info = STATUS.computeIfAbsent(dog.getUUID(), k -> new DogInfo(player.getUUID()));
		info.state = DogsPayload.ALIVE;
		info.name = dog.hasCustomName() ? dog.getName().getString() : "Perro";
		info.x = dog.getX();
		info.y = dog.getY();
		info.z = dog.getZ();
		info.health = dog.getHealth();
		info.maxHealth = dog.getMaxHealth();
		info.dim = dog.level().dimension().toString();
	}

	private static void setActivity(Mob dog, String activity) {
		DogInfo info = STATUS.get(dog.getUUID());
		if (info != null) {
			info.activity = activity;
		}
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

	private static void collectLoot(ServerLevel level, LivingEntity victim, Pending pending) {
		List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
				victim.getBoundingBox().inflate(3.0),
				item -> item.isAlive() && item.tickCount < 20);
		for (ItemEntity drop : drops) {
			pending.items.add(drop.getItem().copy());
			drop.discard();
		}
		
		List<ExperienceOrb> xpDrops = level.getEntitiesOfClass(ExperienceOrb.class,
				victim.getBoundingBox().inflate(3.0),
				orb -> orb.isAlive());
		for (ExperienceOrb orb : xpDrops) {
			pending.xp += orb.getValue();
			orb.discard();
		}
	}

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
