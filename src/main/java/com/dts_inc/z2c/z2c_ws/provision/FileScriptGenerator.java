/**
 * 
 */
package com.dts_inc.z2c.z2c_ws.provision;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dts_inc.z2c.z2c_ws.App;

/**
 * Generates provision scripts and writes them to temporary files for
 * execution
 * @author bergstromr
 *
 */
public class FileScriptGenerator implements ScriptGenerator {
	static Logger logger = LoggerFactory.getLogger(FileScriptGenerator.class);
	static final String template = 
			     "	vsphere_vm { \"${vm_path}\":\n"
                +"        	ensure          => running,\n"
                +"			source          => \"${vm_source}\\n"
                +"			resource_pool   => \"${vm_host}\",\n"
                +"			create_command  => {\n"
                +"        		command                 => '/bin/sed',"
                +"        		arguments               => \"-i '/IPADDR/c\\IPADDR=${client_ip}' /etc/sysconfig/network-scripts/${eth_name} && /bin/sed -i '/HOSTNAME/c\\HOSTNAME=${client_host}' /etc/sysconfig/network && /bin/hostname ${client_host} && /sbin/service network restart && /usr/bin/subscription-manager register --username ${rhn_user} --password ${rhn_pw} --auto-attach && /usr/bin/curl -k https://${puppet_host}:8140/packages/current/install.bash --connect-timeout 120 --max-time 600 | /bin/bash >/tmp/agent.install.log 2>&1\",\n"
                +"        		working_directory       => '/',\n"
                +"        		user                    => \"${vm_user}\",\n"
                +"        		password                => \"${vm_pw}\",\n"
                +"           }\n"
                +"	}";
	/* (non-Javadoc)
	 * @see com.dts_inc.z2c.z2c_ws.provision.ScriptGenerator#generate(java.util.HashMap)
	 */
	@Override
	public String generate(HashMap<String, String> config) {
		String retval = null;
		StringBuffer buffer = new StringBuffer("node '");
		buffer.append(config.get("client_host")).append("' {\n");
		// Generate variables using keys prefixed with $
		for(Entry<String,String> entry : config.entrySet()) {
			buffer.append("    $").append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
		}
		// Append creation commmand template
		buffer.append(template).append("\n}\n");
		
		// Open tempfile
		try {
			Path tempPath = Files.createTempFile("script", ".pp");
			retval = tempPath.toString();
			logger.debug("Opening temp file: {}", retval);
			try (FileWriter temp = new FileWriter(retval))
			{
				// Write file
				temp.write(buffer.toString());
			}
		} catch (IOException e) {
			logger.error("Error opening temp file: ",e);
		}
		
		// return full path to caller.
		return retval;	
	}

}
