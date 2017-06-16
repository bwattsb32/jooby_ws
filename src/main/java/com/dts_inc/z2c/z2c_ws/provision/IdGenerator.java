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

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;

/**
 * @author wattsb
 *
 */
public class IdGenerator {

	private static String[] Beginning = { "CMO" };
	private static String[] Middle = { "e1", "e2", "e3", "p1", "p2", "p3", "t1", "t2", "t3" };
	private static String[] End = { "rhel" };

	private static Random rand = new Random();

	public static String generateName() {

		return Beginning[rand.nextInt(Beginning.length)] + 
				Middle[rand.nextInt(Middle.length)]+
				End[rand.nextInt(End.length)]+ "-" +
				UUID.randomUUID().toString().substring(0, 4);

	}

	
	public static String generateUUID() {
		return UUID.randomUUID().toString();
	}
	
	public static String generateTimeStamp() {
		TimeZone tz = TimeZone.getTimeZone("UTC");
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
		df.setTimeZone(tz);
		String nowAsISO = df.format(new Date());
		return nowAsISO;
	}


}
