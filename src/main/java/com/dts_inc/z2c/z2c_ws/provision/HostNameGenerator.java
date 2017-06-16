package com.dts_inc.z2c.z2c_ws.provision;

import java.util.Map;

/**
 * Interface for all hostname generators accepts a generic key value pair context.
 * @author bergstromr
 *
 */
public interface HostNameGenerator {
	public String generateHostname(Map<String,Object> context) throws InvalidGeneratorContext;
}
