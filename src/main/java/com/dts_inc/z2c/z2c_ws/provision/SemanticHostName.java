/**
 * 
 */
package com.dts_inc.z2c.z2c_ws.provision;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates a semantic (parts of name have meaning) hostname given a context.
 * 
 * @author bergstromr
 *
 */
public class SemanticHostName implements HostNameGenerator {
	static String ENVIRONMENT_ROLE = "sys:environmentRole";
	static Logger logger = LoggerFactory.getLogger(SemanticHostName.class);
	/**
	 * 
	 * @see com.dts_inc.z2c.z2c_ws.provision.HostNameGenerator#generateHostname(java.util.Map)
	 * @param content {"sys:environmentRole" ["dev" | "test" | "prod" | "admin"]}
	 * @throws InvalidGeneratorContext 
	 */
	@Override
	public String generateHostname(Map<String, Object> params) throws InvalidGeneratorContext {
		StringBuffer retval = new StringBuffer("cmo");
		String role;
		String puppetCode = null;
		
		logger.debug("Context for hostname: {}", params);
		if( null == (role = params.get(ENVIRONMENT_ROLE).toString())) {
			throw new InvalidGeneratorContext("Missing context key", ENVIRONMENT_ROLE);
		}
		logger.debug("Role for Hostname: {}", role);
		// Based on the role, determine the prefix
		switch (role) {
			case "admin" : 
				puppetCode = "e0";
				break;
			case "dev" : 
				puppetCode = "e3";
				break;
			case "test" : 
				puppetCode = "e2";
				break;
			case "prod" : 
				puppetCode = "e1";
				break;
		}
		if( null == puppetCode) throw new InvalidGeneratorContext("Unsupported puppet environemnt", role);
		retval.append(puppetCode);
		retval.append("l");
		retval.append(UUID.randomUUID().toString().substring(0, 4));
		
		return retval.toString();
	}	 

}
