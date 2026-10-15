package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * place_block: place the held block into an empty cell by clicking a solid neighbor, and
 * interact_block: right-click a block itself (doors, buttons, chests, crafting tables, beds).
 * Both are vanilla {@code useItemOn}; they differ only in which block gets clicked.
 */
public final class PlaceBlockTool implements McpTool {
	/** Up to ~0.5 s for a container screen to open after interact_block. */
	private static final int SCREEN_WAIT_POLLS = 10;

	private final GameBridge bridge;
	private final boolean interact;

	private PlaceBlockTool(GameBridge bridge, boolean interact) {
		this.bridge = bridge;
		this.interact = interact;
	}

	public static PlaceBlockTool place(GameBridge bridge) {
		return new PlaceBlockTool(bridge, false);
	}

	public static PlaceBlockTool interact(GameBridge bridge) {
		return new PlaceBlockTool(bridge, true);
	}

	@Override
	public String name() {
		return interact ? "interact_block" : "place_block";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.INTERACTION;
	}

	@Override
	public String description() {
		return interact
				? "Right-click the block at 'pos' with the held item: open doors/chests/crafting tables/furnaces, "
						+ "press buttons, sleep in beds, till with a hoe, etc. 'face' defaults to the side facing the player."
				: "Place the held block (or use the held item like a bucket or flint and steel) into the empty cell "
						+ "'pos'. A solid neighbor is clicked automatically (the one below first). Fails if the cell is "
						+ "occupied, has no solid neighbor, or is out of reach. Returns the block now at 'pos'.";
	}

	@Override
	public JsonObject inputSchema() {
		Schema s = Schema.object()
				.pos("pos", interact ? "Block to right-click." : "Empty cell to fill.")
				.enumeration("hand", "Hand to use (default main).", "main", "off");
		if (interact) {
			s.enumeration("face", "Clicked face (default: toward the player).",
					"down", "up", "north", "south", "west", "east");
		}
		return s.required("pos").build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		int[] at = Args.requirePos(args, "pos");
		InteractionHand hand = "off".equals(Args.getEnum(args, "hand", "main", "main", "off"))
				? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		String faceName = interact
				? Args.getEnum(args, "face", null, "down", "up", "north", "south", "west", "east") : null;

		JsonObject out = bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			BlockPos pos = new BlockPos(at[0], at[1], at[2]);

			BlockHitResult hit;
			if (interact) {
				if (level.getBlockState(pos).isAir()) throw new ToolException("there is no block at " + pos.toShortString());
				Direction face = faceName != null ? Direction.byName(faceName) : AgentControl.faceToward(p, pos);
				hit = hitOn(pos, face);
			} else {
				if (!level.getBlockState(pos).canBeReplaced()) {
					throw new ToolException("cell is occupied by " + BlockScanner.idOf(level.getBlockState(pos)));
				}
				hit = null;
				// Prefer the floor, as players usually build.
				for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH,
						Direction.WEST, Direction.EAST, Direction.UP}) {
					BlockPos support = pos.relative(d);
					BlockState s = level.getBlockState(support);
					if (!s.canBeReplaced() && p.isWithinBlockInteractionRange(support, 0.0)) {
						hit = hitOn(support, d.getOpposite());
						break;
					}
				}
				if (hit == null) {
					throw new ToolException("no solid neighbor within reach to place against");
				}
			}
			if (!p.isWithinBlockInteractionRange(hit.getBlockPos(), 0.0)) {
				throw new ToolException("block is out of reach; move closer (within ~4.5 blocks of the eyes)");
			}

			AgentControl.lookAt(p, hit.getLocation());
			InteractionResult result = mc.gameMode.useItemOn(p, hand, hit);
			if (result instanceof InteractionResult.Success) {
				p.swing(hand);
			}
			JsonObject o = new JsonObject();
			o.addProperty("success", result.consumesAction());
			o.addProperty("result", result.getClass().getSimpleName());
			o.add("clicked", Json.pos(hit.getBlockPos()));
			o.addProperty("face", hit.getDirection().getName());
			o.addProperty("blockAtPos", BlockScanner.idOf(level.getBlockState(pos)));
			if (mc.screen != null) {
				o.addProperty("screenOpened", mc.screen.getClass().getSimpleName());
			}
			return o;
		});
		if (interact && out.get("success").getAsBoolean() && !out.has("screenOpened")) {
			// Containers open when the server answers, a tick or two later: wait briefly so the
			// agent can call get_container right away.
			for (int i = 0; i < SCREEN_WAIT_POLLS; i++) {
				String screen = bridge.callOnGameThread(() -> {
					var s = Minecraft.getInstance().screen;
					return s == null ? null : s.getClass().getSimpleName();
				});
				if (screen != null) {
					out.addProperty("screenOpened", screen);
					break;
				}
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		return out;
	}

	/** Hit at the center of {@code face} of the block at {@code pos}. */
	private static BlockHitResult hitOn(BlockPos pos, Direction face) {
		Vec3 loc = Vec3.atCenterOf(pos).add(face.getUnitVec3().scale(0.5));
		return new BlockHitResult(loc, face, pos, false);
	}
}
