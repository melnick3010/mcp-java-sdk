
/*
 * Copyright 2025 - 2025 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

/**
 * Simple {@link Filter} which records calls made to an MCP server.
 *
 * @author Daniel Garnier-Moiroux
 */
public class McpTestRequestRecordingServletFilter implements Filter {

	// Nota: se il filtro è usato in contesto multi-thread, valuta CopyOnWriteArrayList
	private final List<Call> calls = new ArrayList<>();

	// --- Metodi richiesti da javax.servlet.Filter (API 3.0/3.1/4.0) ---
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// no-op: il filtro non necessita inizializzazione
	}

	@Override
	public void destroy() {
		// no-op: niente risorse da rilasciare
	}
	// -------------------------------------------------------------------

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
			throws IOException, ServletException {

		if (servletRequest instanceof HttpServletRequest) {
			HttpServletRequest req = (HttpServletRequest) servletRequest;

			// Java 8: niente 'var' e niente toUnmodifiableMap
			List<String> headerNames = toList(req.getHeaderNames());

			Map<String, String> headersMutable = headerNames.stream()
				.collect(Collectors.toMap(Function.identity(), new Function<String, String>() {
					@Override
					public String apply(String name) {
						List<String> values = toList(req.getHeaders(name));
						return String.join(",", values);
					}
				}));

			Map<String, String> headers = Collections.unmodifiableMap(headersMutable);

			CachedBodyHttpServletRequest request = new CachedBodyHttpServletRequest(req);

			calls.add(new Call(req.getMethod(), headers, request.getBodyAsString()));

			filterChain.doFilter(request, servletResponse);
		}
		else {
			filterChain.doFilter(servletRequest, servletResponse);
		}
	}

	public List<Call> getCalls() {
		// Java 8: niente List.copyOf; ritorno una copia non modificabile
		return Collections.unmodifiableList(new ArrayList<>(calls));
	}

	// Java 8: sostituisce 'record Call(...)' con una POJO immutabile
	public static final class Call {

		private final String method;

		private final Map<String, String> headers;

		private final String body;

		public Call(String method, Map<String, String> headers, String body) {
			this.method = method;
			// difensivo: manteniamo immutabilità anche qui
			this.headers = (headers == null ? Collections.<String, String>emptyMap()
					: Collections.unmodifiableMap(new HashMap<String, String>(headers)));
			this.body = body;
		}

		public String getMethod() {
			return method;
		}

		public Map<String, String> getHeaders() {
			return headers;
		}

		public String getBody() {
			return body;
		}

	}

	public static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

		private final byte[] cachedBody;

		public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
			super(request);
			// Java 8: niente InputStream.readAllBytes()
			this.cachedBody = toByteArray(request.getInputStream());
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			return new CachedBodyServletInputStream(cachedBody);
		}

		@Override
		public BufferedReader getReader() throws IOException {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}

		public String getBodyAsString() {
			return new String(cachedBody, StandardCharsets.UTF_8);
		}

		private static byte[] toByteArray(InputStream in) throws IOException {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] data = new byte[8192];
			int n;
			while ((n = in.read(data)) != -1) {
				buffer.write(data, 0, n);
			}
			return buffer.toByteArray();
		}

	}

	public static class CachedBodyServletInputStream extends ServletInputStream {

		private final InputStream cachedBodyInputStream;

		public CachedBodyServletInputStream(byte[] cachedBody) {
			this.cachedBodyInputStream = new ByteArrayInputStream(cachedBody);
		}

		@Override
		public boolean isFinished() {
			// Per ByteArrayInputStream available() non lancia IOException
			return (cachedBodyInputStream instanceof ByteArrayInputStream)
					&& ((ByteArrayInputStream) cachedBodyInputStream).available() == 0;
		}

		@Override
		public boolean isReady() {
			// Sorgente in memoria: sempre "ready"
			return true;
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			// Se necessario, implementare la notifica; qui non supportato
			throw new UnsupportedOperationException();
		}

		@Override
		public int read() throws IOException {
			return cachedBodyInputStream.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return cachedBodyInputStream.read(b, off, len);
		}

	}

	// Utility per convertire Enumeration -> List senza warning raw
	private static <T> List<T> toList(Enumeration<T> e) {
		List<T> list = new ArrayList<>();
		while (e != null && e.hasMoreElements()) {
			list.add(e.nextElement());
		}
		return list;
	}

}
