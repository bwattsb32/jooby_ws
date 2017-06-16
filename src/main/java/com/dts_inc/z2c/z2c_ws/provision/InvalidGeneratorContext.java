package com.dts_inc.z2c.z2c_ws.provision;

public class InvalidGeneratorContext extends Exception {
	private static final long serialVersionUID = 1L;

	public InvalidGeneratorContext(String message, String context) {
		super(message+": "+context);
	}
}
