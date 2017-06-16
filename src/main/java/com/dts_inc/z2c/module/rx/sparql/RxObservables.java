/**
 * 
 */
package com.dts_inc.z2c.module.rx.sparql;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dts_inc.z2c.z2c_ws.provision.LoadResource;

import rx.Observable;

/**
 * @author bergstromr
 *
 */
final public class RxObservables {
	static Logger logger = LoggerFactory.getLogger(RxObservables.class);
	
	/**
	 * Given a path to a script with substitution variables. Replace the values by looking up the variables in the provided 
	 * HashMap.
	 * @param scriptPath
	 * @param params
	 * @return An Observable that emits the finished script.
	 */
	public static Observable<String> parameterizeScript(String scriptPath, String substPrefix, HashMap<String,Object> params) {
		return Observable.from( ((HashMap<String,Object>)params).entrySet())
				.reduce( (String) null, (acc, param) -> { // Parameterize query
					if( null == acc) acc = LoadResource.getResourceAsString(scriptPath);
					logger.debug("Replacing [{}] with [{}]", param.getKey(), param.getValue());
					acc = ((String)acc).replaceAll("\\"+substPrefix+param.getKey(), param.getValue().toString());
					return acc;});
	}
	
	
}
