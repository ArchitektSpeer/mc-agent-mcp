package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** attack_entity (left click) and interact_entity (right click) on an entity by id. */
public final class AttackTool implements McpTool {
	private final GameBridge bridge;
	private final boolean interact;

	private AttackTool(GameBridge bridge, boolean interact) {
		this.bridge = bridge;
		this.interact = interact;
	}

	public static AttackTool attack(GameBridge bridge) {
		return new AttackTool(bridge, false);
	}

	public static AttackTool interact(GameBridge bridge) {
		return new AttackTool(bridge, true);
	}

	@Override
	public String name() {
		return interact ? "interact_entity" : "attack_entity";
	}

	@Override
	public String description() {
		return interact
				? "Right-click an entity (id from get_entities): trade with villagers, feed/breed animals, "
						+ "shear sheep, mount boats/horses/minecarts. Must be within ~3 blocks."
				: "Hit an entity (id from get_entities) once with the held item. Must be within ~3 blocks. "
						+ "Damage depends on the attack cooldown ('attackStrength', 1.0 = full), so wait ~10-20 ticks "
						+ "between hits. Returns the target's health afterwards (client view, may lag a tick).";
	}

	@Override
	public JsonObject inputSchema() {
		Schema s = Schema.object().integer("entityId", "Target entity id.");
		if (interact) s.enumeration("hand", "Hand to use (default main).", "main", "off");
		return s.required("entityId").build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		int id = Args.getInt(args, "entityId", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
		if (!Args.has(args, "entityId")) throw new ToolException("missing required argument 'entityId'");
		InteractionHand hand = "off".equals(Args.getEnum(args, "hand", "main", "main", "off"))
				? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

		return bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			Minecraft mc = Minecraft.getInstance();
			Entity target = mc.level.getEntity(id);
			if (target == null || !target.isAlive()) throw new ToolException("no living entity with id " + id);
			if (target == p) throw new ToolException("cannot target yourself");
			if (!p.isWithinEntityInteractionRange(target, 0.0)) {
				throw new ToolException("entity is out of reach (distance "
						+ Json.round2(Math.sqrt(p.distanceToSqr(target))) + "); move closer");
			}
			AgentControl.lookAt(p, target.getBoundingBox().getCenter());

			JsonObject o = new JsonObject();
			if (interact) {
				InteractionResult r = mc.gameMode.interact(p, target, hand);
				if (r instanceof InteractionResult.Success) p.swing(hand);
				o.addProperty("success", r.consumesAction());
				o.addProperty("result", r.getClass().getSimpleName());
			} else {
				o.addProperty("attackStrength", Json.round2(p.getAttackStrengthScale(0.5F)));
				mc.gameMode.attack(p, target);
				p.swing(InteractionHand.MAIN_HAND);
				if (target instanceof LivingEntity le) {
					o.addProperty("targetHealth", le.getHealth());
				}
				o.addProperty("targetAlive", target.isAlive());
			}
			return o;
		});
	}
}
