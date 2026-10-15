package com.mcagent.mcp.client.mixin;

import com.mcagent.mcp.client.blocks.BlockDiffTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Exact change detection for {@link BlockDiffTracker}: mark the tracked volume dirty
 * whenever the client world applies a block state. Runs on the game thread.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

	@Inject(method = "setBlock", at = @At("TAIL"))
	private void mcagent$onSetBlock(BlockPos pos, BlockState state, int flags, int recursion,
									CallbackInfoReturnable<Boolean> cir) {
		if (Boolean.TRUE.equals(cir.getReturnValue())) {
			BlockDiffTracker.onBlockChanged(pos);
		}
	}
}
