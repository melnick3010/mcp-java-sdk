/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;

/**
 * Tests for {@link DefaultJsonSchemaValidator}.
 *
 * @author Christian Tzolov
 */
class DefaultJsonSchemaValidatorTests {

	private DefaultJsonSchemaValidator validator;

	private ObjectMapper objectMapper;

	@Mock
	private ObjectMapper mockObjectMapper;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		validator = new DefaultJsonSchemaValidator();
		objectMapper = new ObjectMapper();
	}

	/**
	 * Utility method to convert JSON string to Map<String, Object>
	 */
	private Map<String, Object> toMap(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to parse JSON: " + json, e);
		}
	}

	private List<Map<String, Object>> toListMap(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
			});
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to parse JSON: " + json, e);
		}
	}

	@Test
	void testDefaultConstructor() {
		DefaultJsonSchemaValidator defaultValidator = new DefaultJsonSchemaValidator();

		String schemaJson = "{\"type\": \"object\",					\"properties\": {						\"test\": {\"type\": \"string\"}					}}";
		String contentJson = "{	\"test\": \"value\"	}";

		ValidationResponse response = defaultValidator.validate(toMap(schemaJson), toMap(contentJson));
		assertTrue(response.isValid());
	}

	@Test
	void testConstructorWithObjectMapper() {
		ObjectMapper customMapper = new ObjectMapper();
		DefaultJsonSchemaValidator customValidator = new DefaultJsonSchemaValidator(customMapper);

		String schemaJson = "{					\"type\": \"object\",					\"properties\": {						\"test\": {\"type\": \"string\"}					}				}";

		String contentJson = "{\n" + "    \"test\": \"value\"\n" + "}";

		ValidationResponse response = customValidator.validate(toMap(schemaJson), toMap(contentJson));
		assertTrue(response.isValid());
	}

	@Test
	void testValidateWithValidStringSchema() {

		String schemaJson = "{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
				+ "    \"name\": {\"type\": \"string\"},\n" + "    \"age\": {\"type\": \"integer\"}\n" + "  },\n"
				+ "  \"required\": [\"name\", \"age\"]\n" + "}\n";

		String contentJson = "{\n" + "  \"name\": \"John Doe\",\n" + "  \"age\": 30\n" + "}\n";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
		assertNotNull(response.getJsonStructuredOutput());
	}

	@Test
	void testValidateWithValidNumberSchema() {

		String schemaJson = "{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
				+ "    \"price\": {\"type\": \"number\", \"minimum\": 0},\n"
				+ "    \"quantity\": {\"type\": \"integer\", \"minimum\": 1}\n" + "  },\n"
				+ "  \"required\": [\"price\", \"quantity\"]\n" + "}\n";

		String contentJson = "{\n" + "  \"price\": 19.99,\n" + "  \"quantity\": 5\n" + "}\n";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
	}

	@Test
	void testValidateWithValidArraySchema() {

		String schemaJson = "{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n" + "    \"items\": {\n"
				+ "      \"type\": \"array\",\n" + "      \"items\": {\"type\": \"string\"}\n" + "    }\n" + "  },\n"
				+ "  \"required\": [\"items\"]\n" + "}\n";

		String contentJson = "{\n" + "  \"items\": [\"apple\", \"banana\", \"cherry\"]\n" + "}\n";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
	}

	@Test
	void testValidateWithValidArraySchemaTopLevelArray() {

		String schemaJson = "{\n" + "  \"$schema\": \"https://json-schema.org/draft/2020-12/schema\",\n"
				+ "  \"type\": \"array\",\n" + "  \"items\": {\n" + "    \"type\": \"object\",\n"
				+ "    \"properties\": {\n" + "      \"city\": { \"type\": \"string\" },\n"
				+ "      \"summary\": { \"type\": \"string\" },\n"
				+ "      \"temperatureC\": { \"type\": \"number\" }\n" + "    },\n"
				+ "    \"required\": [ \"city\", \"summary\", \"temperatureC\" ],\n"
				+ "    \"additionalProperties\": false\n" + "  }\n" + "}\n";

		String contentJson = "[\n" + "  {\n" + "    \"city\": \"London\",\n"
				+ "    \"summary\": \"Generally mild with frequent rainfall. Winters are cool and damp, summers are warm but rarely hot. Cloudy conditions are common throughout the year.\",\n"
				+ "    \"temperatureC\": 11.3\n" + "  },\n" + "  {\n" + "    \"city\": \"New York\",\n"
				+ "    \"summary\": \"Four distinct seasons with hot and humid summers, cold winters with snow, and mild springs and autumns. Precipitation is fairly evenly distributed throughout the year.\",\n"
				+ "    \"temperatureC\": 12.8\n" + "  },\n" + "  {\n" + "    \"city\": \"San Francisco\",\n"
				+ "    \"summary\": \"Mild year-round with a distinctive Mediterranean climate. Famous for summer fog, mild winters, and little temperature variation throughout the year. Very little rainfall in summer months.\",\n"
				+ "    \"temperatureC\": 14.6\n" + "  },\n" + "  {\n" + "    \"city\": \"Tokyo\",\n"
				+ "    \"summary\": \"Humid subtropical climate with hot, wet summers and mild winters. Experiences a rainy season in early summer and occasional typhoons in late summer to early autumn.\",\n"
				+ "    \"temperatureC\": 15.4\n" + "  }\n" + "]\n";

		Map<String, Object> schema = toMap(schemaJson);

		// Validate as JSON string
		ValidationResponse response = validator.validate(schema, contentJson);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());

		List<Map<String, Object>> structuredContent = toListMap(contentJson);

		// Validate as List<Map<String, Object>>
		response = validator.validate(schema, structuredContent);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
	}

	@Test
	void testValidateWithInvalidTypeSchema() {

		String schemaJson = "{\n" + "    \"type\": \"object\",\n" + "    \"properties\": {\n"
				+ "        \"name\": {\"type\": \"string\"},\n" + "        \"age\": {\"type\": \"integer\"}\n"
				+ "    },\n" + "    \"required\": [\"name\", \"age\"]\n" + "}";

		String contentJson = "{\n" + "    \"name\": \"John Doe\",\n" + "    \"age\": \"thirty\"\n" + "}";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertFalse(response.isValid());
		assertNotNull(response.getErrorMessage());
		assertTrue(response.getErrorMessage().contains("Validation failed"));
		assertTrue(response.getErrorMessage().contains("structuredContent does not match tool outputSchema"));
	}

	@Test
	void testValidateWithMissingRequiredField() {

		String schemaJson = "{\n" + "    \"type\": \"object\",\n" + "    \"properties\": {\n"
				+ "        \"name\": {\"type\": \"string\"},\n" + "        \"age\": {\"type\": \"integer\"}\n"
				+ "    },\n" + "    \"required\": [\"name\", \"age\"]\n" + "}";

		String contentJson = "{\n" + "    \"name\": \"John Doe\"\n" + "}";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertFalse(response.isValid());
		assertNotNull(response.getErrorMessage());
		assertTrue(response.getErrorMessage().contains("Validation failed"));
	}

	@Test
	void testValidateWithAdditionalPropertiesNotAllowed() {

		String schemaJson = "{\n" + "    \"type\": \"object\",\n" + "    \"properties\": {\n"
				+ "        \"name\": {\"type\": \"string\"}\n" + "    },\n" + "    \"required\": [\"name\"],\n"
				+ "    \"additionalProperties\": false\n" + "}";

		String contentJson = "{\n" + "    \"name\": \"John Doe\",\n" + "    \"extraField\": \"should not be allowed\"\n"
				+ "}";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertFalse(response.isValid());
		assertNotNull(response.getErrorMessage());
		assertTrue(response.getErrorMessage().contains("Validation failed"));
	}

	@Test
	void testValidateWithAdditionalPropertiesExplicitlyAllowed() {

		String schemaJson = "{\n" + "    \"type\": \"object\",\n" + "    \"properties\": {\n"
				+ "        \"name\": {\"type\": \"string\"}\n" + "    },\n" + "    \"required\": [\"name\"],\n"
				+ "    \"additionalProperties\": true\n" + "}";

		String contentJson = "{\n" + "  \"name\": \"John Doe\",\n" + "  \"extraField\": \"should be allowed\"\n"
				+ "}\n";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
	}

	@Test
	void testValidateWithDefaultAdditionalProperties() {

		final String nl = System.lineSeparator();

		String schemaJson = String.join(nl, "{", "  \"type\": \"object\",", "  \"properties\": {",
				"    \"name\": {\"type\": \"string\"}", "  },", "  \"required\": [\"name\"],",
				"  \"additionalProperties\": true", "}") + nl;

		String contentJson = String.join(nl, "{", "  \"name\": \"John Doe\",",
				"  \"extraField\": \"should be allowed\"", "}") + nl;

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
	}

	@Test
	void testValidateWithAdditionalPropertiesExplicitlyDisallowed() {

		final String nl = System.lineSeparator();

		String schemaJson = String.join(nl, "{", "  \"type\": \"object\",", "  \"properties\": {",
				"    \"name\": {\"type\": \"string\"}", "  },", "  \"required\": [\"name\"],",
				"  \"additionalProperties\": false", "}") + nl;

		String contentJson = String.join(nl, "{", "  \"name\": \"John Doe\",",
				"  \"extraField\": \"should be allowed\"", "}") + nl;

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertFalse(response.isValid());
		assertNotNull(response.getErrorMessage());
		assertTrue(response.getErrorMessage().contains("Validation failed"));
	}

	@Test
	void testValidateWithEmptySchema() {

		String schemaJson = "{\n" + "  \"additionalProperties\": true\n" + "}\n";

		String contentJson = "{\n" + "  \"anything\": \"goes\"\n" + "}\n";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
	}

	@Test
	void testValidateWithEmptyContent() {

		String schemaJson = "{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {}\n" + "}\n";

		String contentJson = "{\n" + "}\n";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
	}

	@Test
	void testValidateWithNestedObjectSchema() {

		String schemaJson = "{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n" + "    \"person\": {\n"
				+ "      \"type\": \"object\",\n" + "      \"properties\": {\n"
				+ "        \"name\": {\"type\": \"string\"},\n" + "        \"address\": {\n"
				+ "          \"type\": \"object\",\n" + "          \"properties\": {\n"
				+ "            \"street\": {\"type\": \"string\"},\n" + "            \"city\": {\"type\": \"string\"}\n"
				+ "          },\n" + "          \"required\": [\"street\", \"city\"]\n" + "        }\n" + "      },\n"
				+ "      \"required\": [\"name\", \"address\"]\n" + "    }\n" + "  },\n"
				+ "  \"required\": [\"person\"]\n" + "}\n";

		String contentJson = "{\n" + "  \"person\": {\n" + "    \"name\": \"John Doe\",\n" + "    \"address\": {\n"
				+ "      \"street\": \"123 Main St\",\n" + "      \"city\": \"Anytown\"\n" + "    }\n" + "  }\n"
				+ "}\n";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
	}

	@Test
	void testValidateWithInvalidNestedObjectSchema() {

		String schemaJson = "{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n" + "    \"person\": {\n"
				+ "      \"type\": \"object\",\n" + "      \"properties\": {\n"
				+ "        \"name\": {\"type\": \"string\"},\n" + "        \"address\": {\n"
				+ "          \"type\": \"object\",\n" + "          \"properties\": {\n"
				+ "            \"street\": {\"type\": \"string\"},\n" + "            \"city\": {\"type\": \"string\"}\n"
				+ "          },\n" + "          \"required\": [\"street\", \"city\"]\n" + "        }\n" + "      },\n"
				+ "      \"required\": [\"name\", \"address\"]\n" + "    }\n" + "  },\n"
				+ "  \"required\": [\"person\"]\n" + "}\n";

		String contentJson = "{\n" + "  \"person\": {\n" + "    \"name\": \"John Doe\",\n" + "    \"address\": {\n"
				+ "      \"street\": \"123 Main St\"\n" + "    }\n" + "  }\n" + "}\n";

		Map<String, Object> schema = toMap(schemaJson);
		Map<String, Object> structuredContent = toMap(contentJson);

		ValidationResponse response = validator.validate(schema, structuredContent);

		assertFalse(response.isValid());
		assertNotNull(response.getErrorMessage());
		assertTrue(response.getErrorMessage().contains("Validation failed"));
	}

	@Test
	void testValidateWithJsonProcessingException() throws Exception {
		DefaultJsonSchemaValidator validatorWithMockMapper = new DefaultJsonSchemaValidator(mockObjectMapper);

		Map<String, Object> schema = Collections.singletonMap("type", "object");
		Map<String, Object> structuredContent = Collections.singletonMap("key", "value");

		// This will trigger our null check and throw JsonProcessingException
		when(mockObjectMapper.valueToTree(any())).thenReturn(null);

		ValidationResponse response = validatorWithMockMapper.validate(schema, structuredContent);

		assertFalse(response.isValid());
		assertNotNull(response.getErrorMessage());
		assertTrue(response.getErrorMessage().contains("Error parsing tool JSON Schema"));
		assertTrue(response.getErrorMessage().contains("Failed to convert schema to JsonNode"));
	}

	@ParameterizedTest
	@MethodSource("provideValidSchemaAndContentPairs")
	void testValidateWithVariousValidInputs(Map<String, Object> schema, Map<String, Object> content) {
		ValidationResponse response = validator.validate(schema, content);

		assertTrue(response.isValid(),
				"Expected validation to pass for schema: " + schema + " and content: " + content);
		assertNull(response.getErrorMessage());
	}

	@ParameterizedTest
	@MethodSource("provideInvalidSchemaAndContentPairs")
	void testValidateWithVariousInvalidInputs(Map<String, Object> schema, Map<String, Object> content) {
		ValidationResponse response = validator.validate(schema, content);

		assertFalse(response.isValid(),
				"Expected validation to fail for schema: " + schema + " and content: " + content);
		assertNotNull(response.getErrorMessage());
		assertTrue(response.getErrorMessage().contains("Validation failed"));
	}

	private static Map<String, Object> staticToMap(String json) {
		try {
			ObjectMapper mapper = new ObjectMapper();
			return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to parse JSON: " + json, e);
		}
	}

	private static Stream<Arguments> provideValidSchemaAndContentPairs() {
		return Stream.of(
				// Boolean schema
				Arguments.of(
						staticToMap("{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
								+ "    \"flag\": {\"type\": \"boolean\"}\n" + "  }\n" + "}\n"),
						staticToMap("{\n" + "  \"flag\": true\n" + "}\n")),
				// String with additional properties allowed
				Arguments.of(
						staticToMap("{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
								+ "    \"name\": {\"type\": \"string\"}\n" + "  },\n"
								+ "  \"additionalProperties\": true\n" + "}\n"),
						staticToMap("{\n" + "  \"name\": \"test\",\n" + "  \"extra\": \"allowed\"\n" + "}\n")),
				// Array with specific items
				Arguments.of(
						staticToMap("{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
								+ "    \"numbers\": {\n" + "      \"type\": \"array\",\n"
								+ "      \"items\": {\"type\": \"number\"}\n" + "    }\n" + "  }\n" + "}\n"),
						staticToMap("{\n" + "  \"numbers\": [1.0, 2.5, 3.14]\n" + "}\n")),
				// Enum validation
				Arguments.of(staticToMap("{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
						+ "    \"status\": {\n" + "      \"type\": \"string\",\n"
						+ "      \"enum\": [\"active\", \"inactive\", \"pending\"]\n" + "    }\n" + "  }\n" + "}\n"),
						staticToMap("{\n" + "  \"status\": \"active\"\n" + "}\n")));
	}

	private static Stream<Arguments> provideInvalidSchemaAndContentPairs() {
		return Stream.of(
				// Wrong boolean type
				Arguments.of(
						staticToMap("{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
								+ "    \"flag\": {\"type\": \"boolean\"}\n" + "  }\n" + "}\n"),
						staticToMap("{\n" + "  \"flag\": \"true\"\n" + "}\n")),
				// Array with wrong item types
				Arguments.of(
						staticToMap("{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
								+ "    \"numbers\": {\n" + "      \"type\": \"array\",\n"
								+ "      \"items\": {\"type\": \"number\"}\n" + "    }\n" + "  }\n" + "}\n"),
						staticToMap("{\n" + "  \"numbers\": [\"one\", \"two\", \"three\"]\n" + "}\n")),
				// Invalid enum value
				Arguments.of(staticToMap("{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
						+ "    \"status\": {\n" + "      \"type\": \"string\",\n"
						+ "      \"enum\": [\"active\", \"inactive\", \"pending\"]\n" + "    }\n" + "  }\n" + "}\n"),
						staticToMap("{\n" + "  \"status\": \"unknown\"\n" + "}\n")),
				// Minimum constraint violation
				Arguments.of(
						staticToMap("{\n" + "  \"type\": \"object\",\n" + "  \"properties\": {\n"
								+ "    \"age\": {\"type\": \"integer\", \"minimum\": 0}\n" + "  }\n" + "}\n"),
						staticToMap("{\n" + "  \"age\": -5\n" + "}\n")));
	}

	@Test
	void testValidationResponseToValid() {
		String jsonOutput = "{\"test\":\"value\"}";
		ValidationResponse response = ValidationResponse.asValid(jsonOutput);
		assertTrue(response.isValid());
		assertNull(response.getErrorMessage());
		assertEquals(jsonOutput, response.getJsonStructuredOutput());
	}

	@Test
	void testValidationResponseToInvalid() {
		String errorMessage = "Test error message";
		ValidationResponse response = ValidationResponse.asInvalid(errorMessage);
		assertFalse(response.isValid());
		assertEquals(errorMessage, response.getErrorMessage());
		assertNull(response.getJsonStructuredOutput());
	}

	@Test
	void testValidationResponseRecord() {
		ValidationResponse response1 = new ValidationResponse(true, null, "{\"valid\":true}");
		ValidationResponse response2 = new ValidationResponse(false, "Error", null);

		assertTrue(response1.isValid());
		assertNull(response1.getErrorMessage());
		assertEquals("{\"valid\":true}", response1.getJsonStructuredOutput());

		assertFalse(response2.isValid());
		assertEquals("Error", response2.getErrorMessage());
		assertNull(response2.getJsonStructuredOutput());

		// Test equality
		ValidationResponse response3 = new ValidationResponse(true, null, "{\"valid\":true}");
		assertEquals(response1, response3);
		assertNotEquals(response1, response2);
	}

}
