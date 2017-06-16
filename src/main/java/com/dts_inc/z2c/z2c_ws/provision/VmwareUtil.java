package com.dts_inc.z2c.z2c_ws.provision;

import com.vmware.vim25.mo.Datacenter;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.GuestAuthManager;
import com.vmware.vim25.mo.GuestOperationsManager;
import com.vmware.vim25.mo.GuestProcessManager;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ResourcePool;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.dts_inc.z2c.z2c_ws.App;
import com.vmware.vim25.*;

import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VmwareUtil {
	static Logger logger = LoggerFactory.getLogger(VmwareUtil.class);

	private static String url = "https://172.31.3.160/sdk/vimService";
	private static String user = "wattsb@cmoa3s.com";
	private static String password = "CMOUser16";

	private static String vmPathName = "CMOA3SLAB/vm/RHEL6TEST";
	private static String cloneName = "sdk_clone_test_101";
	private static String dataCenterName = "CMOA3SLAB";
	

	public static void runProgram(String vmPath, String vmName, String command){
		String message = "";
		vmPathName = vmPath.concat(vmName);
		
		// TODO Auto-generated method stub
		try
		{
			ServiceInstance si = new ServiceInstance(new URL(url), user, password, true);
			Folder rootFolder = si.getRootFolder();
			
			ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("VirtualMachine");
		    if(mes==null || mes.length ==0)
		    {
		      return;
		    }
		    
		    GuestOperationsManager gom = si.getGuestOperationsManager();
		    VirtualMachine vm = (VirtualMachine) mes[0];
		    
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

	
	
	public static void deleteVM(String vmPath, String vmName){
		String message = "";
		//vmPathName = vmPath.concat(vmName);
		vmPathName = "CMOA3SLAB/vm/sdk_test_VM_002";

		// TODO Auto-generated method stub
		try
		{
			ServiceInstance si = new ServiceInstance(new URL(url), user, password, true);
			VirtualMachine vm = null;
			try {
				vm = (VirtualMachine) si.getSearchIndex().findByInventoryPath(vmPathName);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			if(vm==null )
			{
				logger.debug("VirtualMachine or Datacenter path is NOT correct. Pls double check. ");
				return ;
			}
			else {
				Task task = vm.destroy_Task();
				logger.debug("Launching the VM delete task. It might take a while. Please wait for the result ...");

				String status =   task.waitForMe();
				if(status==Task.SUCCESS)
				{
					logger.debug("Virtual Machine deleted successfully.");
				}
				else
				{
					logger.debug("Failure -: Virtual Machine cannot be deleted.");
				}
			}

		}
		catch(RemoteException re)
		{
			re.printStackTrace();
		}
		catch(MalformedURLException mue)
		{
			mue.printStackTrace();
		}
		return ;
	}

}
