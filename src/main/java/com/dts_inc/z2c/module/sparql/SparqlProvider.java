package com.dts_inc.z2c.module.sparql;

import javax.inject.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/** 
 * Provides lifecycle for a remote SPARQL connection.
 * @author bergstromr
 *
 */
public class SparqlProvider implements Provider<SparqlClient> {
	private static Logger logger = LoggerFactory.getLogger(SparqlProvider.class.getName());
	static final String BLAZEGRAPH_URL = "blazegraph.url";
	private String repoUrl;
	
	public SparqlProvider(Config conf) {
		repoUrl = conf.getString(BLAZEGRAPH_URL);
	}
	
	/**
	 * Return the constructed object. Do not reuse.
	 * Caller must close.
	 */
	@Override
	public SparqlClient get() {
		SparqlClient retval = new SparqlClient(repoUrl);
		logger.debug("Providing client: {}", retval);
		return retval;
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
