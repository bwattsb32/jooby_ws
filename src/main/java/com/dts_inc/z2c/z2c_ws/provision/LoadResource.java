package com.dts_inc.z2c.z2c_ws.provision;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoadResource {
	static Logger logger = LoggerFactory.getLogger(LoadResource.class);
	
	public static String getResourceAsString(String resource) {
		String retval = null;
		try (BufferedReader buffer = new BufferedReader(new InputStreamReader(LoadResource.class.getResourceAsStream(resource)))) {
            retval =  buffer.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
			logger.error("Error load resource [{}]", resource, e );
		}
		return retval;
	}
}
