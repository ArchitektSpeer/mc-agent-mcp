package com.mcagent.mcp.client.mixin;

import com.mcagent.mcp.client.control.AgentControl;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the agent is mining, skip vanilla's per-tick {@code continueAttack}: with the attack
 * key up it calls {@code stopDestroyBlock()} every tick, which would reset the agent's
 * break progress before it could ever finish.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void mcagent$yieldToAgentBreaking(boolean leftClick, CallbackInfo ci) {
		if (AgentControl.isBreaking()) {
			ci.cancel();
		}
	}
}
