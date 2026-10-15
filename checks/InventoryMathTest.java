import com.mcagent.mcp.client.inventory.InventoryMath;
import com.mcagent.mcp.client.inventory.InventoryMath.Stack;

import java.util.List;

/** Transfer planning and grid-fit rules behind transfer_items / craft. */
public class InventoryMathTest {
	static int failures = 0;

	public static void main(String[] args) {
		List<Stack> stacks = List.of(
				new Stack(0, "minecraft:cobblestone", 12),
				new Stack(1, "minecraft:dirt", 64),
				new Stack(2, "minecraft:cobblestone", 64),
				new Stack(5, "minecraft:cobblestone", 30));

		check("filter + largest first", InventoryMath.pickStacks(stacks, "minecraft:cobblestone", 70)
				.equals(List.of(2, 5)));
		check("one stack covers small request", InventoryMath.pickStacks(stacks, "minecraft:cobblestone", 10)
				.equals(List.of(2)));
		check("wanted<=0 takes all matching", InventoryMath.pickStacks(stacks, "minecraft:cobblestone", 0)
				.equals(List.of(2, 5, 0)));
		check("more than available takes all", InventoryMath.pickStacks(stacks, "minecraft:cobblestone", 9999)
				.size() == 3);
		check("null item = any item", InventoryMath.pickStacks(stacks, null, 0).size() == 4);
		check("equal counts ordered by slot", InventoryMath.pickStacks(
				List.of(new Stack(9, "a", 5), new Stack(3, "a", 5)), "a", 0).equals(List.of(3, 9)));
		check("no match -> empty", InventoryMath.pickStacks(stacks, "minecraft:diamond", 1).isEmpty());
		check("empty stacks ignored", InventoryMath.pickStacks(List.of(new Stack(1, "a", 0)), "a", 0).isEmpty());

		check("planks (1x1) fit 2x2", InventoryMath.fitsGrid(true, 1, 1, 1, 2));
		check("crafting table (2x2) fits 2x2", InventoryMath.fitsGrid(true, 2, 2, 4, 2));
		check("pickaxe (3x3) needs a table", !InventoryMath.fitsGrid(true, 3, 3, 5, 2));
		check("pickaxe fits 3x3", InventoryMath.fitsGrid(true, 3, 3, 5, 3));
		check("stick (1x2) fits 2x2", InventoryMath.fitsGrid(true, 1, 2, 2, 2));
		check("shapeless 4 fits 2x2", InventoryMath.fitsGrid(false, 0, 0, 4, 2));
		check("shapeless 5 needs a table", !InventoryMath.fitsGrid(false, 0, 0, 5, 2));

		check("id gets namespace", "minecraft:oak_log".equals(InventoryMath.itemId("oak_log")));
		check("id keeps namespace", "mod:thing".equals(InventoryMath.itemId("mod:thing")));
		check("id trimmed + lower-cased", "minecraft:stick".equals(InventoryMath.itemId(" Stick ")));
		check("null id stays null", InventoryMath.itemId(null) == null);

		System.out.println(failures == 0 ? "INVENTORY-MATH: PASS" : "INVENTORY-MATH: FAIL (" + failures + ")");
		if (failures != 0) System.exit(1);
	}

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}
}
