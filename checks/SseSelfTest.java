import com.google.gson.JsonObject;
import com.mcagent.mcp.server.McpHttpServer;
import com.mcagent.mcp.server.McpProtocol;
import com.mcagent.mcp.server.NotificationBus;
import com.mcagent.mcp.tools.PingTool;
import com.mcagent.mcp.tools.ToolRegistry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Self-contained end-to-end check: SSE push + concurrent POST on the same server. */
public class SseSelfTest {
	static final String HOST = "127.0.0.1";
	static final int PORT = 27993;
	static final String TOKEN = "testtoken";
	static final List<String> frames = new ArrayList<>();
	static final CountDownLatch got3 = new CountDownLatch(3);

	public static void main(String[] args) throws Exception {
		ToolRegistry reg = new ToolRegistry();
		reg.register(new PingTool());
		McpProtocol protocol = new McpProtocol(reg, "mc-agent-mcp", "0.1.0-test");
		NotificationBus bus = new NotificationBus();
		McpHttpServer server = new McpHttpServer(protocol, bus, HOST, PORT, TOKEN);
		server.start();

		// 1) Open a raw SSE connection exactly like an MCP client would.
		Socket sock = new Socket(HOST, PORT);
		OutputStream out = sock.getOutputStream();
		out.write(("GET /mcp HTTP/1.1\r\nHost: " + HOST + "\r\n"
				+ "Authorization: Bearer " + TOKEN + "\r\n"
				+ "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		out.flush();

		Thread reader = new Thread(() -> {
			try (BufferedReader br = new BufferedReader(
					new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = br.readLine()) != null) {
					if (line.startsWith("data: ")) {
						String payload = line.substring(6);
						synchronized (frames) { frames.add(payload); }
						// Count only real notifications, not keep-alive comments.
						if (payload.contains("block_diff")) got3.countDown();
					}
					if (line.startsWith("HTTP/")) {
						System.out.println("sse handshake: " + line);
					}
				}
			} catch (Exception e) {
				System.out.println("reader ended: " + e);
			}
		}, "sse-reader");
		reader.setDaemon(true);
		reader.start();

		// 2) Wait until the server registered the subscriber.
		long deadline = System.currentTimeMillis() + 5000;
		while (bus.subscriberCount() == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		System.out.println("subscribers registered: " + bus.subscriberCount());

		// 3) Push three notifications from the "game thread" side.
		for (int i = 1; i <= 3; i++) {
			JsonObject params = new JsonObject();
			params.addProperty("changed", i);
			JsonObject n = new JsonObject();
			n.addProperty("jsonrpc", "2.0");
			n.addProperty("method", "notifications/mcagent/block_diff");
			n.add("params", params);
			System.out.println("broadcast #" + i + " reached " + bus.broadcast(n) + " subscriber(s)");
			Thread.sleep(100);
		}

		// 4) Concurrent POST while the SSE stream is open (thread-starvation check).
		String post = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"ping\",\"arguments\":{}}}");
		System.out.println("concurrent POST -> " + (post.length() > 80 ? post.substring(0, 80) + "..." : post));

		// 5) Collect the pushed frames.
		boolean ok = got3.await(5, TimeUnit.SECONDS);
		System.out.println("received 3 notifications: " + ok);
		synchronized (frames) {
			for (String f : frames) System.out.println("  frame: " + f);
		}

		// 6) DELETE closes the session and its streams.
		int del = delete();
		System.out.println("DELETE /mcp -> HTTP " + del);
		Thread.sleep(300);
		System.out.println("subscribers after DELETE: " + bus.subscriberCount());

		server.stop();
		System.out.println(ok && frames.size() == 3 ? "SSE-PUSH: PASS" : "SSE-PUSH: FAIL");
	}

	static String post(String body) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL("http://" + HOST + ":" + PORT + "/mcp").openConnection();
		c.setRequestMethod("POST");
		c.setRequestProperty("Authorization", "Bearer " + TOKEN);
		c.setRequestProperty("Content-Type", "application/json");
		c.setDoOutput(true);
		c.setConnectTimeout(3000);
		c.setReadTimeout(3000);
		try (OutputStream os = c.getOutputStream()) {
			os.write(body.getBytes(StandardCharsets.UTF_8));
		}
		try (InputStream in = c.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	static int delete() throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL("http://" + HOST + ":" + PORT + "/mcp").openConnection();
		c.setRequestMethod("DELETE");
		c.setRequestProperty("Authorization", "Bearer " + TOKEN);
		c.setConnectTimeout(3000);
		c.setReadTimeout(3000);
		return c.getResponseCode();
	}
}
