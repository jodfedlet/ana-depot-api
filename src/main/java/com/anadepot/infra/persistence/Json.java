package com.anadepot.infra.persistence;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Small JSON helper for jsonb payloads (audit, outbox, snapshots). */
public final class Json {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private Json() {
	}

	public static String write(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	public static Map<String, Object> readMap(String json) {
		return json == null || json.isBlank() ? Map.of() : MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
	}
}
