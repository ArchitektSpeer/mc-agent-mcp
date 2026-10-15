import com.mcagent.mcp.server.McpHttpServer;
import com.mcagent.mcp.server.McpProtocol;
import com.mcagent.mcp.server.NotificationBus;
import com.mcagent.mcp.tools.BatchTool;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.PingTool;
import com.mcagent.mcp.tools.ToolCategory;
import com.mcagent.mcp.tools.ToolPolicy;
import com.mcagent.mcp.tools.ToolRegistry;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** MCP transport/JSON-RPC regression checks (no Minecraft needed). */
public class ProtocolTest {
	static final String BASE = "http://127.0.0.1:27994/mcp";
	static final String TOKEN = "testtoken";
	static int failures = 0;

	public static void main(String[] args) throws Exception {
		// "Origin" is a restricted header for HttpURLConnection unless this is set first.
		System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
		ToolRegistry reg = new ToolRegistry();
		reg.register(new PingTool());
		reg.register(new McpTool() {
			@Override public String name() { return "boom"; }
			@Override public ToolCategory category() { return ToolCategory.INTERACTION; }
			@Override public String description() { return "always fails"; }
			@Override public JsonObject inputSchema() { return new JsonObject(); }
			@Override public JsonObject call(JsonObject args) { throw new ToolException("intentional failure"); }
		});
		reg.register(simple("secret_chat", ToolCategory.CHAT));
		reg.register(simple("walk", ToolCategory.MOVEMENT));
		// chat disabled; movement limited to one call per 2 s
		ToolPolicy policy = new ToolPolicy().setEnabled(ToolCategory.CHAT, false).limit(ToolCategory.MOVEMENT, 0.5, 1);
		McpProtocol protocol = new McpProtocol(reg, "mc-agent-mcp", "0.1.0-test", "test playbook", policy);
		reg.register(new BatchTool(protocol::invokeTool));
		NotificationBus bus = new NotificationBus();
		McpHttpServer server = new McpHttpServer(protocol, bus, "127.0.0.1", 27994, TOKEN);
		server.start();

		// initialize -> session id header
		Resp init = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
				+ "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"0\"}}}", null);
		check("initialize -> 200", init.code == 200);
		check("initialize returns serverInfo", init.body.contains("mc-agent-mcp"));
		check("initialize returns instructions", init.body.contains("\"instructions\":\"test playbook\""));
		String session = init.session;
		check("initialize returns Mcp-Session-Id", session != null && !session.isBlank());
		check("initialize negotiates protocolVersion", init.body.contains("2025-06-18"));

