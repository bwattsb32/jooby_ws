package com.dts_inc.z2c.module.vmware;

import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dts_inc.z2c.z2c_ws.provision.VmwareUtil;
import com.vmware.vim25.GuestProgramSpec;
import com.vmware.vim25.NamePasswordAuthentication;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.GuestAuthManager;
import com.vmware.vim25.mo.GuestOperationsManager;
import com.vmware.vim25.mo.GuestProcessManager;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.VirtualMachine;

public class RunProgram {

	static Logger logger = LoggerFactory.getLogger(RunProgram.class);

	private static String url = "https://172.31.3.160/sdk/vimService";
	private static String user = "wattsb@cmoa3s.com";
	private static String password = "CMOUser16";
	private static String cloneName = "sdk_clone_test_101";

	public static void runProgram(HashMap<String,Object> params, String command){
		String message = "";
		cloneName = (String) params.get("sys:vmHostName");
		// TODO Auto-generated method stub
		try
		{
			ServiceInstance si = new ServiceInstance(new URL(url), user, password, true);
			Folder rootFolder = si.getRootFolder();
			
		
		    
		    GuestOperationsManager gom = si.getGuestOperationsManager();
		    VirtualMachine vm = null;
		    try {
				vm = (VirtualMachine) si.getSearchIndex().findByInventoryPath("CMOA3SLAB/vm/"+ cloneName);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		    
		    if(!"guestToolsRunning".equals(vm.getGuest().toolsRunningStatus))
		    {
		    	logger.debug("The VMware Tools is not running in the Guest OS on VM: " + vm.getName());
		    	logger.debug("Exiting...");
		      return;
		    }
		    
		    GuestAuthManager gam = gom.getAuthManager(vm);
		    NamePasswordAuthentication npa = new NamePasswordAuthentication();
		    npa.username = "root";
		    npa.password = "REDhatdts01";
		 
		    GuestProgramSpec spec = new GuestProgramSpec();
		    spec.programPath = "/";
		    spec.arguments = command;
		 
		    GuestProcessManager gpm = gom.getProcessManager(vm);
		    long pid = gpm.startProgramInGuest(npa, spec);
		    logger.debug("pid: " + pid);
		 
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
		return;
	}
	
}
