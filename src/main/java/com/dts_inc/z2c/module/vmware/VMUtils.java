package com.dts_inc.z2c.module.vmware;

import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.VirtualMachine;

public class VMUtils {
	static Logger logger = LoggerFactory.getLogger(VMUtils.class);
	
	private static String url = "https://172.31.3.160/sdk/vimService"; //defaults for dev env only
	private static String user = "wattsb@cmoa3s.com"; //defaults for dev env only
	private static String password = "CMOUser16"; //defaults for dev env only


	private static String guestvmPath = "CMOA3SLAB/vm/"; //defaults for dev env only
	private static String cloneName = "sdk_clone_test_101"; //defaults for dev env only
	private static String dataCenterName = "CMOA3SLAB"; //defaults for dev env only

	


	public static String getIpAddress(HashMap<String,Object> params){
		String ipaddress = null;
		cloneName = (String) params.get("sys:vmHostName");
		url = (String) params.get("sys:vcenterUrl");
		user = (String) params.get("sys:vcenterUser");
		password = (String) params.get("sys:vcenterPassword");
		dataCenterName = (String) params.get("sys:vcenterDataCenterName");
		guestvmPath = dataCenterName + "/vm/";
		
		
		try
		{
			ServiceInstance si = new ServiceInstance(new URL(url), user, password, true);
			VirtualMachine vm = null;

			try {
				vm = (VirtualMachine) si.getSearchIndex().findByInventoryPath(guestvmPath + cloneName);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			if(!"guestToolsRunning".equals(vm.getGuest().toolsRunningStatus))
			{
				logger.debug("The VMware Tools is not running in the Guest OS on VM: " + vm.getName());

			}
			else {
				logger.debug("The VMware Tools is running in the Guest OS on VM: " + vm.getName());
			}

			ipaddress = vm.getGuest().ipAddress;
			logger.debug("VM IPADDRESS is : " + ipaddress);

			si.getServerConnection().logout();

		}
		catch(RemoteException re)
		{
			re.printStackTrace();
		}
		catch(MalformedURLException mue)
		{
			mue.printStackTrace();
		}

		return ipaddress;
	}

}
