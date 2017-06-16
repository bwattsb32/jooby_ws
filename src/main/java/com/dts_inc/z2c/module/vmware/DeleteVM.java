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
import com.vmware.vim25.mo.GuestOperationsManager;
import com.vmware.vim25.mo.GuestProcessManager;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;

public class DeleteVM {
	
	static Logger logger = LoggerFactory.getLogger(DeleteVM.class);

	private static String url = "https://172.31.3.160/sdk/vimService";
	private static String user = "wattsb@cmoa3s.com";
	private static String password = "CMOUser16";

	private static String vmPathName = "";


	public static void deleteVM(HashMap<String,Object> params){
		String message = "";
		//vmPathName = vmPath.concat(vmName);
		vmPathName = ((HashMap<String,Object>) params).get("sys:vmDeletePath").toString() + 
				((HashMap<String,Object>) params).get("sys:hostName").toString().toLowerCase();
		

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
				Task task = null;
				String status = "";
				
				task = vm.powerOffVM_Task();
				logger.debug("Powering off the vm. It might take a while. Please wait for the result ...");

				status =   task.waitForMe();
				if(status==Task.SUCCESS)
				{
					logger.debug("Virtual Machine powered off successfully.");
				}
				else
				{
					logger.debug("Failure -: Virtual Machine cannot be powered off.");
				}
				
				pause(10, "seconds to power off VM");
				
				task = vm.destroy_Task();
				logger.debug("Launching the VM delete task. It might take a while. Please wait for the result ...");

				status =   task.waitForMe();
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
	
	
	public static void configureVM(HashMap<String,Object> params){
		
		logger.debug("ConfigureVM has params: " + params.toString());
		
		vmPathName = ((HashMap<String,Object>) params).get("sys:vmDeletePath").toString() + 
				((HashMap<String,Object>) params).get("sys:hostName").toString().toLowerCase();

		logger.debug("Attempting to configure VM with name:  " + vmPathName + " by issuing commands...");
		

		
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

			
			pause(10, "seconds to issue commands");
			
			if(!"guestToolsRunning".equals(vm.getGuest().toolsRunningStatus))
			{
				logger.debug("The VMware Tools is not running in the Guest OS on VM: " + vm.getName());

			}
			else {
				logger.debug("The VMware Tools is running in the Guest OS on VM: " + vm.getName());
			}
			
			

			String command = " sudo subscription-manager unregister";
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
	    namePasswordAuthentication.setUsername("root");
	    namePasswordAuthentication.setPassword("REDhatdts01");

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
	
	public static void pause(int i, String reason){
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
