/*
* Copyright 2025 - 2025 the original author or authors.
*/

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

/**
 * @author Christian Tzolov
 * @author Daniel Garnier-Moiroux
 */
public class TomcatTestUtil {

	TomcatTestUtil() {
		// Prevent instantiation
	}

	public static Tomcat createTomcatServer(String contextPath, int port, Servlet servlet,
			Filter... additionalFilters) {

		Tomcat tomcat = new Tomcat();
		tomcat.setPort(port);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		Context context = tomcat.addContext(contextPath, baseDir);

		// Add transport servlet to Tomcat
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(servlet);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		for (Filter filter : additionalFilters) {
			FilterDef filterDef = new FilterDef();
			filterDef.setFilter(filter);
			filterDef.setFilterName(McpTestRequestRecordingServletFilter.class.getSimpleName());
			context.addFilterDef(filterDef);

			FilterMap filterMap = new FilterMap();
			filterMap.setFilterName(McpTestRequestRecordingServletFilter.class.getSimpleName());
			filterMap.addURLPattern("/*");
			context.addFilterMap(filterMap);
		}

		org.apache.catalina.connector.Connector connector = tomcat.getConnector();
		connector.setAsyncTimeout(3000);

		return tomcat;
	}

	/**
	 * Finds an available port on the local machine.
	 * @return an available port number
	 * @throws IllegalStateException if no available port can be found
	 */
	public static int findAvailablePort() {
		try (final ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress(0));
			return socket.getLocalPort();
		}
		catch (final IOException e) {
			throw new IllegalStateException("Cannot bind to an available port!", e);
		}
	}
	
	public static void awaitServer(Tomcat tomcat) {
	    tomcat.getServer().await();
	}
	

public static class HealthServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        resp.getWriter().write("OK");
    }
}


}
