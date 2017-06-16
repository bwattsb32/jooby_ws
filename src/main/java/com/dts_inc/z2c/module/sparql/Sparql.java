package com.dts_inc.z2c.module.sparql;

import org.jooby.Env;
import org.jooby.Jooby;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.inject.Binder;
import com.typesafe.config.Config;

/**
 * Exposes Sparql repository manager
 * @author bergstromr
 *
 */
public class Sparql implements Jooby.Module {
	static Logger logger = LoggerFactory.getLogger(Sparql.class);
	private SparqlProvider provider;
	
	@Override
	public void configure(Env env, Config conf, Binder binder) {
		logger.debug("Configuring module.");
		provider = new SparqlProvider(conf);
		
		// Start and stop events linked to lifecycle methods
		env.onStart(provider::start);
		env.onStop(provider::stop);
		
		
		// bind the provider to the class for injection
		binder.bind(SparqlClient.class).toProvider(provider).asEagerSingleton();
	}

}
