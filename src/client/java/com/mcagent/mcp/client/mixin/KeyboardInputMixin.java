package com.mcagent.mcp.client.mixin;

import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.client.control.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Agent movement: after vanilla has read the keyboard, replace the key state with the
 * agent's held input (if any) and recompute the impulses the same way vanilla does.
 * Works while the window is unfocused, unlike pressing {@code KeyMapping}s.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends net.minecraft.client.player.Input {

	@Inject(method = "tick", at = @At("TAIL"))
	private void mcagent$overrideInput(boolean isSneaking, float sneakingSpeedMultiplier, CallbackInfo ci) {
		Input held = AgentControl.inputOverride();
		if (held == null) return;
		this.up = held.forward();
		this.down = held.backward();
		this.left = held.left();
		this.right = held.right();
		this.jumping = held.jump();
		this.shiftKeyDown = held.shift();
		this.forwardImpulse = impulse(held.forward(), held.backward());
		this.leftImpulse = impulse(held.left(), held.right());
		if (isSneaking) {
			this.leftImpulse *= sneakingSpeedMultiplier;
			this.forwardImpulse *= sneakingSpeedMultiplier;
		}
		// 1.21.1 has no sprint flag in Input: aiStep reads the sprint key, which the agent never presses.
		LocalPlayer player = Minecraft.getInstance().player;
		if (held.sprint() && held.forward() && player != null) {
			player.setSprinting(true);
		}
	}

	private static float impulse(boolean positive, boolean negative) {
		return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
	}
}
