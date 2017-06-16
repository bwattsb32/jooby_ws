package com.dts_inc.z2c.module.vmware;

import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.Date;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vmware.vim25.FileFault;
import com.vmware.vim25.GuestOperationsFault;
import com.vmware.vim25.GuestProgramSpec;
import com.vmware.vim25.InvalidState;
import com.vmware.vim25.NamePasswordAuthentication;
import com.vmware.vim25.RuntimeFault;
import com.vmware.vim25.TaskInProgress;
import com.vmware.vim25.VirtualMachineCloneSpec;
import com.vmware.vim25.VirtualMachineRelocateSpec;
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

public class CreateVM {

	static Logger logger = LoggerFactory.getLogger(CreateVM.class);

	private static String url = "https://172.31.3.160/sdk/vimService"; //defaults for dev env only
	private static String user = "wattsb@cmoa3s.com"; //defaults for dev env only
	private static String password = "CMOUser16"; //defaults for dev env only

	private static String vmPathName = "CMOA3SLAB/vm/RHEL6TEST"; //defaults for dev env only
	private static String guestvmPath = "CMOA3SLAB/vm/"; //defaults for dev env only
	private static String cloneName = "sdk_clone_test_101"; //defaults for dev env only
	private static String dataCenterName = "CMOA3SLAB"; //defaults for dev env only
	private static String resourcePoolName = "TEST"; //defaults for dev env only
	
	private static String templateuser = "root"; //defaults for dev env only
	private static String templatepassword = "REDhatdts01"; //defaults for dev env only

	public static void createVM(HashMap<String,Object> params){

		cloneName = (String) params.get("sys:vmHostName");
		url = (String) params.get("sys:vcenterUrl");
		user = (String) params.get("sys:vcenterUser");
		password = (String) params.get("sys:vcenterPassword");
		vmPathName = (String) params.get("sys:vcenterDefaultTemplatePath");
		dataCenterName = (String) params.get("sys:vcenterDataCenterName");
		resourcePoolName = (String) params.get("sys:vcenterDefaultResourcePool");
		templateuser = (String) params.get("sys:defaultTemplateUser");
		templatepassword = (String) params.get("sys:defaultTemplatePassword");
		guestvmPath = dataCenterName + "/vm/";
		
		logger.debug("Attempting to create VM with name:  " + cloneName);
		logger.debug("VCenter URL:  " + url);
		logger.debug("VCenter username:  " + user);
		logger.debug("VM PathName:  " + vmPathName);
		logger.debug("VCenter datacenter name:  " + dataCenterName);
		logger.debug("Resource Pool:  " + resourcePoolName);
		logger.debug("Template Username:  " + templateuser);
		logger.debug("Guest VM Path:  " + guestvmPath);
		

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
			Datacenter dc = (Datacenter) si.getSearchIndex().findByInventoryPath(dataCenterName);

			ResourcePool rp = (ResourcePool) new InventoryNavigator(
					dc).searchManagedEntity("ResourcePool", resourcePoolName);

			if(vm==null || dc ==null)
			{
				logger.debug("VirtualMachine or Datacenter path is NOT correct. Pls double check. ");
				return;
			}


			Folder vmFolder = dc.getVmFolder();

			VirtualMachineCloneSpec cloneSpec = new VirtualMachineCloneSpec();
			VirtualMachineRelocateSpec relocSpec = new VirtualMachineRelocateSpec();

			relocSpec.setPool(rp.getMOR());

			cloneSpec.setLocation(relocSpec);

			cloneSpec.setPowerOn(true);
			cloneSpec.setTemplate(false);


			Task task = vm.cloneVM_Task(vmFolder, cloneName, cloneSpec);
			logger.debug("Launching the VM clone task. It might take a while. Please wait for the result ...");

			String status =   task.waitForMe();
			if(status==Task.SUCCESS)
			{
				logger.debug("Virtual Machine successfully cloned.");


			}
			else
			{
				logger.debug("Failure -: Virtual Machine cannot be cloned.");
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


	}



	public static void configureVM(HashMap<String,Object> params){
		cloneName = (String) params.get("sys:vmHostName");
		String ethernetName = (String) params.get("sys:adminVirtualNic");
		String rhUser = (String) params.get("sys:rhnUser");
		String rhUserPw = (String) params.get("sys:rhnPassword");
		String puppetMasterHost = (String) params.get("sys:puppetMasterHost");

		logger.debug("Attempting to configure VM with name:  " + cloneName + " by issuing commands...");
		
		pause(30, "seconds to issue commands");
		
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

			String command = " /bin/sed -i '/HOSTNAME/c\\HOSTNAME=xVMCLIENTx' /etc/sysconfig/network"
					.replaceAll("xVMCLIENTx", cloneName);
			guestCommand(si, vm, command);
			
			command = " /bin/sed -i '/DHCP_HOSTNAME/c\\DHCP_HOSTNAME=xVMCLIENTx' /etc/sysconfig/network"
					.replaceAll("xVMCLIENTx", cloneName);
			guestCommand(si, vm, command);
			
			command = " /bin/hostname xVMCLIENTx".replaceAll("xVMCLIENTx", cloneName);;
			guestCommand(si, vm, command);
			
			command = " /bin/sed -i '/BOOTPROTO/c\\BOOTPROTO=dhcp' /etc/sysconfig/network-scripts/xETH_NAMEx"
					.replaceAll("xETH_NAMEx", ethernetName);
			guestCommand(si, vm, command);
			
			command = " /sbin/service network restart";
			guestCommand(si, vm, command);
			
			pause(30, "seconds for restart before registering OS and puppet");
			
			command = " /usr/bin/subscription-manager register --username xRHUSERx --password xRHPASSWDx --auto-attach"
					.replaceAll("xRHUSERx", rhUser)
					.replaceAll("xRHPASSWDx", rhUserPw);
			guestCommand(si, vm, command);
			
			command = " /usr/bin/curl -k https://xPUPPETMASTERHOSTx:8140/packages/current/install.bash --connect-timeout 120 --max-time 600 | /bin/bash >/tmp/agent.install.log 2>&1"
					.replaceAll("xPUPPETMASTERHOSTx", puppetMasterHost);
			guestCommand(si, vm, command);
			
		    
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

	
	private static void guestCommand(ServiceInstance si, VirtualMachine vm, String command) throws GuestOperationsFault, InvalidState, TaskInProgress, FileFault, RuntimeFault, RemoteException{
		String script = "#!/bin/bash \n ";
		
		
		// Authentication properties
	    NamePasswordAuthentication namePasswordAuthentication = new NamePasswordAuthentication();
	    namePasswordAuthentication.setUsername(templateuser);
	    namePasswordAuthentication.setPassword(templatepassword);

	    // Execution properties
	    GuestProgramSpec guestProgramSpec = new GuestProgramSpec();
	    guestProgramSpec.setProgramPath("/");
	    guestProgramSpec.setArguments(script.concat(command));

	    // Run script on remote host
	    GuestOperationsManager guestOperationsManager = si.getGuestOperationsManager();
	    GuestProcessManager guestProcessManager = guestOperationsManager.getProcessManager(vm);
	    
	    long pid = guestProcessManager.startProgramInGuest(namePasswordAuthentication, guestProgramSpec);	    
	    logger.debug(command + " pid: " + pid);
	}
	
	private static void pause(int i, String reason){
		logger.debug("Waiting " + i + " " +  reason + " ..." );
		try {

			//sleep i seconds
			Thread.sleep(i * 1000);

			logger.debug("Waiting is over..." + new Date());

		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
