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
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class PerroCazadorMod implements ModInitializer {
	public static final String HUNTER_TAG = "perro_cazador";

	private static final Set<EntityType<?>> PREY = Set.of(
			EntityType.PIG, EntityType.SHEEP);

	private static final Set<Item> MEAT = Set.of(
			Items.PORKCHOP, Items.COOKED_PORKCHOP,
			Items.MUTTON, Items.COOKED_MUTTON);

	private static final double SEARCH_RADIUS = 64.0;
	private static final double MAX_WANDER_DIST = 96.0;
	private static final int MAX_CAPACITY = 16;
	private static final int CHECK_INTERVAL = 20;
	private static final double DELIVER_DISTANCE = 3.0;

	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private static final class Pending {
		final List<ItemStack> items = new ArrayList<>();
		int xp;
		boolean returningToOwner = false;

		int itemCount() {
			int total = 0;
			for (ItemStack s : items) {
				total += s.getCount();
			}
			return total;
		}

		public void saveToNbt(CompoundTag tag, HolderLookup.Provider registries) {
			RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);

			CompoundTag data = new CompoundTag();
			data.putInt("XP", xp);
			data.putBoolean("Returning", returningToOwner);

			ListTag list = new ListTag();
			for (ItemStack stack : items) {
				ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent(list::add);
			}
			data.put("Items", list);
			tag.put("PerroCazadorData", data);
		}

		public static Pending loadFromNbt(CompoundTag tag, HolderLookup.Provider registries) {
			if (!tag.contains("PerroCazadorData")) return null;
			RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);

			CompoundTag data = tag.getCompoundOrEmpty("PerroCazadorData");
			Pending pending = new Pending();
			pending.xp = data.getIntOr("XP", 0);
			pending.returningToOwner = data.getBooleanOr("Returning", false);

			ListTag list = data.getListOrEmpty("Items");
			for (int i = 0; i < list.size(); i++) {
				list.getCompound(i)
						.flatMap(t -> ItemStack.CODEC.parse(ops, t).result())
						.ifPresent(pending.items::add);
			}
			return pending;
		}
	}

	@Override
	public void onInitialize() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (!(entity instanceof Wolf dog) || !dog.isTame() || !dog.isOwnedBy(player)) {
				return InteractionResult.PASS;
			}

			boolean hasBone = player.getItemInHand(hand).is(Items.BONE);

			if (!level.isClientSide()) {
				syncFromEntity(dog, level.registryAccess());

				if (player.isShiftKeyDown() && hasBone) {
					if (dog.getTags().contains(HUNTER_TAG)) {
						dog.removeTag(HUNTER_TAG);
						dog.setTarget(null);
						Pending carried = PENDING.remove(dog.getUUID());
						clearEntityNbt(dog, level.registryAccess());
						if (carried != null && dog.level() instanceof ServerLevel sl) {
							dropPending(sl, dog, carried, player);
						}
						restoreDefaultGoals(dog);
						player.displayClientMessage(Component.literal("Tu perro ya no es cazador."), true);
					} else {
						dog.addTag(HUNTER_TAG);
						removeFollowOwnerGoal(dog);
						player.displayClientMessage(Component.literal("¡Tu perro ahora es cazador!"), true);
					}
					return InteractionResult.SUCCESS;
				}

				if (hasBone && PENDING.containsKey(dog.getUUID())) {
					Pending carried = PENDING.remove(dog.getUUID());
					clearEntityNbt(dog, level.registryAccess());
					if (carried != null && dog.level() instanceof ServerLevel sl) {
						dropPending(sl, dog, carried, player);
						player.displayClientMessage(Component.literal("Tu perro te ha soltado el botín."), true);
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
				tickPlayerDogs(player);
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
			if (!PREY.contains(victim.getType())) {
				return;
			}
			Entity killer = source.getEntity();
			if (!(killer instanceof Wolf dog) || !dog.getTags().contains(HUNTER_TAG)) {
				return;
			}
			if (!(victim.level() instanceof ServerLevel level)) {
				return;
			}

			syncFromEntity(dog, level.registryAccess());
			Pending pending = PENDING.computeIfAbsent(dog.getUUID(), k -> new Pending());
			collectLoot(level, victim, pending);
			pending.xp += 1 + dog.getRandom().nextInt(3);

			if (pending.itemCount() >= MAX_CAPACITY) {
				pending.returningToOwner = true;
			}
			syncToEntity(dog, pending, level.registryAccess());
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Map.Entry<UUID, Pending> entry : new ArrayList<>(PENDING.entrySet())) {
				for (ServerLevel level : server.getAllLevels()) {
					Entity entity = level.getEntity(entry.getKey());
					if (entity instanceof Wolf dog) {
						syncToEntity(dog, entry.getValue(), level.registryAccess());
						break;
					}
				}
			}
			PENDING.clear();
		});
	}

	/** Vuelca el estado actual de la entidad a un CompoundTag (API ValueOutput de 1.21.6+). */
	private static CompoundTag saveEntity(Wolf dog, HolderLookup.Provider registries) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		dog.saveWithoutId(output);
		return output.buildResult();
	}

	/** Carga un CompoundTag en la entidad (API ValueInput de 1.21.6+). */
	private static void loadEntity(Wolf dog, CompoundTag tag, HolderLookup.Provider registries) {
		dog.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
	}

	private static void syncFromEntity(Wolf dog, HolderLookup.Provider registries) {
		CompoundTag tag = saveEntity(dog, registries);
		Pending pending = Pending.loadFromNbt(tag, registries);
		if (pending != null) {
			PENDING.put(dog.getUUID(), pending);
		} else {
			PENDING.remove(dog.getUUID());
		}
	}

	private static void syncToEntity(Wolf dog, Pending pending, HolderLookup.Provider registries) {
		CompoundTag tag = saveEntity(dog, registries);
		if (pending != null && !pending.items.isEmpty()) {
			pending.saveToNbt(tag, registries);
		} else {
			tag.remove("PerroCazadorData");
		}
		loadEntity(dog, tag, registries);
	}

	private static void clearEntityNbt(Wolf dog, HolderLookup.Provider registries) {
		CompoundTag tag = saveEntity(dog, registries);
		tag.remove("PerroCazadorData");
		loadEntity(dog, tag, registries);
	}

	private static void tickPlayerDogs(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		List<Mob> dogs = level.getEntitiesOfClass(Mob.class,
				player.getBoundingBox().inflate(SEARCH_RADIUS * 2),
				e -> e instanceof Wolf w
						&& w.isTame()
						&& w.isOwnedBy(player)
						&& e.getTags().contains(HUNTER_TAG));

		boolean playerHoldingBone = player.getMainHandItem().is(Items.BONE) || player.getOffhandItem().is(Items.BONE);

		for (Mob dog : dogs) {
			Wolf wolf = (Wolf) dog;
			removeFollowOwnerGoal(wolf);
			syncFromEntity(wolf, level.registryAccess());

			Pending pending = PENDING.get(dog.getUUID());

			if (wolf.isOrderedToSit()) {
				continue;
			}

			if (playerHoldingBone || (pending != null && pending.returningToOwner)) {
				if (pending != null && dog.distanceTo(player) <= DELIVER_DISTANCE) {
					PENDING.remove(dog.getUUID());
					clearEntityNbt(wolf, level.registryAccess());
					dropPending(level, dog, pending, player);
					player.displayClientMessage(Component.literal("Tu perro te ha entregado la carga."), true);
				} else {
					dog.setTarget(null);
					dog.getNavigation().moveTo(player, 1.25);
				}
				continue;
			}

			if (wolf.getHealth() < wolf.getMaxHealth() * 0.4f) {
				if (dog.distanceTo(player) > 6.0) {
					dog.getNavigation().moveTo(player, 1.1);
				}
				continue;
			}

			if (dog.getTarget() != null && dog.getTarget().isAlive()) {
				continue;
			}

			Mob prey = level.getEntitiesOfClass(Mob.class,
					player.getBoundingBox().inflate(SEARCH_RADIUS),
					e -> PREY.contains(e.getType())
							&& e.isAlive()
							&& !e.isBaby()
							&& !e.hasCustomName()
							&& e != dog
							&& e.distanceToSqr(player) <= (MAX_WANDER_DIST * MAX_WANDER_DIST))
					.stream()
					.min(Comparator.comparingDouble(e -> e.distanceToSqr(dog)))
					.orElse(null);

			if (prey != null) {
				dog.setTarget(prey);
				dog.getNavigation().moveTo(prey, 1.25);
			} else {
				if (dog.distanceTo(player) > MAX_WANDER_DIST) {
					dog.getNavigation().moveTo(player, 1.1);
				}
			}
		}
	}

	private static java.lang.reflect.Field goalSelectorField;

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

	private static void removeFollowOwnerGoal(Mob dog) {
		GoalSelector goals = goalsOf(dog);
		if (goals != null) {
			goals.removeAllGoals(goal -> goal instanceof FollowOwnerGoal);
		}
	}

	private static void restoreDefaultGoals(Mob dog) {
		GoalSelector goals = goalsOf(dog);
		if (goals != null && dog instanceof Wolf wolf) {
			goals.addGoal(6, new FollowOwnerGoal(wolf, 1.0, 10.0F, 2.0F));
		}
	}

	private static void collectLoot(ServerLevel level, LivingEntity victim, Pending pending) {
		List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
				victim.getBoundingBox().inflate(3.0),
				item -> item.isAlive() && item.tickCount < 40 && MEAT.contains(item.getItem().getItem()));
		for (ItemEntity drop : drops) {
			pending.items.add(drop.getItem().copy());
			drop.discard();
		}
	}

	private static void dropPending(ServerLevel level, Entity dog, Pending pending, Entity owner) {
		for (ItemStack stack : pending.items) {
			ItemEntity drop = new ItemEntity(level, dog.getX(), dog.getY() + 0.5, dog.getZ(), stack);
			drop.setDefaultPickUpDelay();
			if (owner != null) {
				Vec3 push = owner.position().subtract(dog.position()).multiply(1.0, 0.0, 1.0).normalize().scale(0.2);
				drop.setDeltaMovement(push.x, 0.25, push.z);
			}
			level.addFreshEntity(drop);
		}
		if (pending.xp > 0) {
			ExperienceOrb.award(level, dog.position(), pending.xp);
		}
		pending.items.clear();
		pending.xp = 0;
	}
}
