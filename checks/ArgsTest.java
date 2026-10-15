import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagent.mcp.client.ChatLog;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;

/**
 * Argument validation and the chat log: the pure logic behind the actuation tools.
 * Every bad argument must become a ToolException (isError for the agent), never a
 * Gson exception or a silently truncated value.
 */
public class ArgsTest {
	static int failures = 0;

	public static void main(String[] args) {
		JsonObject a = JsonParser.parseString("{\"i\":5,\"f\":1.5,\"big\":1e12,\"s\":\"x\",\"b\":true,"
				+ "\"pos\":[1,-2,3],\"badPos\":[1,2],\"fpos\":[1,2.5,3],\"n\":null,\"e\":\"MAIN\"}").getAsJsonObject();

		check("int in range", Args.getInt(a, "i", 0, 0, 10) == 5);
		check("int default when absent", Args.getInt(a, "zz", 7, 0, 10) == 7);
		check("null counts as absent", Args.getInt(a, "n", 7, 0, 10) == 7);
		throwsTool("int out of range", () -> Args.getInt(a, "i", 0, 0, 4));
		throwsTool("fraction is not an int", () -> Args.getInt(a, "f", 0, 0, 10));
		throwsTool("huge value is not an int", () -> Args.getInt(a, "big", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));
		throwsTool("string is not an int", () -> Args.getInt(a, "s", 0, 0, 10));
		check("double", Args.getDouble(a, "f", 0) == 1.5);
		check("bool", Args.getBool(a, "b", false));
		throwsTool("string is not a bool", () -> Args.getBool(a, "s", false));
		throwsTool("number is not a string", () -> Args.getString(a, "i", null));
		throwsTool("missing required string", () -> Args.requireString(a, "zz"));
		check("enum is case-insensitive", "main".equals(Args.getEnum(a, "e", null, "main", "off")));
		throwsTool("enum rejects unknown", () -> Args.getEnum(a, "s", null, "main", "off"));

		int[] p = Args.getPos(a, "pos");
		check("pos parsed", p[0] == 1 && p[1] == -2 && p[2] == 3);
		check("absent pos is null", Args.getPos(a, "zz") == null);
		throwsTool("pos needs 3 elements", () -> Args.getPos(a, "badPos"));
		throwsTool("pos needs integers", () -> Args.getPos(a, "fpos"));
		throwsTool("missing required pos", () -> Args.requirePos(a, "zz"));

		ChatLog log = new ChatLog();
		log.add("chat", "Steve", "hi");
		log.add("game", null, "Steve joined");
		JsonObject all = log.since(0, 50);
		check("chat log returns all", all.getAsJsonArray("messages").size() == 2);
		check("lastSeq advances", all.get("lastSeq").getAsLong() == 2);
		check("since filters older", log.since(1, 50).getAsJsonArray("messages").size() == 1);
		check("nothing new", log.since(2, 50).getAsJsonArray("messages").isEmpty());
		for (int i = 0; i < ChatLog.CAPACITY + 10; i++) log.add("chat", "x", "m" + i);
		check("log is bounded", log.since(0, ChatLog.CAPACITY).getAsJsonArray("messages").size() == ChatLog.CAPACITY);
		JsonObject limited = log.since(0, 3);
		check("max keeps the newest", limited.getAsJsonArray("messages").get(2).getAsJsonObject()
				.get("text").getAsString().equals("m" + (ChatLog.CAPACITY + 9)));
		check("omitted count reported", limited.get("omitted").getAsInt() == ChatLog.CAPACITY - 3);

		System.out.println(failures == 0 ? "ARGS: PASS" : "ARGS: FAIL (" + failures + ")");
		if (failures != 0) System.exit(1);
	}

	static void throwsTool(String what, Runnable r) {
		try {
			r.run();
			check(what, false);
		} catch (McpTool.ToolException e) {
			check(what + " (" + e.getMessage() + ")", true);
		} catch (RuntimeException e) {
			check(what + " threw " + e.getClass().getSimpleName() + " instead of ToolException", false);
		}
	}

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}
}
