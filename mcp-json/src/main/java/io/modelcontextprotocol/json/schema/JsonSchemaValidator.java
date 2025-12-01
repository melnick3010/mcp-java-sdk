/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.json.schema;

import java.util.Map;

/**
 * Interface for validating structured content against a JSON schema. This interface
 * defines a method to validate structured content based on the provided output schema.
 *
 * @author Christian Tzolov
 */
public interface JsonSchemaValidator {

	/**
	 * Represents the result of a validation operation. Immutable value type compatible
	 * with Java 8 (replacement for the Java 16 "record").
	 *
	 * @param valid Indicates whether the validation was successful.
	 * @param errorMessage An error message if the validation failed, otherwise null.
	 * @param jsonStructuredOutput The text structured content in JSON format if the
	 * validation was successful, otherwise null.
	 */
	public static final class ValidationResponse {

		private final boolean valid;

		private final String errorMessage;

		private final String jsonStructuredOutput;

		public ValidationResponse(boolean valid, String errorMessage, String jsonStructuredOutput) {
			this.valid = valid;
			this.errorMessage = errorMessage;
			this.jsonStructuredOutput = jsonStructuredOutput;
		}

		public boolean isValid() {
			return valid;
		}

		public String getErrorMessage() {
			return errorMessage;
		}

		public String getJsonStructuredOutput() {
			return jsonStructuredOutput;
		}

		public static ValidationResponse asValid(String jsonStructuredOutput) {
			return new ValidationResponse(true, null, jsonStructuredOutput);
		}

		public static ValidationResponse asInvalid(String message) {
			return new ValidationResponse(false, message, null);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;
			if (!(o instanceof ValidationResponse))
				return false;
			ValidationResponse that = (ValidationResponse) o;
			return valid == that.valid && java.util.Objects.equals(errorMessage, that.errorMessage)
					&& java.util.Objects.equals(jsonStructuredOutput, that.jsonStructuredOutput);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(valid, errorMessage, jsonStructuredOutput);
		}

		@Override
		public String toString() {
			return "ValidationResponse{" + "valid=" + valid + ", errorMessage='" + errorMessage + '\''
					+ ", jsonStructuredOutput='" + jsonStructuredOutput + '\'' + '}';
		}

	}

	/**
	 * Validates the structured content against the provided JSON schema.
	 * @param schema The JSON schema to validate against.
	 * @param structuredContent The structured content to validate.
	 * @return A ValidationResponse indicating whether the validation was successful or
	 * not.
	 */
	ValidationResponse validate(Map<String, Object> schema, Object structuredContent);

	/**
	 * Creates the default {@link JsonSchemaValidator}.
	 * @return The default {@link JsonSchemaValidator}
	 * @throws IllegalStateException If no {@link JsonSchemaValidator} implementation
	 * exists on the classpath.
	 */
	static JsonSchemaValidator createDefault() {
		return JsonSchemaInternal.createDefaultValidator();
	}

	/**
	 * Returns the default {@link JsonSchemaValidator}.
	 * @return The default {@link JsonSchemaValidator}
	 * @throws IllegalStateException If no {@link JsonSchemaValidator} implementation
	 * exists on the classpath.
	 */
	static JsonSchemaValidator getDefault() {
		return JsonSchemaInternal.getDefaultValidator();
	}

}