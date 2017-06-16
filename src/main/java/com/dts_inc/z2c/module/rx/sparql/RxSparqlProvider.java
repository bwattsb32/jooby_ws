/**
 * 
 */
package com.dts_inc.z2c.module.rx.sparql;

import javax.inject.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dts_inc.z2c.module.rx.sparql.RxSparqlClient;
import com.typesafe.config.Config;

/**
 * @author bergstromr
 *
 */
public class RxSparqlProvider implements Provider<RxSparqlClient> {
	private static Logger logger = LoggerFactory.getLogger(RxSparqlProvider.class.getName());
	static final String BLAZEGRAPH_URL = "blazegraph.url";
	private String repoUrl;
	
	public RxSparqlProvider(Config conf) {
		repoUrl = conf.getString(BLAZEGRAPH_URL);
	}

	@Override
	public RxSparqlClient get() {
		return new RxSparqlClient(repoUrl);
	}
	
	/**
	 * Start the connection
	 */
	public void start() {
		
	}
	
	/**
	 * Stop the repository.
	 */
	public void stop() {
		logger.info("Sparql Module shutdown.");
	}

}
