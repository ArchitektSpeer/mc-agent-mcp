import com.mcagent.mcp.server.McpHttpServer;
import com.mcagent.mcp.server.McpProtocol;
import com.mcagent.mcp.server.NotificationBus;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.PingTool;
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
		ToolRegistry reg = new ToolRegistry();
		reg.register(new PingTool());
		reg.register(new McpTool() {
			@Override public String name() { return "boom"; }
			@Override public String description() { return "always fails"; }
			@Override public JsonObject inputSchema() { return new JsonObject(); }
			@Override public JsonObject call(JsonObject args) { throw new ToolException("intentional failure"); }
		});
		McpProtocol protocol = new McpProtocol(reg, "mc-agent-mcp", "0.1.0-test");
		NotificationBus bus = new NotificationBus();
		McpHttpServer server = new McpHttpServer(protocol, bus, "127.0.0.1", 27994, TOKEN);
		server.start();

		// initialize -> session id header
		Resp init = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
				+ "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"0\"}}}", null);
		check("initialize -> 200", init.code == 200);
		check("initialize returns serverInfo", init.body.contains("mc-agent-mcp"));
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
		HttpURLConnection c = (HttpURLConnection) new URL(BASE).openConnection();
		c.setRequestMethod(method);
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
