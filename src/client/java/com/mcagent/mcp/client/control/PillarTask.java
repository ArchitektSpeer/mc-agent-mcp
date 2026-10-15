package com.mcagent.mcp.client.control;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.blocks.BlockScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Pillar up": place a block into the cell the player is standing in, the way players do it —
 * jump, and place the block underneath once the feet have cleared the cell.
 */
public final class PillarTask implements AgentControl.TickTask {
	private static final int TIMEOUT_TICKS = 14;

	private final AgentControl control;
	private final BlockPos pos;
	private final InteractionHand hand;
	private int ticks;

	public PillarTask(AgentControl control, BlockPos pos, InteractionHand hand) {
		this.control = control;
		this.pos = pos;
		this.hand = hand;
	}

	@Override
	public JsonObject tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (ticks++ == 0) {
			control.reflexMove(new Input(false, false, false, false, true, false, false), 3, "pillar jump");
			return null;
		}
		if (ticks > TIMEOUT_TICKS) return result(mc, false, "could not jump clear of the cell (low ceiling?)");
		// Feet must be above the cell before the block can go in.
		if (p.getY() < pos.getY() + 1.0) return null;
		BlockPos support = pos.below();
		AgentControl.lookAt(p, Vec3.atCenterOf(support).add(0, 0.5, 0));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
		mc.gameMode.useItemOn(p, hand, hit);
		p.swing(hand);
		boolean placed = !mc.level.getBlockState(pos).canBeReplaced();
		return result(mc, placed, placed ? null : "the server did not accept the block");
	}

	@Override
	public JsonObject cancelled(String reason) {
		return result(Minecraft.getInstance(), false, reason);
	}

	private JsonObject result(Minecraft mc, boolean placed, String reason) {
		JsonObject o = new JsonObject();
		o.addProperty("success", placed);
		o.addProperty("placed", placed);
		o.addProperty("pillared", true);
		if (mc.level != null) o.addProperty("blockAtPos", BlockScanner.idOf(mc.level.getBlockState(pos)));
		if (reason != null) o.addProperty("reason", reason);
		return o;
	}
}
