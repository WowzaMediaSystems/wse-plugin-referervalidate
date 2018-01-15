/*
 * This code and all components (c) Copyright 2006 - 2018, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin;

public class SessionInfo implements Comparable<SessionInfo>
{
	private String appName = "";
	private String appInstName = "";
	private String streamName = "";
	private String ipAddress = "";
	private String queryStr = "";
	private String referer = "";
	private long expireTime = -1;
	
	public String getAppName()
	{
		return appName;
	}

	public void setAppName(String appName)
	{
		this.appName = appName;
	}

	public String getAppInstName()
	{
		return appInstName;
	}

	public void setAppInstName(String appInstName)
	{
		this.appInstName = appInstName;
	}

	public String getStreamName()
	{
		return streamName;
	}

	public void setStreamName(String streamName)
	{
		this.streamName = streamName;
	}

	public String getIpAddress()
	{
		return ipAddress;
	}

	public void setIpAddress(String ipAddress)
	{
		this.ipAddress = ipAddress;
	}

	public String getQueryStr()
	{
		return queryStr;
	}

	public void setQueryStr(String queryStr)
	{
		this.queryStr = queryStr;
	}

	public String getReferer()
	{
		return referer;
	}

	public void setReferer(String referrer)
	{
		this.referer = referrer;
	}

	public long getExpireTime()
	{
		return expireTime;
	}

	public void setExpireTime(long expireTime)
	{
		this.expireTime = expireTime;
	}

	public boolean equals(Object o)
	{
		if(o == null || o.getClass() != this.getClass()) return false;
		if(o == this) return true;
		
		SessionInfo other = (SessionInfo)o;
		if (this.appName.equals(other.appName) && this.appInstName.equals(other.appInstName) && this.streamName.equals(other.streamName) && this.ipAddress.equals(other.ipAddress) && this.queryStr.equals(other.queryStr))
			return true;
		return false;
	}
	
	public String toString()
	{
		return "appName: "+appName+", appInstName: "+appInstName+", streamName: "+streamName+", ipAddress: "+ipAddress+", queryStr: "+queryStr;
	}
	
	public int hashCode()
	{
		return this.toString().hashCode();
	}

	public int compareTo(SessionInfo o)
	{
		if (this.expireTime == o.expireTime)
			return 0;
		return this.expireTime > o.expireTime ? 1:-1;
	}
	
	
}
