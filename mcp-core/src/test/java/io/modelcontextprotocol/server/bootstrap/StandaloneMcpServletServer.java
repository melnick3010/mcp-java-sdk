package io.modelcontextprotocol.server.bootstrap;


import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.Context;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpServerSession;

// Avvio server standalone con Tomcat embedded.
public final class StandaloneMcpServletServer {

    public static void main(String[] args) throws Exception {
        // Parametri via system properties (puoi sovrascriverli a runtime)
        final int port = Integer.parseInt(System.getProperty("port", "38001"));
        final String mapping = System.getProperty("mapping", "/somePath/*");         // mappa il servlet su /somePath/*
        final String sseEndpoint = System.getProperty("sseEndpoint", "/sse");        // GET /somePath/sse
        final String messageEndpoint = System.getProperty("messageEndpoint", "/mcp/message"); // POST /somePath/mcp/message
        final boolean enableKeepAlive = Boolean.parseBoolean(System.getProperty("keepAlive", "false"));
        final Duration keepAliveInterval = enableKeepAlive ? Duration.ofSeconds(2) : null;

        // 1) Tomcat embedded
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(port);
        Path baseDir = Files.createTempDirectory("mcp-servlet");
        Context ctx = tomcat.addContext("", baseDir.toString());

        // 2) Costruisci il provider servlet
        HttpServletSseServerTransportProvider provider =
                HttpServletSseServerTransportProvider.builder()
                        .jsonMapper(McpJsonMapper.getDefault())
                        .baseUrl("") // verrà ricostruito da scheme/host/port della request
                        .messageEndpoint(messageEndpoint)
                        .sseEndpoint(sseEndpoint)
                        .keepAliveInterval(keepAliveInterval) // null = disabilitato
                        .contextExtractor((req) -> McpTransportContextExtractor.EMPTY.extract(req))
                        .build();

        // 3) Collega la factory delle sessioni (esempio: usa il tuo McpAsyncServer)
        //    NB: la factory deve creare una nuova McpServerSession per ogni connessione SSE.
        //io.modelcontextprotocol.server.McpAsyncServer asyncServer = new io.modelcontextprotocol.server.McpAsyncServer(provider);
        io.modelcontextprotocol.server.McpAsyncServer asyncServer = McpServer.async(provider).serverInfo("integration-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(60)).build();
        provider.setSessionFactory(asyncServer);
        

        // 4) Registra il servlet e la mapping ("/somePath/*" per replicare i tuoi log)
        final String servletName = "mcp-sse";
        Tomcat.addServlet(ctx, servletName, provider);
        ctx.addServletMappingDecoded(mapping, servletName);

        // 5) Avvio + attesa (non chiude il processo finché il server è attivo)
        tomcat.start();
        System.out.printf("Tomcat avviato su porta %d%n", port);
        System.out.printf("Servlet mapping: %s (SSE=%s, MESSAGE=%s)%n", mapping, sseEndpoint, messageEndpoint);
        tomcat.getServer().await();
    }
}