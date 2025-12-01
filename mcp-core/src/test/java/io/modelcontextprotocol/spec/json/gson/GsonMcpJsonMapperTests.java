package io.modelcontextprotocol.spec.json.gson;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class GsonMcpJsonMapperTests {

	// Sostituisce il record con una classe POJO immutabile
	public static final class Person {

		private final String name;

		private final int age;

		public Person(String name, int age) {
			this.name = name;
			this.age = age;
		}

		public String getName() {
			return name;
		}

		public int getAge() {
			return age;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;
			if (!(o instanceof Person))
				return false;
			Person other = (Person) o;
			return age == other.age && name.equals(other.name);
		}

		@Override
		public int hashCode() {
			return name.hashCode() * 31 + age;
		}

	}

	@Test
	public void roundTripSimplePojo() throws IOException {
		GsonMcpJsonMapper mapper = new GsonMcpJsonMapper();

		Person input = new Person("Alice", 30);
		String json = mapper.writeValueAsString(input);
		assertNotNull(json);
		assertTrue(json.contains("\"Alice\""));
		assertTrue(json.contains("\"age\""));

		Person decoded = mapper.readValue(json, Person.class);
		assertEquals(input, decoded);

		byte[] bytes = mapper.writeValueAsBytes(input);
		assertNotNull(bytes);
		Person decodedFromBytes = mapper.readValue(bytes, Person.class);
		assertEquals(input, decodedFromBytes);
	}

	@Test
	public void readWriteParameterizedTypeWithTypeRef() throws IOException {
		GsonMcpJsonMapper mapper = new GsonMcpJsonMapper();
		String json = "[\"a\", \"b\", \"c\"]";

		List<String> list = mapper.readValue(json, new TypeRef<List<String>>() {
		});
		assertEquals(Arrays.asList("a", "b", "c"), list); // sostituisce List.of

		String encoded = mapper.writeValueAsString(list);
		assertTrue(encoded.startsWith("["));
		assertTrue(encoded.contains("\"a\""));
	}

	@Test
	public void convertValueMapToPojoAndParameterized() {
		GsonMcpJsonMapper mapper = new GsonMcpJsonMapper();
		Map<String, Object> src = new HashMap<>();
		src.put("name", "Bob");
		src.put("age", 42);

		// Convert to simple POJO
		Person person = mapper.convertValue(src, Person.class);
		assertEquals(new Person("Bob", 42), person);

		// Convert to parameterized Map
		Map<String, Object> toMap = mapper.convertValue(person, new TypeRef<Map<String, Object>>() {
		});
		assertEquals("Bob", toMap.get("name"));
		assertEquals(42.0, ((Number) toMap.get("age")).doubleValue(), 0.0); // Gson may
																			// emit double
																			// for
																			// primitives
	}

	@Test
	void deserializeJsonRpcMessageRequestUsingCustomMapper() throws IOException {

		GsonMcpJsonMapper mapper = new GsonMcpJsonMapper();

		String json = "{\n" + "  \"jsonrpc\": \"2.0\",\n" + "  \"id\": 1,\n" + "  \"method\": \"ping\",\n"
				+ "  \"params\": { \"x\": 1, \"y\": \"z\" }\n" + "}";

		McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(mapper, json);
		assertTrue(msg instanceof McpSchema.JSONRPCRequest);

		McpSchema.JSONRPCRequest req = (McpSchema.JSONRPCRequest) msg;
		assertEquals("2.0", req.jsonrpc());
		assertEquals("ping", req.method());
		assertNotNull(req.id());
		assertEquals("1", req.id().toString());

		assertNotNull(req.params());
		assertTrue(req.params() instanceof Map); // sostituisce assertInstanceOf

		@SuppressWarnings("unchecked")
		Map<String, Object> params = (Map<String, Object>) req.params();

		assertEquals(1.0, ((Number) params.get("x")).doubleValue(), 0.0);
		assertEquals("z", params.get("y"));
	}

	@Test
	void integrateWithMcpSchemaStaticMapperForStringParsing() {

		GsonMcpJsonMapper gsonMapper = new GsonMcpJsonMapper();

		// Tool builder parsing of input/output schema strings
		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name("echo")
			.description("Echo tool")
			.inputSchema(gsonMapper,
					"{\n" + "  \"type\": \"object\",\n" + "  \"properties\": { \"x\": { \"type\": \"integer\" } },\n"
							+ "  \"required\": [\"x\"]\n" + "}")
			.outputSchema(gsonMapper,
					"{\n" + "  \"type\": \"object\",\n" + "  \"properties\": { \"y\": { \"type\": \"string\" } }\n"
							+ "}")
			.build();

		assertNotNull(tool.getInputSchema());
		assertNotNull(tool.getOutputSchema());
		assertTrue(tool.getOutputSchema().containsKey("properties"));

		// CallToolRequest builder parsing of JSON arguments string
		CallToolRequest call = McpSchema.CallToolRequest.builder()
			.name("echo")
			.arguments(gsonMapper, "{\"x\": 123}")
			.build();

		assertEquals("echo", call.getName());
		assertNotNull(call.getArguments());
		assertTrue(call.getArguments().get("x") instanceof Number);
		assertEquals(123.0, ((Number) call.getArguments().get("x")).doubleValue(), 0.0);

	}

}
