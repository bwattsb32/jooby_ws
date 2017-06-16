package com.dts_inc.z2c.module.rx.sparql;

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
public class RxSparql implements Jooby.Module {
	static Logger logger = LoggerFactory.getLogger(RxSparql.class);
	private RxSparqlProvider provider;
	
	@Override
	public void configure(Env env, Config conf, Binder binder) {
		logger.debug("Configuring module.");
		provider = new RxSparqlProvider(conf);
		
		// Start and stop events linked to lifecycle methods
		env.onStart(provider::start);
		env.onStop(provider::stop);
		
		
		// bind the provider to the class for injection
		binder.bind(RxSparqlClient.class).toProvider(provider).asEagerSingleton();
	}

}
