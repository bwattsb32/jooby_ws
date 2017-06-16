package com.dts_inc.z2c.module.sparql;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder.ErrorDataEncoderException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import javax.net.ssl.SSLException;

import org.jooby.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscriber;

import com.google.inject.Inject;

/**
 * Encapsulates making SPARQL queries over HTTP.
 * 
 * @author bergstromr
 *
 */
public class SparqlClient {
	static Logger logger = LoggerFactory.getLogger(SparqlClient.class);

	// URL of the SPARQL endpoint.
	private String endpointUrl;

	// Thread pool for connection. Must be shutdown.
	EventLoopGroup group;
	
	SparqlClientInitializer initializer;
	// Need this to return Observable for response.
	SparqlClientHandler handler;

	public SparqlClient() {
		logger.warn("Default web client. Connecting to local host at default namespace, port 8080!");
		endpointUrl = "http://127.0.0.1:8080/blazegraph/sparql";
	}

	/**
	 * Create client with the specified endpoint.
	 * 
	 * @param url
	 */
	public SparqlClient(String url) {
		endpointUrl = url;
		logger.warn("Connected to configured (application.conf) blazegraph endpoint: {}", url);
	}

	/**
	 * Connect to configured endpoint and POST a form that contains a SPARQL query.
	 * TODO: Should response object be done differently? Injected?
	 * @param rsp Jooby Response object to communcating with client. 
	 * @throws Exception 
	 */
	public void executeQuery(String query, Subscriber<? super String> subscriber) throws Exception {
		logger.debug("executeQuery++");
		
		// Build the handler objects initializer->handler->subscriber
		handler = new SparqlClientHandler(subscriber);
		SparqlClientInitializer initializer = new SparqlClientInitializer(handler);
		
		URI uri;
		try {
			uri = new URI(endpointUrl);
			logger.debug("Uri: {}", uri);
			String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
			String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
			int port = uri.getPort();
			if (port == -1) {
				if ("http".equalsIgnoreCase(scheme)) {
					port = 80;
				} else if ("https".equalsIgnoreCase(scheme)) {
					port = 443;
				}
			}

			if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
				throw new Exception("Only HTTP(S) is supported.");
			}

			// Create SSL context if necessary.
			// TODO: This is insecure!!!
			//final boolean ssl = "https".equalsIgnoreCase(scheme);
			/*final SslContext sslCtx;
			if (ssl) {
				logger.warn("Connecting using SSL and NO CERTIFICATE VERIFICATION.");
				sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
			} else {
				sslCtx = null;
			}*/

			logger.debug("Connecting.");

			// This represent a thread pool. Make sure you close the "group"!  See close().
			group = new NioEventLoopGroup();
			Bootstrap b = new Bootstrap();
			b.group(group).channel(NioSocketChannel.class).handler(initializer);

			// Make the connection attempt.
			Channel ch = b.connect(host, port).sync().channel();

			// Prepare the HTTP request.
			HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri.getRawPath());
			request.headers().set(HttpHeaderNames.HOST, host);
			request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
			request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
			
			// This header is key for Blazegraph to interpret the query. 
			request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded");
			
			// This header is the response data format. We want json-ld for later compaction.
			request.headers().set(HttpHeaderNames.ACCEPT,"application/ld+json");

			// Used to encode the HTTP Form paramters.
			HttpPostRequestEncoder bodyRequestEncoder = null;
			try {
				// Wrap the request.
				bodyRequestEncoder = new HttpPostRequestEncoder(request, false);
			} catch (NullPointerException e) {
				// should not be since args are not null
				logger.error("Error encoding POST request: ", e);
			} catch (ErrorDataEncoderException e) {
				// test if getMethod is a POST getMethod
				logger.error("Error encoding POST request: ", e);
			}

			// Add Form parameters. The only one you really need is "query"
			try {
				bodyRequestEncoder.addBodyAttribute("getform", "POST");
				// Put the SPARQL query in the body as a paramter.
				bodyRequestEncoder.addBodyAttribute("query", query);
			} catch (NullPointerException e) {
				// should not be since not null args
				logger.error("Error adding form attributes: ", e);
			} catch (ErrorDataEncoderException e) {
				// if an encoding error occurs
				logger.error("Error adding form attributes: ", e);
			}

			// Finalize request. Required by library.
			try {
				request = bodyRequestEncoder.finalizeRequest();
			} catch (ErrorDataEncoderException e) {
				// if an encoding error occurs
				logger.error("Error finalizing request", e);
			}

			// Send the HTTP request to server.
			// SparqlClientHander will process the server response.
			ch.writeAndFlush(request);

