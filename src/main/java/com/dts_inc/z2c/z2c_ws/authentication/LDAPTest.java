package com.dts_inc.z2c.z2c_ws.authentication;

import java.util.Hashtable;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

public class LDAPTest
{
	public static void main(String[] args)
	{
		String username = "wattsb";
		String password = "CMOUser16";
		String base = "cn=users,cn=accounts,dc=cmoa3s,dc=com";
		String dn = "uid=" + username + "," + base;
		String ldapURL = "ldap://cmoe0lb1a5.cmoa3s.com:389";

		// Setup environment for authenticating
		
		Hashtable<String, String> environment = 
			new Hashtable<String, String>();
		environment.put(Context.INITIAL_CONTEXT_FACTORY,
				"com.sun.jndi.ldap.LdapCtxFactory");
		environment.put(Context.PROVIDER_URL, ldapURL);
		environment.put(Context.SECURITY_AUTHENTICATION, "simple");
		environment.put(Context.SECURITY_PRINCIPAL, dn);
		environment.put(Context.SECURITY_CREDENTIALS, password);

		try
		{
			DirContext authContext = 
				new InitialDirContext(environment);
			
			// user is authenticated
			System.out.println("User is authenticated");
			
		}
		catch (AuthenticationException ex)
		{
			
			// Authentication failed
			System.out.println("User is not authenticated");

		}
		catch (NamingException ex)
		{
			ex.printStackTrace();
		}
	}
}