		// notification -> 202, empty body
		Resp note = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session);
		check("notification -> 202", note.code == 202);
		check("notification has no body", note.body.isEmpty());

		// tools/list
		Resp list = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session);
		check("tools/list lists both tools", list.body.contains("\"ping\"") && list.body.contains("\"boom\""));
		check("tools/list includes inputSchema", list.body.contains("inputSchema"));
		check("tools/list hides disabled tools", !list.body.contains("secret_chat"));
		check("tools/list carries annotations", list.body.contains("\"readOnlyHint\""));

		// permissions + rate limits
		Resp denied = post("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"secret_chat\",\"arguments\":{}}}", session);
		check("disabled tool -> isError with config hint", denied.body.contains("\"isError\":true")
				&& denied.body.contains("permissions.chat"));
		Resp walk1 = post("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"walk\",\"arguments\":{}}}", session);
		Resp walk2 = post("{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"walk\",\"arguments\":{}}}", session);
		check("first call within limit", !walk1.body.contains("isError"));
		check("second call rate limited with retryAfterMs", walk2.body.contains("\"isError\":true")
				&& walk2.body.contains("retryAfterMs"));

		// batch: ping ok, then a disabled tool stops the run; nesting is refused
		Resp toolBatch = post("{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"tools/call\",\"params\":{\"name\":\"batch\","
				+ "\"arguments\":{\"steps\":[{\"tool\":\"ping\"},{\"tool\":\"secret_chat\"},{\"tool\":\"ping\"}]}}}", session);
		check("batch runs steps in order", toolBatch.body.contains("\"pong\":true"));
		check("batch stops at a denied step", toolBatch.body.contains("\"completedSteps\":2")
				&& toolBatch.body.contains("permissions.chat"));
		Resp toolBatch2 = post("{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"tools/call\",\"params\":{\"name\":\"batch\","
				+ "\"arguments\":{\"continueOnError\":true,\"steps\":[{\"tool\":\"batch\"},{\"tool\":\"ping\"}]}}}", session);
		check("batch refuses nesting, continueOnError keeps going", toolBatch2.body.contains("cannot be nested")
				&& toolBatch2.body.contains("\"completedSteps\":2"));
		Resp toolBatch3 = post("{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"tools/call\",\"params\":{\"name\":\"batch\","
				+ "\"arguments\":{\"steps\":[]}}}", session);
		check("empty batch -> isError", toolBatch3.body.contains("\"isError\":true"));

		// oversized body
		Resp huge = post("{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"ping\",\"pad\":\""
				+ "x".repeat(McpHttpServer.MAX_BODY_BYTES) + "\"}", session);
		check("body over 1 MiB -> 413", huge.code == 413);

		// tools/call ok
		Resp call = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"ping\",\"arguments\":{}}}", session);
		check("tools/call ping -> pong", call.body.contains("pong") && call.body.contains("structuredContent"));

		// tools/call failure -> isError, not a protocol error
		Resp boom = post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"boom\",\"arguments\":{}}}", session);
		check("failing tool -> isError=true", boom.body.contains("\"isError\":true"));
		check("failing tool keeps the message", boom.body.contains("intentional failure"));

		// unknown tool
		Resp unknown = post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":"
				+ "{\"name\":\"nope\",\"arguments\":{}}}", session);
		check("unknown tool -> JSON-RPC error", unknown.body.contains("\"error\""));

		// unknown method
		Resp badMethod = post("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"does/not/exist\"}", session);
		check("unknown method -> JSON-RPC error", badMethod.body.contains("\"error\""));

		// malformed JSON
		Resp bad = post("{not json", session);
		check("malformed JSON -> parse error", bad.body.contains("-32700"));

		// auth
		Resp noAuth = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}", session, null, true);
		check("missing token -> 401", noAuth.code == 401);

		// Origin validation (DNS-rebinding protection)
		Resp evil = send("POST", "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\"}", session, TOKEN,
				"http://evil.example.com");
		check("foreign Origin -> 403", evil.code == 403);
		Resp nullOrigin = send("POST", "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\"}", session, TOKEN, "null");
		check("opaque 'null' Origin -> 403", nullOrigin.code == 403);
		Resp localOrigin = send("POST", "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"ping\"}", session, TOKEN,
				"http://localhost:6274");
		check("loopback Origin -> 200", localOrigin.code == 200);

		// batch: notifications only -> 202
		Resp batch = post("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
				+ "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]", session);
		check("notification batch -> 202", batch.code == 202);

		// batch with a request -> array response
		Resp batch2 = post("[{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"},"
				+ "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}]", session);
		check("request batch -> JSON array", batch2.body.trim().startsWith("["));

		// wrong method on the endpoint
		Resp put = request("PUT", "{}", session);
		check("PUT -> 405", put.code == 405);

		// session header echoed on later calls
		check("later calls echo Mcp-Session-Id", session.equals(list.session));

		// DELETE closes the session
		Resp del = request("DELETE", null, session);
		check("DELETE -> 200", del.code == 200);

		server.stop();
		System.out.println(failures == 0 ? "PROTOCOL: PASS" : "PROTOCOL: FAIL (" + failures + ")");
		if (failures != 0) System.exit(1);
	}

	static McpTool simple(String name, ToolCategory cat) {
		return new McpTool() {
			@Override public String name() { return name; }
			@Override public ToolCategory category() { return cat; }
			@Override public String description() { return name; }
			@Override public JsonObject inputSchema() { return new JsonObject(); }
			@Override public JsonObject call(JsonObject args) { return new JsonObject(); }
		};
	}

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}

	static final class Resp {
		int code;
		String body = "";
		String session;
	}

	static Resp post(String body, String session) throws Exception {
		return post(body, session, TOKEN, false);
	}

	static Resp post(String body, String session, String token, boolean useSession) throws Exception {
		return send("POST", body, session, token);
	}

	static Resp request(String method, String body, String session) throws Exception {
		return send(method, body, session, TOKEN);
	}

	static Resp send(String method, String body, String session, String token) throws Exception {
		return send(method, body, session, token, null);
	}

	static Resp send(String method, String body, String session, String token, String origin) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(BASE).openConnection();
		c.setRequestMethod(method);
		if (origin != null) c.setRequestProperty("Origin", origin);
		if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
		c.setRequestProperty("Content-Type", "application/json");
		if (session != null) c.setRequestProperty("Mcp-Session-Id", session);
		if (body != null) {
			c.setDoOutput(true);
			try (OutputStream os = c.getOutputStream()) {
				os.write(body.getBytes(StandardCharsets.UTF_8));
			}
		}
		Resp r = new Resp();
		r.code = c.getResponseCode();
		r.session = c.getHeaderField("Mcp-Session-Id");
		try (InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream()) {
			if (in != null) r.body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// no body
		}
		return r;
	}
}