			// Wait for the server to close the connection.
			ch.closeFuture().sync();
		} catch (URISyntaxException e) {
			logger.error("Error connecting to endpoint: ", e);
		//} catch (SSLException e) {
		//	logger.error("Error connecting to endpoint: ", e);
		} catch (InterruptedException e) {
			logger.error("Error connecting to endpoint: ", e);
		}
	}
	
	public void executeInsert(String ttlString, Subscriber<? super String> subscriber) throws Exception {
		logger.debug("executeQuery++");
		
		// Build the handler objects initializer->handler->subscriber
		handler = new SparqlClientHandler(subscriber);
		SparqlClientInitializer initializer = new SparqlClientInitializer(handler);
		
		URI uri;
		try {
			uri = new URI(endpointUrl);
			logger.debug("Uri: {}", uri);
			String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
			String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
			int port = uri.getPort();
			if (port == -1) {
				if ("http".equalsIgnoreCase(scheme)) {
					port = 80;
				} else if ("https".equalsIgnoreCase(scheme)) {
					port = 443;
				}
			}

			if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
				throw new Exception("Only HTTP(S) is supported.");
			}

			// Create SSL context if necessary.
			// TODO: This is insecure!!!
			//final boolean ssl = "https".equalsIgnoreCase(scheme);
			/*final SslContext sslCtx;
			if (ssl) {
				logger.warn("Connecting using SSL and NO CERTIFICATE VERIFICATION.");
				sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
			} else {
				sslCtx = null;
			}*/

			logger.debug("Connecting.");

			// This represent a thread pool. Make sure you close the "group"!  See close().
			group = new NioEventLoopGroup();
			Bootstrap b = new Bootstrap();
			b.group(group).channel(NioSocketChannel.class).handler(initializer);

			// Make the connection attempt.
			Channel ch = b.connect(host, port).sync().channel();

			// Prepare the HTTP request.
			FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri.getRawPath());
			request.headers().set(HttpHeaderNames.HOST, host);
			request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
			request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
			
			// This header is key for Blazegraph to interpret the query. 
			request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-turtle");
			
			// This header is the response data format. We want json-ld for later compaction.
			// TODO: Not sure what the response will be.
			//request.headers().set(HttpHeaderNames.ACCEPT,"application/ld+json");

			// build buffer to set the body
			ByteBuf bbuf = Unpooled.copiedBuffer(ttlString, StandardCharsets.UTF_8);
			request.headers().set(HttpHeaderNames.CONTENT_LENGTH, bbuf.readableBytes());
			request.content().clear().writeBytes(bbuf);

			// Send the HTTP request to server.
			// SparqlClientHander will process the server response.
			ch.writeAndFlush(request);

			// Wait for the server to close the connection.
			ch.closeFuture().sync();
		} catch (URISyntaxException e) {
			logger.error("Error connecting to endpoint: ", e);
		//} catch (SSLException e) {
		//	logger.error("Error connecting to endpoint: ", e);
		} catch (InterruptedException e) {
			logger.error("Error connecting to endpoint: ", e);
		}
	}
	
	public void executeDelete(String query, Subscriber<? super String> subscriber) throws Exception {
		logger.debug("executeQuery++");
		
		// Build the handler objects initializer->handler->subscriber
		handler = new SparqlClientHandler(subscriber);
		SparqlClientInitializer initializer = new SparqlClientInitializer(handler);
		
		URI uri;
		try {
			uri = new URI(endpointUrl);
			logger.debug("Uri: {}", uri);
			String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
			String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
			int port = uri.getPort();
			if (port == -1) {
				if ("http".equalsIgnoreCase(scheme)) {
					port = 80;
				} else if ("https".equalsIgnoreCase(scheme)) {
					port = 443;
				}
			}

			if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
				throw new Exception("Only HTTP(S) is supported.");
			}

			// Create SSL context if necessary.
			// TODO: This is insecure!!!
			//final boolean ssl = "https".equalsIgnoreCase(scheme);
			/*final SslContext sslCtx;
			if (ssl) {
				logger.warn("Connecting using SSL and NO CERTIFICATE VERIFICATION.");
				sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
			} else {
				sslCtx = null;
			}*/

			logger.debug("Connecting.");

			// This represent a thread pool. Make sure you close the "group"!  See close().
			group = new NioEventLoopGroup();
			Bootstrap b = new Bootstrap();
			b.group(group).channel(NioSocketChannel.class).handler(initializer);

			// Make the connection attempt.
			Channel ch = b.connect(host, port).sync().channel();

			// Prepare the HTTP request.
			FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.DELETE, uri.getRawPath()+"?query="+URLEncoder.encode(query,"UTF-8"));
			request.headers().set(HttpHeaderNames.HOST, host);
			request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
			request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
			
			// This header is key for Blazegraph to interpret the query. 
			//request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded");

			
			logger.debug("Final Request: {}", request);

			// Send the HTTP request to server.
			// SparqlClientHander will process the server response.
			ch.writeAndFlush(request);

			// Wait for the server to close the connection.
			ch.closeFuture().sync();
		} catch (URISyntaxException e) {
			logger.error("Error connecting to endpoint: ", e);
		//} catch (SSLException e) {
		//	logger.error("Error connecting to endpoint: ", e);
		} catch (InterruptedException e) {
			logger.error("Error connecting to endpoint: ", e);
		}
	}

	/**
	 * Release resources.
	 */
	public void close() {
		group.shutdownGracefully();
	}
}


//curl --get -X DELETE 'http://localhost:8080/blazegraph/namespace/bergstromr/sparql' --data-urlencode 'query=describe <http://z2c.dts-inc.com/id/420b948d-142c-4098-b3be-b3409b54db4e>'