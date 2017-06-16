/*************************************************************************
 * 
 * DYNAMIC TECHNOLOGY SYSTEMS, INCORPORATED -- CONFIDENTIAL
 * __________________
 * 
 *  [2015] Dynamic Technology Systems, Inc. (DTS-INC)
 *  All Rights Reserved.
 * 
 * NOTICE:  All information contained herein is, and remains
 * the property of Dynamic Technology Systems Incorporated and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Dynamic Technology Systems Incorporated
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Dynamic Technology Systems Incorporated.
 */
package com.dts_inc.z2c.z2c_ws.provision;


import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author wattsb
 *
 */
public class CommandOutput {


	static Logger logger = LoggerFactory.getLogger(CommandOutput.class);
	
	public CommandOutput(String[] commands) throws InterruptedException, IOException{

		ProcessBuilder pb = new ProcessBuilder(commands);

		Process process = pb.start();
		IOThreadHandler outputHandler = new IOThreadHandler(
				process.getInputStream());
		outputHandler.start();
		process.waitFor();
		logger.debug(outputHandler.getOutput().toString());
	}

	private static class IOThreadHandler extends Thread {
		private InputStream inputStream;
		private StringBuilder output = new StringBuilder();

		IOThreadHandler(InputStream inputStream) {
			this.inputStream = inputStream;
		}

		public void run() {
			Scanner br = null;
			try {
				br = new Scanner(new InputStreamReader(inputStream));
				String line = null;
				while (br.hasNextLine()) {
					line = br.nextLine();
					output.append(line
							+ System.getProperty("line.separator"));
				}
			} finally {
				br.close();
			}
		}

		public StringBuilder getOutput() {
			return output;
		}
	}
}