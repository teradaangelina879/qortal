package org.qora.api;

import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.eclipse.jetty.rewrite.handler.RedirectPatternRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.RequestLogWriter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.InetAccessHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.qora.api.resource.AnnotationPostProcessor;
import org.qora.api.resource.ApiDefinition;
import org.qora.settings.Settings;

public class ApiService {

	private static ApiService instance;

	private final ResourceConfig config;
	private Server server;

	private ApiService() {
		this.config = new ResourceConfig();
		this.config.packages("org.qora.api.resource");
		this.config.register(OpenApiResource.class);
		this.config.register(ApiDefinition.class);
		this.config.register(AnnotationPostProcessor.class);
	}

	public static ApiService getInstance() {
		if (instance == null)
			instance = new ApiService();

		return instance;
	}

	public Iterable<Class<?>> getResources() {
		return this.config.getClasses();
	}

	public void start() {
		try {
			// Create API server
			InetAddress bindAddr = InetAddress.getByName(Settings.getInstance().getBindAddress());
			InetSocketAddress endpoint = new InetSocketAddress(bindAddr, Settings.getInstance().getApiPort());
			this.server = new Server(endpoint);

			// Error handler
			ErrorHandler errorHandler = new ApiErrorHandler();
			this.server.setErrorHandler(errorHandler);

			// Request logging
			if (Settings.getInstance().isApiLoggingEnabled()) {
				RequestLogWriter logWriter = new RequestLogWriter("API-requests.log");
				logWriter.setAppend(true);
				logWriter.setTimeZone("UTC");
				RequestLog requestLog = new CustomRequestLog(logWriter, CustomRequestLog.EXTENDED_NCSA_FORMAT);
				this.server.setRequestLog(requestLog);
			}

			// IP address based access control
			InetAccessHandler accessHandler = new InetAccessHandler();
			for (String pattern : Settings.getInstance().getApiWhitelist()) {
				accessHandler.include(pattern);
			}
			this.server.setHandler(accessHandler);

			// URL rewriting
			RewriteHandler rewriteHandler = new RewriteHandler();
			accessHandler.setHandler(rewriteHandler);

			// Context
			ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
			context.setContextPath("/");
			rewriteHandler.setHandler(context);

			// Cross-origin resource sharing
			FilterHolder corsFilterHolder = new FilterHolder(CrossOriginFilter.class);
			corsFilterHolder.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
			corsFilterHolder.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET, POST, DELETE");
			corsFilterHolder.setInitParameter(CrossOriginFilter.CHAIN_PREFLIGHT_PARAM, "false");
			context.addFilter(corsFilterHolder, "/*", null);

			// API servlet
			ServletContainer container = new ServletContainer(this.config);
			ServletHolder apiServlet = new ServletHolder(container);
			apiServlet.setInitOrder(1);
			context.addServlet(apiServlet, "/*");

			// Swagger-UI static content
			ClassLoader loader = this.getClass().getClassLoader();
			ServletHolder swaggerUIServlet = new ServletHolder("static-swagger-ui", DefaultServlet.class);
			swaggerUIServlet.setInitParameter("resourceBase", loader.getResource("resources/swagger-ui/").toString());
			swaggerUIServlet.setInitParameter("dirAllowed", "true");
			swaggerUIServlet.setInitParameter("pathInfoOnly", "true");
			context.addServlet(swaggerUIServlet, "/api-documentation/*");

			rewriteHandler.addRule(new RedirectPatternRule("", "/api-documentation/")); // redirect to Swagger UI start page
			rewriteHandler.addRule(new RedirectPatternRule("/api-documentation", "/api-documentation/")); // redirect to Swagger UI start page

			// Start server
			this.server.start();
		} catch (Exception e) {
			// Failed to start
			throw new RuntimeException("Failed to start API", e);
		}
	}

	public void stop() {
		try {
			// Stop server
			this.server.stop();
		} catch (Exception e) {
			// Failed to stop
		}

		this.server = null;
	}

}
