package com.dts_inc.z2c.z2c_ws.provision;

import java.util.HashMap;

/**
 * Interface for script generators.
 * @author bergstromr
 *
 */
public interface ScriptGenerator {
	/**
	 * Given a set of parameters generate a puppet script with 1 to n node declarations.
	 * @param config HashMap with substitution parameters for the script template
	 * @return A path or URI to the generated script
	 */
	public String generate(HashMap<String,String> config);
}
