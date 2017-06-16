package com.dts_inc.z2c.module.rx.sparql;

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
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder.ErrorDataEncoderException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dts_inc.z2c.module.sparql.SparqlClientHandler;
import com.dts_inc.z2c.module.sparql.SparqlClientInitializer;

import rx.Observable;

public class RxSparqlClient {
	static Logger logger = LoggerFactory.getLogger(RxSparqlClient.class);

	// URL of the SPARQL endpoint.
	private String endpointUrl;
	
	public RxSparqlClient() {
		logger.warn("Default web client. Connecting to local host at default namespace, port 8080!");
		endpointUrl = "http://127.0.0.1:8080/blazegraph/sparql";
	}

	/**
	 * Create client with the specified endpoint.
	 * 
	 * @param url
	 */
	public RxSparqlClient(String url) {
		endpointUrl = url;
		logger.warn("Connected to configured (application.conf) blazegraph endpoint: {}", url);
	}
	
	public Observable<String> constructQuery(String query) {
		return Observable.create(subscriber ->{
			logger.debug("constructQuery: {}\n", query);
			// Build the handler objects initializer->handler->subscriber
			RxSparqlClientHandler handler = new RxSparqlClientHandler(subscriber);
			RxSparqlClientInitializer initializer = new RxSparqlClientInitializer(handler);
			
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
					subscriber.onError(new Exception("Only HTTP(S) is supported."));
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
				EventLoopGroup group = new NioEventLoopGroup();
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
					subscriber.onError(e);
				} catch (ErrorDataEncoderException e) {
					// test if getMethod is a POST getMethod
					logger.error("Error encoding POST request: ", e);
					subscriber.onError(e);
				}

				// Add Form parameters. The only one you really need is "query"
				try {
					bodyRequestEncoder.addBodyAttribute("getform", "POST");
					// Put the SPARQL query in the body as a paramter.
					bodyRequestEncoder.addBodyAttribute("query", query);
				} catch (NullPointerException e) {
					// should not be since not null args
					logger.error("Error adding form attributes: ", e);
					subscriber.onError(e);
				} catch (ErrorDataEncoderException e) {
					// if an encoding error occurs
					logger.error("Error adding form attributes: ", e);
					subscriber.onError(e);
				}

				// Finalize request. Required by library.
				try {
					request = bodyRequestEncoder.finalizeRequest();
				} catch (ErrorDataEncoderException e) {
					// if an encoding error occurs
					logger.error("Error finalizing request", e);
					subscriber.onError(e);
				}

				// Send the HTTP request to server.
				// SparqlClientHander will process the server response.
				ch.writeAndFlush(request);

				// Wait for the server to close the connection.
				ch.closeFuture().sync();
				
				group.shutdownGracefully();
				
				// Close the observable
				subscriber.onCompleted();
			} catch (URISyntaxException e) {
				logger.error("Error connecting to endpoint: ", e);
				subscriber.onError(e);
			//} catch (SSLException e) {
			//	logger.error("Error connecting to endpoint: ", e);
			} catch (InterruptedException e) {
				logger.error("Error connecting to endpoint: ", e);
				subscriber.onError(e);
			}
		});
	}
	
	/**
	 * Accepts a string containing Turtle encoded triples for insert.
	 * @param triples
	 * @return Server response in XML.
	 */
	public Observable<String> turtleInsert(String ttlString) {
		return Observable.create(subscriber ->{
			logger.debug("turtleInsert: \n{}", ttlString);
			
			// Build the handler objects initializer->handler->subscriber
			RxSparqlClientHandler handler = new RxSparqlClientHandler(subscriber);
			RxSparqlClientInitializer initializer = new RxSparqlClientInitializer(handler);
			
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
					subscriber.onError(new Exception("Only HTTP(S) is supported."));
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
				EventLoopGroup group = new NioEventLoopGroup();
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
				
				// TODO: This may be too much churn. Hold at class level?
				group.shutdownGracefully().sync();
			} catch (URISyntaxException e) {
				logger.error("Error connecting to endpoint: ", e);
				subscriber.onError(e);
			//} catch (SSLException e) {
			//	logger.error("Error connecting to endpoint: ", e);
			} catch (InterruptedException e) {
				logger.error("Error connecting to endpoint: ", e);
				subscriber.onError(e);
			}
		});
	}
	
	/**
	 * Delete triples using graph query (describe or construct) to select.
	 * @param query
	 * @return
	 */
	public Observable<String> deleteWithQuery(String query) {
		return Observable.create(subscriber -> {
			logger.debug("deleteWithQuery: {}\n", query);
			// Build the handler objects initializer->handler->subscriber
			RxSparqlClientHandler handler = new RxSparqlClientHandler(subscriber);
			RxSparqlClientInitializer initializer = new RxSparqlClientInitializer(handler);
			
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
					subscriber.onError(new Exception("Only HTTP(S) is supported."));
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
				EventLoopGroup group = new NioEventLoopGroup();
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
				subscriber.onError(e);
			//} catch (SSLException e) {
			//	logger.error("Error connecting to endpoint: ", e);
			} catch (InterruptedException e) {
				logger.error("Error connecting to endpoint: ", e);
				subscriber.onError(e);
			} catch (Exception e) {
				logger.error("Error connecting to endpoint: ", e);
				subscriber.onError(e);
			}
				
		});
	}
}
