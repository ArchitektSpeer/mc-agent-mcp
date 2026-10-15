package com.mcagent.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Small builder for tool input schemas, so each tool's schema reads as one expression. */
public final class Schema {
	private final JsonObject props = new JsonObject();
	private final JsonArray required = new JsonArray();

	public static Schema object() {
		return new Schema();
	}

	public Schema integer(String name, String description) {
		return prop(name, "integer", description);
	}

	public Schema number(String name, String description) {
		return prop(name, "number", description);
	}

	public Schema bool(String name, String description) {
		return prop(name, "boolean", description);
	}

	public Schema string(String name, String description) {
		return prop(name, "string", description);
	}

	public Schema enumeration(String name, String description, String... values) {
		JsonObject p = new JsonObject();
		p.addProperty("type", "string");
		JsonArray e = new JsonArray();
		for (String v : values) e.add(v);
		p.add("enum", e);
		p.addProperty("description", description);
		props.add(name, p);
		return this;
	}

	/** Integer {@code [x,y,z]} triple. */
	public Schema pos(String name, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", "array");
		JsonObject items = new JsonObject();
		items.addProperty("type", "integer");
		p.add("items", items);
		p.addProperty("minItems", 3);
		p.addProperty("maxItems", 3);
		p.addProperty("description", description);
		props.add(name, p);
		return this;
	}

	public Schema required(String... names) {
		for (String n : names) required.add(n);
		return this;
	}

	public JsonObject build() {
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		s.add("properties", props);
		if (!required.isEmpty()) s.add("required", required);
		return s;
	}

	private Schema prop(String name, String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		props.add(name, p);
		return this;
	}
}
