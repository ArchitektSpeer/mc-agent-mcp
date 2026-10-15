package com.mcagent.mcp.client.reflex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.ChatLog;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.config.ReflexSettings;
import com.mcagent.mcp.tools.ToolCategory;
import com.mcagent.mcp.tools.ToolPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Per-tick survival reactions that keep a slow agent alive between its turns. An LLM needs
 * seconds per decision; a zombie hits every second and a creeper explodes 1.5 s after it
 * starts hissing. These reflexes act within one tick (50 ms):
 * <ul>
 *   <li>defend: hit hostile mobs in reach with the best hotbar weapon when the cooldown is full;</li>
 *   <li>creepers: hit (knockback) and back away;</li>
 *   <li>eat safe hotbar food when hungry and no enemy is near;</li>
 *   <li>fill empty armor slots from the inventory;</li>
 *   <li>swim up when out of air, jump out of lava;</li>
 *   <li>report damage and interrupt the agent's running action so it can react.</li>
 * </ul>
 * Each reflex only runs if the owner allows the tool it imitates ({@link ToolPolicy#allows}).
 * Everything that happens is logged to the event log, which {@code observe} returns.
 * Game thread only.
 */
public final class Reflexes {
	private static final double THREAT_RADIUS = 16;
	private static final double CREEPER_DANGER = 4.0;
	private static final int COMBAT_LINGER_TICKS = 40;
	private static final int FLEE_TICKS = 24;
	private static final double CHARGE_RADIUS = 12;
	private static final int CHARGE_TICKS = 8;
	/** Below this, charging an archer is more likely to kill the player than the archer. */
	private static final float CHARGE_MIN_HEALTH = 8;

	private final ReflexSettings settings;
	private final AgentControl control;
	private final ToolPolicy policy;
	private final ChatLog events;

	private float lastHealth = -1;
	private int restoreSlot = -1;
	private int combatTicks;
	private int engagedId = Integer.MIN_VALUE;
	private int eating;
	private int eatFoodBefore;
	private int creeperCooldown;
	private int fluidCooldown;
	private int armorCooldown;
	private int chargeCooldown;
	private boolean fluidEpisode;
	private int chargingId = Integer.MIN_VALUE;

	public Reflexes(ReflexSettings settings, AgentControl control, ToolPolicy policy, ChatLog events) {
		this.settings = settings;
		this.control = control;
		this.policy = policy;
		this.events = events;
	}

	public ReflexSettings settings() {
		return settings;
	}

	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null) {
			lastHealth = -1;
			return;
		}
		if (p.isDeadOrDying()) {
			if (lastHealth > 0) events.add("death", null, "died: " + damageCause(mc, p));
			lastHealth = 0;
			resetCombat();
			return;
		}
		trackDamage(mc, p);
		if (mc.screen != null) return; // the agent is working in a GUI

		List<Entity> hostiles = hostiles(mc, p, THREAT_RADIUS);
		if (creeperCooldown > 0) creeperCooldown--;
		if (fluidCooldown > 0) fluidCooldown--;
		if (armorCooldown > 0) armorCooldown--;

		if (settings.escapeFluids && allowed("move", ToolCategory.MOVEMENT)) escapeFluids(p);
		if (settings.evadeCreepers) evadeCreepers(mc, p, hostiles);
		boolean meleeTarget = false;
		if (settings.autoDefend && allowed("attack_entity", ToolCategory.INTERACTION)) meleeTarget = defend(mc, p, hostiles);
		if (settings.chargeRanged && !meleeTarget && allowed("move", ToolCategory.MOVEMENT)
				&& allowed("attack_entity", ToolCategory.INTERACTION)) {
			chargeRanged(p, hostiles);
		}
		finishCombat(p);
		if (settings.autoEat) eat(mc, p, hostiles);
		if (settings.autoArmor && armorCooldown == 0 && hostiles.isEmpty()) equipArmor(mc, p);
	}

	/** Hostile mobs within {@code radius}, nearest first. Used by observe as well. */
	public static List<Entity> hostiles(Minecraft mc, LocalPlayer p, double radius) {
		List<Entity> list = mc.level.getEntities(p, p.getBoundingBox().inflate(radius),
				e -> e instanceof Enemy && e.isAlive() && e.distanceToSqr(p) <= radius * radius);
		list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
		return list;
	}

	/** Compact threat list for observe and action results. */
	public static JsonArray describeThreats(Minecraft mc, LocalPlayer p, int max) {
		JsonArray arr = new JsonArray();
		for (Entity e : hostiles(mc, p, THREAT_RADIUS)) {
			if (arr.size() >= max) break;
			JsonObject t = new JsonObject();
			t.addProperty("id", e.getId());
			t.addProperty("type", typeId(e));
			t.addProperty("distance", Math.round(Math.sqrt(e.distanceToSqr(p)) * 10) / 10.0);
			if (e instanceof LivingEntity le) t.addProperty("health", le.getHealth());
			if (e instanceof Creeper c && c.getSwellDir() > 0) t.addProperty("hissing", true);
			arr.add(t);
		}
		return arr;
	}

	// ---- damage --------------------------------------------------------------------

	private void trackDamage(Minecraft mc, LocalPlayer p) {
		float h = p.getHealth();
		if (lastHealth > 0 && h < lastHealth - 0.01f) {
			String cause = damageCause(mc, p);
			events.add("damage", null, "took " + fmt(lastHealth - h) + " damage from " + cause
					+ ", health " + fmt(h) + "/20");
			if (settings.interruptOnDamage) {
				control.interruptAgent(mc, "took damage from " + cause + " (health " + fmt(h) + ")", true);
			}
		}
		lastHealth = h;
	}

	private static String damageCause(Minecraft mc, LocalPlayer p) {
		if (p.isInLava()) return "lava";
		if (p.isOnFire()) return "fire";
		if (p.isUnderWater() && p.getAirSupply() <= 0) return "drowning";
		List<Entity> near = hostiles(mc, p, 6);
		if (!near.isEmpty()) return typeId(near.get(0)) + " #" + near.get(0).getId();
		if (p.getFoodData().getFoodLevel() == 0) return "starvation";
		return "unknown (fall, projectile or effect)";
	}

	// ---- combat --------------------------------------------------------------------

	private void evadeCreepers(Minecraft mc, LocalPlayer p, List<Entity> hostiles) {
		if (creeperCooldown > 0) return;
		for (Entity e : hostiles) {
			if (!(e instanceof Creeper c)) continue;
			double d = Math.sqrt(c.distanceToSqr(p));
			if (d > CREEPER_DANGER) return; // list is nearest first
			AgentControl.lookAt(p, c.getBoundingBox().getCenter());
			boolean hit = false;
			if (allowed("attack_entity", ToolCategory.INTERACTION) && p.isWithinEntityInteractionRange(c, 0)
					&& p.getAttackStrengthScale(0.5f) >= 0.9f) {
				selectWeapon(p);
				mc.gameMode.attack(p, c);
				p.swing(InteractionHand.MAIN_HAND);
				hit = true;
			}
			if (allowed("move", ToolCategory.MOVEMENT)) {
				// A creeper defuses once its target is 7+ blocks away; sprinting (~5.6 b/s) outruns it,
				// walking backwards does not. Turn away and sprint.
				double dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
				float away = (float) Math.toDegrees(Math.atan2(-dx, dz));
				p.setYRot(away);
				p.setYHeadRot(away);
				control.reflexMove(new Input(true, false, false, false, false, false, true), FLEE_TICKS,
						"fleeing a creeper");
			}
			events.add("reflex", null, "creeper #" + c.getId() + " at " + fmt(d) + " blocks"
					+ (c.getSwellDir() > 0 ? " (hissing)" : "") + ": " + (hit ? "hit it, " : "") + "sprinted away");
			creeperCooldown = FLEE_TICKS;
			combatTicks = COMBAT_LINGER_TICKS;
			return;
		}
	}

	/** @return true when a hostile was in melee reach (whether or not the cooldown allowed a hit) */
	private boolean defend(Minecraft mc, LocalPlayer p, List<Entity> hostiles) {
		Entity target = null;
		for (Entity e : hostiles) {
			if (e instanceof Creeper) continue; // handled by evadeCreepers
			if (p.isWithinEntityInteractionRange(e, 0)) {
				target = e;
				break;
			}
		}
		if (target == null) return false;
		combatTicks = COMBAT_LINGER_TICKS;
		if (control.isBreakingBlock()) control.cancelBreak(mc, "interrupted: defending against " + typeId(target));
		if (target.getId() != engagedId) {
			engagedId = target.getId();
			events.add("reflex", null, "defending against " + typeId(target) + " #" + target.getId());
		}
		if (p.isUsingItem() || p.getAttackStrengthScale(0.5f) < 0.95f) return true;
		selectWeapon(p);
		AgentControl.lookAt(p, target.getBoundingBox().getCenter());
		mc.gameMode.attack(p, target);
		p.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	/**
	 * Archers out-range melee and win a standing fight; the vanilla answer is to close in.
	 * Sprint at the nearest visible ranged mob in short bursts; defend() hits it on arrival.
	 */
	private void chargeRanged(LocalPlayer p, List<Entity> hostiles) {
		if (chargeCooldown > 0) {
			chargeCooldown--;
			return;
		}
		if (p.getHealth() < CHARGE_MIN_HEALTH) return;
		for (Entity e : hostiles) {
			if (!(e instanceof RangedAttackMob) || e instanceof Creeper) continue;
			double d = Math.sqrt(e.distanceToSqr(p));
			if (d > CHARGE_RADIUS) return;
			if (!p.hasLineOfSight(e)) continue;
			AgentControl.lookAt(p, e.getBoundingBox().getCenter());
			control.reflexMove(new Input(true, false, false, false, false, false, true), CHARGE_TICKS,
					"charging a " + typeId(e));
			if (e.getId() != chargingId) {
				chargingId = e.getId();
				events.add("reflex", null, "charging " + typeId(e) + " #" + e.getId() + " at " + fmt(d) + " blocks");
			}
			combatTicks = COMBAT_LINGER_TICKS;
			chargeCooldown = CHARGE_TICKS;
			return;
		}
	}

	/** After the fight: report kills and give the agent its previous hotbar slot back. */
	private void finishCombat(LocalPlayer p) {
		if (engagedId != Integer.MIN_VALUE) {
			Entity e = p.level().getEntity(engagedId);
			if (e == null || !e.isAlive()) {
				events.add("reflex", null, "target #" + engagedId + " is dead or gone");
				engagedId = Integer.MIN_VALUE;
			}
		}
		if (combatTicks > 0 && --combatTicks == 0) resetCombat(p);
	}

	private void resetCombat() {
		combatTicks = 0;
		engagedId = Integer.MIN_VALUE;
	}

	private void resetCombat(LocalPlayer p) {
		resetCombat();
		if (restoreSlot >= 0 && eating == 0) {
			p.getInventory().setSelectedSlot(restoreSlot);
			restoreSlot = -1;
		}
	}

	private void selectWeapon(LocalPlayer p) {
		if (!allowed("select_slot", ToolCategory.INVENTORY)) return;
		Inventory inv = p.getInventory();
		int best = ReflexMath.bestWeapon(hotbar(inv));
		if (best >= 0 && best != inv.getSelectedSlot()) {
			if (restoreSlot < 0) restoreSlot = inv.getSelectedSlot();
			inv.setSelectedSlot(best);
		}
	}

	// ---- food ----------------------------------------------------------------------

	private void eat(Minecraft mc, LocalPlayer p, List<Entity> hostiles) {
		if (eating > 0) {
			if (--eating == 0 || !control.isUsingKey()) {
				eating = 0;
				int now = p.getFoodData().getFoodLevel();
				events.add("reflex", null, now > eatFoodBefore
						? "ate (food " + eatFoodBefore + " -> " + now + ")"
						: "eating was interrupted (food " + now + ")");
				if (restoreSlot >= 0 && combatTicks == 0) {
					p.getInventory().setSelectedSlot(restoreSlot);
					restoreSlot = -1;
				}
			}
			return;
		}
		int food = p.getFoodData().getFoodLevel();
		if (!ReflexMath.wantsToEat(food, p.getHealth(), settings.eatBelowFood)) return;
		if (combatTicks > 0 || p.isUsingItem() || control.isUsingKey()) return;
		if (!hostiles.isEmpty() && hostiles.get(0).distanceToSqr(p) < 36) return;
		if (!allowed("use_item", ToolCategory.INTERACTION) || !allowed("select_slot", ToolCategory.INVENTORY)) return;
		Inventory inv = p.getInventory();
		int slot = ReflexMath.pickFood(hotbar(inv), food, p.getHealth());
		if (slot < 0) return;
		if (restoreSlot < 0) restoreSlot = inv.getSelectedSlot();
		inv.setSelectedSlot(slot);
		eatFoodBefore = food;
		control.holdUse(mc, 40);
		eating = 45;
	}

	// ---- armor ---------------------------------------------------------------------

	private void equipArmor(Minecraft mc, LocalPlayer p) {
		armorCooldown = 40;
		if (!(p.containerMenu instanceof InventoryMenu menu)) return;
		if (!allowed("click_slot", ToolCategory.INVENTORY) || !menu.getCarried().isEmpty()) return;
		for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			if (!p.getItemBySlot(es).isEmpty()) continue;
			Slot best = null;
			int bestTier = 0;
			for (Slot s : menu.slots) {
				if (!(s.container instanceof Inventory) || s.getContainerSlot() >= 36) continue;
				ItemStack st = s.getItem();
				Equippable eq = st.get(DataComponents.EQUIPPABLE);
				if (eq == null || eq.slot() != es) continue;
				int tier = Math.max(1, ReflexMath.armorTier(itemId(st)));
				if (tier > bestTier) {
					bestTier = tier;
					best = s;
				}
			}
			if (best != null) {
				String id = itemId(best.getItem());
				mc.gameMode.handleInventoryMouseClick(menu.containerId, best.index, 0, ClickType.QUICK_MOVE, p);
				events.add("reflex", null, "equipped " + id);
				return; // one piece per pass keeps server and client in step
			}
		}
	}

	// ---- fluids --------------------------------------------------------------------

	private void escapeFluids(LocalPlayer p) {
		if (!p.isInLava() && !p.isUnderWater()) fluidEpisode = false;
		if (fluidCooldown > 0) return;
		String what = null;
		if (p.isInLava()) {
			control.reflexMove(new Input(false, true, false, false, true, false, false), 10, "escaping lava");
			what = "in lava: jumping back out";
		} else if (p.isUnderWater() && p.getAirSupply() < 120) {
			control.reflexMove(new Input(false, false, false, false, true, false, false), 10, "swimming up for air");
			what = "low air (" + p.getAirSupply() + "/300): swimming up";
		}
		if (what == null) return;
		fluidCooldown = 10;
		if (!fluidEpisode) events.add("reflex", null, what); // once per dive, not every half second
		fluidEpisode = true;
	}

	// ---- helpers -------------------------------------------------------------------

	private boolean allowed(String tool, ToolCategory category) {
		return policy.allows(tool, category);
	}

	private static List<ReflexMath.Held> hotbar(Inventory inv) {
		List<ReflexMath.Held> out = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			FoodProperties food = s.get(DataComponents.FOOD);
			out.add(new ReflexMath.Held(i, itemId(s), food == null ? 0 : food.nutrition()));
		}
		return out;
	}

	private static String itemId(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	private static String typeId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	private static String fmt(double d) {
		return String.valueOf(Math.round(d * 10) / 10.0);
	}
}
