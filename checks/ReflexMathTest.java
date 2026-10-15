import com.mcagent.mcp.client.reflex.ReflexMath;
import com.mcagent.mcp.client.reflex.ReflexMath.Held;

import java.util.List;

/** Weapon, food and armor choices made by the survival reflexes. */
public class ReflexMathTest {
	static int failures = 0;

	public static void main(String[] args) {
		check("diamond sword beats iron sword",
				ReflexMath.weaponDps("minecraft:diamond_sword") > ReflexMath.weaponDps("minecraft:iron_sword"));
		check("stone sword out-DPSes stone axe (axes are slow)",
				ReflexMath.weaponDps("minecraft:stone_sword") > ReflexMath.weaponDps("minecraft:stone_axe"));
		check("pickaxe is not a weapon", ReflexMath.weaponDps("minecraft:iron_pickaxe") == 0);
		check("unknown material is not a weapon", ReflexMath.weaponDps("minecraft:plastic_sword") == 0);
		check("best weapon picks the sword slot", ReflexMath.bestWeapon(List.of(
				new Held(0, "minecraft:iron_pickaxe", 0), new Held(3, "minecraft:stone_sword", 0),
				new Held(5, "minecraft:wooden_sword", 0))) == 3);
		check("no weapon -> -1", ReflexMath.bestWeapon(List.of(new Held(0, "minecraft:dirt", 0))) == -1);

		check("eat when below threshold", ReflexMath.wantsToEat(13, 20, 14));
		check("don't eat when fed", !ReflexMath.wantsToEat(17, 20, 14));
		check("eat to regenerate when hurt", ReflexMath.wantsToEat(16, 6, 14));
		check("full and hurt: nothing to gain", !ReflexMath.wantsToEat(20, 6, 14));

		List<Held> food = List.of(
				new Held(0, "minecraft:rotten_flesh", 4), new Held(1, "minecraft:bread", 5),
				new Held(2, "minecraft:cooked_beef", 8), new Held(3, "minecraft:golden_apple", 4),
				new Held(4, "minecraft:dirt", 0));
		check("big gap -> steak", ReflexMath.pickFood(food, 10, 20) == 2);
		check("small gap -> bread, not wasting steak", ReflexMath.pickFood(food, 15, 20) == 1);
		check("rotten flesh never picked", ReflexMath.pickFood(List.of(new Held(0, "minecraft:rotten_flesh", 4)), 5, 20) == -1);
		check("golden apple spared when healthy",
				ReflexMath.pickFood(List.of(new Held(3, "minecraft:golden_apple", 4)), 10, 15) == -1);
		check("golden apple used in an emergency",
				ReflexMath.pickFood(List.of(new Held(3, "minecraft:golden_apple", 4)), 10, 4) == 3);

		check("diamond armor beats iron", ReflexMath.armorTier("minecraft:diamond_chestplate")
				> ReflexMath.armorTier("minecraft:iron_chestplate"));
		check("leather is lowest known tier", ReflexMath.armorTier("minecraft:leather_boots") == 1);
		check("unknown armor tier 0", ReflexMath.armorTier("minecraft:elytra") == 0);

		System.out.println(failures == 0 ? "REFLEX-MATH: PASS" : "REFLEX-MATH: FAIL (" + failures + ")");
		if (failures != 0) System.exit(1);
	}

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}
}
