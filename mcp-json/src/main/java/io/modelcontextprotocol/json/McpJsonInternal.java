/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.json;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Utility class for creating a default {@link McpJsonMapper} instance. This class
 * provides a single method to create a default mapper using the {@link ServiceLoader}
 * mechanism.
 */
final class McpJsonInternal {

	private static McpJsonMapper defaultJsonMapper = null;

	/**
	 * Returns the cached default {@link McpJsonMapper} instance. If the default mapper
	 * has not been created yet, it will be initialized using the
	 * {@link #createDefaultMapper()} method.
	 * @return the default {@link McpJsonMapper} instance
	 * @throws IllegalStateException if no default {@link McpJsonMapper} implementation is
	 * found
	 */
	static McpJsonMapper getDefaultMapper() {
		if (defaultJsonMapper == null) {
			defaultJsonMapper = McpJsonInternal.createDefaultMapper();
		}
		return defaultJsonMapper;
	}

	/**
	 * Creates a default {@link McpJsonMapper} instance using the {@link ServiceLoader}
	 * mechanism. The default mapper is resolved by loading the first available
	 * {@link McpJsonMapperSupplier} implementation on the classpath.
	 * @return the default {@link McpJsonMapper} instance
	 * @throws IllegalStateException if no default {@link McpJsonMapper} implementation is
	 * found
	 */

	static McpJsonMapper createDefaultMapper() {
		AtomicReference<IllegalStateException> ex = new AtomicReference<>();

		// ServiceLoader compatibile con Java 8: si itera direttamente sui fornitori
		ServiceLoader<McpJsonMapperSupplier> loader = ServiceLoader.load(McpJsonMapperSupplier.class);

		for (McpJsonMapperSupplier supplier : loader) {
			try {
				// supplier può essere null in casi patologici, difendiamoci
				if (supplier == null) {
					continue;
				}

				try {
					McpJsonMapper mapper = supplier.get();
					if (mapper != null) {
						return mapper; // primo mapper valido trovato
					}
				}
				catch (Exception e) {
					addException(ex, e);
					// continua a cercare altri supplier
				}
			}
			catch (Exception e) {
				// eccezioni durante l'iterazione/caricamento del provider
				addException(ex, e);
			}
		}

		// Nessun supplier valido trovato: propaga l'ultima IllegalStateException se
		// presente
		IllegalStateException last = ex.get();
		if (last != null) {
			throw last;
		}
		throw new IllegalStateException("No default McpJsonMapper implementation found");
	}

	private static void addException(AtomicReference<IllegalStateException> ref, Exception toAdd) {
		ref.updateAndGet(existing -> {
			if (existing == null) {
				return new IllegalStateException("Failed to initialize default McpJsonMapper", toAdd);
			}
			else {
				existing.addSuppressed(toAdd);
				return existing;
			}
		});
	}

}
