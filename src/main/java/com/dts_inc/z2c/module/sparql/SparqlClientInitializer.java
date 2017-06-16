package com.dts_inc.z2c.module.sparql;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures the HTTP communications channel. Initializes SSL. Configures the handler.
 * @author bergstromr
 *
 */
public class SparqlClientInitializer extends ChannelInitializer<SocketChannel> {
	static Logger logger = LoggerFactory.getLogger(SparqlClientInitializer.class);
	private SparqlClientHandler handler;
	
	/**
	 * Ctor. Use Guice to inject implementations.
	 * @param ctx
	 * @param h
	 */
	public SparqlClientInitializer(SparqlClientHandler handler) {
		logger.debug("Intializer Ctor");
		logger.debug("Instance: {}", this);
		this.handler = handler;
		logger.debug("Handler: {}", handler);
	}

	@Override
	protected void initChannel(SocketChannel ch) throws Exception {
		ChannelPipeline p = ch.pipeline();

		// Enable HTTPS if necessary.
		//if (sslCtx != null) {
		//	p.addLast(sslCtx.newHandler(ch.alloc()));
		//}

		p.addLast(new HttpClientCodec());

		// Remove the following line if you don't want automatic content
		// decompression.
		//p.addLast(new HttpContentDecompressor());

		logger.debug("Pipeline Handler: {}", handler);
		p.addLast(handler);
	}

}
