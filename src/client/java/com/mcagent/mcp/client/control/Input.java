package com.mcagent.mcp.client.control;

/**
 * Held movement keys for one tick. Mirrors the {@code net.minecraft.world.entity.player.Input}
 * record that 1.21.2 introduced, which 1.21.1 lacks; the KeyboardInput mixin translates it
 * into vanilla's per-key fields.
 */
public record Input(boolean forward, boolean backward, boolean left, boolean right,
					boolean jump, boolean shift, boolean sprint) {
}
