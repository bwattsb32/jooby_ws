package com.dts_inc.z2c.module.sparql;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscriber;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import static io.netty.buffer.Unpooled.*;

/**
 * Process the HTTP response from the server. Coerce into client (Hiera) expected JSON format.
 * @author bergstromr
 *
 */
public class SparqlClientHandler extends SimpleChannelInboundHandler<HttpObject> {
	static Logger logger = LoggerFactory.getLogger(SparqlClientHandler.class);

	// Buffer used to concatenate a potentially chunked response.
	private ByteBuf chunks = null;

	private Subscriber<? super String> subscriber = null;
	
	public SparqlClientHandler(Subscriber<? super String> subscriber) {
		logger.debug("SparqlClientHandler Ctor");
		this.subscriber = subscriber;
	}

	/**
	 * Process the server response. Gather all pieces of the response into a single buffer of
	 * JSON-LD. Use the JSON-LD library to compact using customer context to 
	 * simplify the object for Hiera.
	 */
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
		
		// This is all debugging...
		if (msg instanceof HttpResponse) {
			HttpResponse response = (HttpResponse) msg;

			logger.debug("STATUS: " + response.status());
			logger.debug("VERSION: " + response.protocolVersion());

			if (!response.headers().isEmpty()) {
				for (CharSequence name : response.headers().names()) {
					for (CharSequence value : response.headers().getAll(name)) {
						logger.debug("HEADER: " + name + " = " + value);
					}
				}
			}

			if (HttpUtil.isTransferEncodingChunked(response)) {
				logger.debug("CHUNKED CONTENT {");
			} else {
				logger.debug("CONTENT {");
			}
		}
		
		// This is where the work happens. This handler gets called MULTIPLE times.
		if (msg instanceof HttpContent) {
			// The data from the server
			HttpContent content = (HttpContent) msg;

			// Gather all the chunks.
			if (null == chunks) {
				logger.debug("Chunks initialized.");
				chunks = copiedBuffer(content.content());
			} else {
				logger.debug("Adding chunks");
				chunks = copiedBuffer(chunks, content.content());
			}

			logger.debug(content.content().toString(CharsetUtil.UTF_8));

			if (content instanceof LastHttpContent) {
				logger.debug("} END OF CONTENT");
				logger.debug("Subscriber in handler: {}", this.subscriber);
				logger.debug("Chunks: {}", this.chunks);
				subscriber.onNext(chunks.toString(CharsetUtil.UTF_8));
				ctx.close();
			}
		}
	}

	/**
	 * Handler error handling.
	 */
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.error("Error processing response: ", cause);
		ctx.close();
	}
}
