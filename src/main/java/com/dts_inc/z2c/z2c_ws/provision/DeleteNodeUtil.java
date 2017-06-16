package com.dts_inc.z2c.z2c_ws.provision;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Deprecated
public class DeleteNodeUtil {

	static Logger logger = LoggerFactory.getLogger(DeleteNodeUtil.class);
	
	static String newLine = "\n";
	static String deleteQuery = "";

	
	
	public DeleteNodeUtil(){
		
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
	public static String getDeleteQuery(String nodeUri) throws IOException, URISyntaxException {		
		String deleteQuery = LoadResource.getResourceAsString("/scripts/vnode_delete.sprql");
		deleteQuery = deleteQuery.replaceAll("\\?vmId", nodeUri);
	
		return deleteQuery;
	}



	/**
	 * 
	 * @param compact
	 * @param vmHostName
	 * @return 
	 * @throws URISyntaxException 
	 */
	public static void generateScript(HashMap<String,Object> compact, String vmHostName) {
	
		String hostName = "";
		
		try {
			//read in puppet create template file
			String content = LoadResource.getResourceAsString("/tmplt/vm_deletepp.tmplt");
			//String puppetHost = (String) compact.get("sys:puppetMasterHost");
			// 20161011 RAB Removed hard coded puppet master fqdn.
			//String puppetHostName = "cmoe3lpe01.cmoa3s.com";
			
			hostName = compact.get("sys:hostName").toString().toUpperCase();
			
			//replace tokens with actual values from query
			content = content.replaceAll(":nodeName",  (String) compact.get("sys:provisioningHost"));
			// 20161011 RAB Move to sys:vmDeletePath because of VMware requirement
			content = content.replaceAll(":vmDeletePath", (String) compact.get("sys:vmDeletePath"));
			content = content.replaceAll(":vmHostName",  hostName);
			content = content.replaceAll(":fqdn", (String) compact.get("sys:fqdn"));
			content = content.replaceAll(":scpservice", (String) compact.get("sys:scpServiceAcct"));
			// 20161011 RAB Removed hard coded puppet master fqdn.
			content = content.replaceAll(":puppetHostName", (String) compact.get("sys:puppetMasterHost") );

			//write puppet script file 
			File newTextFile = new File(createFileName(hostName));
			FileWriter fw = new FileWriter(newTextFile);
			fw.write(content);
			fw.close();
		} catch (IOException iox) {
			//do stuff with exception
			logger.debug("Could not load script file:",iox);
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
    	String fileName = "/tmp/deleteVM-"+vmHostName.toUpperCase()+".pp";
    	return fileName;
    }
	
}
