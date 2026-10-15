package com.mcagent.mcp.client.mixin;

import com.mcagent.mcp.client.control.AgentControl;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Agent movement: after vanilla has read the keyboard, replace the key state with the
 * agent's held input (if any) and recompute the move vector the same way vanilla does.
 * Works while the window is unfocused, unlike pressing {@code KeyMapping}s.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

	@Inject(method = "tick", at = @At("TAIL"))
	private void mcagent$overrideInput(CallbackInfo ci) {
		Input held = AgentControl.inputOverride();
		if (held == null) return;
		this.keyPresses = held;
		float forward = impulse(held.forward(), held.backward());
		float strafe = impulse(held.left(), held.right());
		this.moveVector = new Vec2(strafe, forward).normalized();
	}

	private static float impulse(boolean positive, boolean negative) {
		return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
	}
}
