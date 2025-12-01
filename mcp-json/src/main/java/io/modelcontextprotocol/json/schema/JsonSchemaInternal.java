/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.json.schema;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Internal utility class for creating a default {@link JsonSchemaValidator} instance.
 * This class uses the {@link ServiceLoader} to discover and instantiate a
 * {@link JsonSchemaValidatorSupplier} implementation.
 */
final class JsonSchemaInternal {

	private static JsonSchemaValidator defaultValidator = null;

	/**
	 * Returns the default {@link JsonSchemaValidator} instance. If the default validator
	 * has not been initialized, it will be created using the {@link ServiceLoader} to
	 * discover and instantiate a {@link JsonSchemaValidatorSupplier} implementation.
	 * @return The default {@link JsonSchemaValidator} instance.
	 * @throws IllegalStateException If no {@link JsonSchemaValidatorSupplier}
	 * implementation exists on the classpath or if an error occurs during instantiation.
	 */
	static JsonSchemaValidator getDefaultValidator() {
		if (defaultValidator == null) {
			defaultValidator = JsonSchemaInternal.createDefaultValidator();
		}
		return defaultValidator;
	}

	/**
	 * Creates a default {@link JsonSchemaValidator} instance by loading a
	 * {@link JsonSchemaValidatorSupplier} implementation using the {@link ServiceLoader}.
	 * @return A default {@link JsonSchemaValidator} instance.
	 * @throws IllegalStateException If no {@link JsonSchemaValidatorSupplier}
	 * implementation is found or if an error occurs during instantiation.
	 */

	static JsonSchemaValidator createDefaultValidator() {
		AtomicReference<IllegalStateException> ex = new AtomicReference<>();

		ServiceLoader<JsonSchemaValidatorSupplier> loader = ServiceLoader.load(JsonSchemaValidatorSupplier.class);

		for (JsonSchemaValidatorSupplier supplier : loader) {
			try {
				if (supplier == null) {
					continue; // difesa contro provider null
				}
				try {
					JsonSchemaValidator validator = supplier.get();
					if (validator != null) {
						return validator; // primo valido trovato
					}
				}
				catch (Exception e) {
					addException(ex, e);
					// continua a cercare altri supplier
				}
			}
			catch (Exception e) {
				// eccezioni durante la risoluzione del provider
				addException(ex, e);
			}
		}

		IllegalStateException last = ex.get();
		if (last != null) {
			throw last;
		}
		throw new IllegalStateException("No default JsonSchemaValidatorSupplier implementation found");
	}

	private static void addException(AtomicReference<IllegalStateException> ref, Exception toAdd) {
		ref.updateAndGet(existing -> {
			if (existing == null) {
				return new IllegalStateException("Failed to initialize default JsonSchemaValidatorSupplier", toAdd);
			}
			else {
				existing.addSuppressed(toAdd);
				return existing;
			}
		});
	}

}
