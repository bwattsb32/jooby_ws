/*************************************************************************
 * 
 * DYNAMIC TECHNOLOGY SYSTEMS, INCORPORATED -- CONFIDENTIAL
 * __________________
 * 
 *  [2015-16] Dynamic Technology Systems, Inc. (DTS-INC)
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author wattsb
 * 20161115 RAB Deprecated by use of observables
 */
@Deprecated
public class CreateNodeUtil {

	static Logger logger = LoggerFactory.getLogger(CreateNodeUtil.class);
	
	static String newLine = "\n";
	static String createQuery = "";
	static String insertQuery = "";
	
	
	public CreateNodeUtil(){
		
	}

	/**
	 * 
	 * @param templateUri
	 * @param hostUri
	 * @param environmentUri
	 * @return
	 * @throws IOException
	 * @throws URISyntaxException 
	 */
	public static String getCreateQuery(String templateUri, String hostUri, String environmentUri, String networkUri) throws URISyntaxException, IOException {
		
		String templateId = templateUri;
		String hostId = hostUri;
		String environmentId = environmentUri;
		String networkId = networkUri;
		
		String createQuery = LoadResource.getResourceAsString("/scripts/vnode_create.sprql");
		createQuery = createQuery.replaceAll("\\%templateId", templateId);
		createQuery = createQuery.replaceAll("\\%hostId", hostId);
		createQuery = createQuery.replaceAll("\\%environmentId", environmentId);
		createQuery = createQuery.replaceAll("\\%networkId", networkId);
		
		return createQuery;
	}

	/**
	 * 
	 * @param vmHostName
	 * @param templateId
	 * @param hostId
	 * @param environmentId
	 * @return
	 * @throws IOException
	 * @throws URISyntaxException 
	 */
	public static String getInsertQuery(String vmHostName, String templateId, String hostId, String environmentId, String networkId) throws IOException, URISyntaxException{
		String uuid = IdGenerator.generateUUID();
		String domainSfx = "cmoa3s.com";
		String status = "pending deployment";
		//String networkId = "http://z2c.dts-inc.com/id/85efc9d8-5e61-4462-a15c-4fad0a664704";
		
		String insertQuery = LoadResource.getResourceAsString("/scripts/vnode_insert.sprql");
		insertQuery = insertQuery.replaceAll("\\%uuId", uuid);
		insertQuery = insertQuery.replaceAll("\\%vmHostName", vmHostName);
		insertQuery = insertQuery.replaceAll("\\%templateId", templateId);
		insertQuery = insertQuery.replaceAll("\\%hostId", hostId);
		insertQuery = insertQuery.replaceAll("\\%networkId", networkId);
		insertQuery = insertQuery.replaceAll("\\%environmentId", environmentId);
		insertQuery = insertQuery.replaceAll("\\%domainSfx", domainSfx);
		insertQuery = insertQuery.replaceAll("\\%status", status);
				
		return insertQuery;
	};

	/**
	 * 
	 * @param compact
	 * @param vmHostName
	 * @throws URISyntaxException 
	 */
	public static void generateScript(HashMap<String,Object> compact, String vmHostName) {
		
		String serverName = vmHostName;
	
		try {
			//read in puppet create template file
			String content = LoadResource.getResourceAsString("/tmplt/vm_createpp.tmplt");
						
			//replace tokens with actual values from query
			content = content.replaceAll(":nodeName",  (String) compact.get("sys:provisioningHost"));
			content = content.replaceAll(":vmPath", (String) compact.get("sys:vmPath"));
			content = content.replaceAll(":vmHostName",  serverName.toUpperCase());
			content = content.replaceAll(":vmTemplateName",  (String) compact.get("sys:vmTemplateName"));
			content = content.replaceAll(":vmHostIP", (String) compact.get("tag:ipAddress"));
			content = content.replaceAll(":vmUser", (String) compact.get("sys:defaultTemplateUser"));
			content = content.replaceAll(":vmPassword", (String) compact.get("sys:defaultTemplatePassword"));
			content = content.replaceAll(":vmCpu", (String) compact.get("tag:cpus"));
			content = content.replaceAll(":vmMem", (String) compact.get("tag:memory"));
			content = content.replaceAll(":vmClient", serverName.toLowerCase());
			content = content.replaceAll(":ethName", (String) compact.get("sys:adminVirtualNic"));
			content = content.replaceAll(":rhnUser", (String) compact.get("sys:rhnUser"));
			content = content.replaceAll(":rhnPassword", (String) compact.get("sys:rhnPassword"));
			content = content.replaceAll(":puppetHost", (String) compact.get("sys:puppetMasterHost") );

			//write puppet script file 
			File newTextFile = new File(createFileName(vmHostName));
			FileWriter fw = new FileWriter(newTextFile);
			fw.write(content);
			fw.close();
		} catch (IOException iox) {
			//do stuff with exception
			logger.debug("Could not load query file:",iox);
		}
	};
	
	/**
	 * 
	 * @param vmHostName
	 */
	public static void executeScript(String vmHostName) {
		String[] commands = {"/usr/local/bin/puppet", "apply", createFileName(vmHostName)};
		
		try {
			new CommandOutput(commands);
			logger.debug("..Executing: " + commands.toString());
		} 
		catch (Exception e) {
			logger.debug(e.toString());
		}
		
	}
	

	/**
	 * 
	 * @param vmHostName
	 * @return
	 */
    private static String createFileName(String vmHostName){
    	String fileName = "/tmp/createVM-"+vmHostName.toUpperCase()+".pp";
    	return fileName;
    }
	
}
