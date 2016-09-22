/**
 * Wowza server software and all components Copyright 2006 - 2016, Wowza Media Systems, LLC, licensed pursuant to the Wowza Media Software End User License Agreement.
 */
package com.wowza.wms.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;

import javax.activation.MimetypesFileTypeMap;

import com.wowza.util.Base64;
import com.wowza.util.StringUtils;
import com.wowza.util.SystemUtils;
import com.wowza.wms.application.ApplicationInstance;
import com.wowza.wms.application.IApplication;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.client.IClient;
import com.wowza.wms.http.HTTProvider2Base;
import com.wowza.wms.http.IHTTPRequest;
import com.wowza.wms.http.IHTTPResponse;
import com.wowza.wms.httpstreamer.cupertinostreaming.httpstreamer.CupertinoStreamingURL;
import com.wowza.wms.httpstreamer.model.IHTTPStreamerSession;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.plugin.RefererValidate;
import com.wowza.wms.plugin.SessionInfo;
import com.wowza.wms.rtp.model.RTPSession;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.vhost.HostPort;
import com.wowza.wms.vhost.IVHost;

import edu.emory.mathcs.backport.java.util.concurrent.locks.WMSReadWriteLock;

public class HttpRefererValidate extends HTTProvider2Base
{

	public static final String CLASS_NAME = "HttpRefererValidate";
	public static final String PROP_HTTP_REFERER_VALIDATE = "httpRefererValidate";

	private static final String PIXEL_B64 = "R0lGODlhAQABAPAAAAAAAAAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==";
	private static final byte[] PIXEL_BYTES = Base64.decode(PIXEL_B64);

	private HttpRefererValidate validator;

	private List<SessionInfo> sessionInfoList;
	private Object lock;
	private WMSLogger logger;
	private Timer timer;
	private long sessionCheckInterval = 60000l;
	private boolean debugLog = false;

	public void onBind(IVHost vhost, HostPort hostPort)
	{
		super.onBind(vhost, hostPort);

		logger = WMSLoggerFactory.getLoggerObj(vhost);
		this.sessionCheckInterval = vhost.getProperties().getPropertyLong(PROP_HTTP_REFERER_VALIDATE + "SessionCheckInterval", this.sessionCheckInterval);
		this.debugLog = vhost.getProperties().getPropertyBoolean(PROP_HTTP_REFERER_VALIDATE + "DebugLog", debugLog);
		if (logger.isDebugEnabled())
			debugLog = true;

		if (vhost.getProperties().getProperty(PROP_HTTP_REFERER_VALIDATE) != null)
		{
			validator = (HttpRefererValidate)vhost.getProperties().getProperty(PROP_HTTP_REFERER_VALIDATE);
			if (debugLog)
			{
				logger.info(CLASS_NAME + "HTTP vhost validator [" + validator + "] already added", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
			}
		}
		else
		{
			if (debugLog)
			{
				logger.info(CLASS_NAME + "HTTP vhost validator [" + this + "] added [" + hostPort.getPort() + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
			}
			vhost.getProperties().setProperty(PROP_HTTP_REFERER_VALIDATE, this);
			validator = this;
			sessionInfoList = new ArrayList<SessionInfo>();
			lock = new Object();
			timer = new Timer();
			timer.schedule(new ExpireTimer(vhost), 0, this.sessionCheckInterval);
		}

	}

	class ExpireTimer extends TimerTask
	{

		private IVHost vhost;

		public ExpireTimer(IVHost vhost)
		{
			this.vhost = vhost;
		}

		public void run()
		{
			if (debugLog)
				logger.info(CLASS_NAME + " SessionTimer run()", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);

			SortedSet<SessionInfo> sortedSessions = new TreeSet<SessionInfo>();
			synchronized(validator.lock)
			{
				sortedSessions.addAll(validator.sessionInfoList);
				while (true)
				{
					if (sortedSessions.isEmpty())
						break;
					SessionInfo info = sortedSessions.first();
					if (System.currentTimeMillis() < info.getExpireTime())
					{
						if (debugLog)
							logger.info(CLASS_NAME + " SessionTimer check : current time [" + System.currentTimeMillis() + "] expire time [" + info.getExpireTime() + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
						break;
					}
					checkSession(info);
					sortedSessions.remove(info);
					if (debugLog)
						logger.info(CLASS_NAME + " SessionTimer removing session: [" + info.toString() + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
				}
			}
		}

		private void checkSession(SessionInfo info)
		{
			boolean remove = true;
			while(true)
			{
				if (vhost == null || !vhost.isApplicationLoaded(info.getAppName()))
					break;
				IApplication application = vhost.getApplication(info.getAppName());
				if (application == null || !application.isAppInstanceLoaded(info.getAppInstName()))
					break;
				IApplicationInstance appInstance = application.getAppInstance(info.getAppInstName());
				if (appInstance == null)
					break;
				List<IMediaStream> rtmpStreams = appInstance.getPlayStreamsByName(info.getStreamName());
				if (rtmpStreams != null)
				{
					for(IMediaStream stream : rtmpStreams)
					{
						IClient client = stream.getClient();
						if(client != null && client.getIp().equalsIgnoreCase(info.getIpAddress()))
						{
							remove = false;
							break;
						}
					}
				}
				if (remove)
				{
					List<IHTTPStreamerSession> httpSessions = appInstance.getHTTPStreamerSessions(info.getStreamName());
					if (httpSessions != null)
					{
						for(IHTTPStreamerSession session : httpSessions)
						{
							if(session.getIpAddress().equalsIgnoreCase(info.getIpAddress()))
							{
								remove = false;
								break;
							}
						}
					}
				}
				
				if (remove)
				{
					List<RTPSession> rtpSessions = appInstance.getRTPSessions(info.getStreamName());
					if (rtpSessions != null)
					{
						for(RTPSession session : rtpSessions)
						{
							if(session.getIp().equalsIgnoreCase(info.getIpAddress()))
							{
								remove = false;
								break;
							}
						}
					}
				}

				if (!remove)
				{
					long newExpireTime = System.currentTimeMillis() + appInstance.getProperties().getPropertyLong(RefererValidate.PROP_NAME_PREFIX + "SessionValidDuration", RefererValidate.SESSION_VALID_DURATION_DEFAULT);
					info.setExpireTime(newExpireTime);
					break;
				}
				
				validator.sessionInfoList.remove(info);
				break;
			}
		}

	}

	public List<SessionInfo> getSessionInfoList()
	{
		synchronized(validator.lock)
		{
			if (debugLog)
			{
				logger.info(CLASS_NAME + "HTTP vhost validator get session list from [" + validator.sessionInfoList + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
			}
			List<SessionInfo> sessions = new ArrayList<SessionInfo>();
			sessions.addAll(validator.sessionInfoList);
			return sessions;
		}
	}

	public void onHTTPRequest(IVHost vhost, IHTTPRequest req, IHTTPResponse resp)
	{
		String referers = "localhost, 127.0.0.1";
		boolean validRequest = false;
		boolean allowBlankReferers = RefererValidate.ALLOW_BLANK_REFERERS;
		long sessionDuration = RefererValidate.SESSION_VALID_DURATION_DEFAULT; // 10 minutes
		IApplicationInstance appInstance = null;
		WMSProperties props = null;
		String appName = "";
		String appInstName = "";
		String streamName = "";
		String imagePath = "";
		byte[] imageBytes = null;

		if (!doHTTPAuthentication(vhost, req, resp))
			return;

		while (true)
		{
			String url = req.getHeader("context");
			if (url == null)
				break;
			int qloc = url.indexOf("?");
			if (qloc >= 0)
				url = url.substring(0, qloc);

			CupertinoStreamingURL urlDecoded = new CupertinoStreamingURL(url, true);

			appName = urlDecoded.getAppName();
			appInstName = urlDecoded.getAppInstanceName();
			streamName = urlDecoded.getStreamName();
			String ipAddress = req.getRemoteAddr();
			String referer = "";
			if (req.getHeaderNames().contains("referer"))
			{
				try
				{
					URL refererURL = new URL(req.getHeader("referer"));
					referer = refererURL.getHost();
				}
				catch (MalformedURLException e)
				{
					logger.error(CLASS_NAME + ".onHttpRequest Exception getting referer: ", e);
				}
			}
			String queryStr = req.getQueryString();

			if (debugLog)
				logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + ", streamName: " + streamName + ", ipAddress: " + ipAddress + ", referer: " + referer + ", queryStr: " + queryStr + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);

			WMSReadWriteLock appLock = vhost.getApplicationLock();
			appLock.writeLock().lock();
			try
			{
				IApplication application = vhost.getApplication(appName);
				if (application == null)
				{
					logger.error(CLASS_NAME + ".onHttpRequest application cannot be loaded: " + appName, WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
					break;
				}
				
				appInstance = application.getAppInstance(appInstName);
				if (appInstance == null)
				{
					logger.error(CLASS_NAME + ".onHttpRequest application instance cannot be loaded: " + appName + "/" + appInstName, WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
					break;
				}
			}
			catch (Exception e)
			{
				logger.error(CLASS_NAME + ".onHttpRequest Exception loading appInstance: " + appName + "/" + appInstName, e);
				break;
			}
			finally
			{
				appLock.writeLock().unlock();
			}

			props = appInstance.getProperties();

			referers = props.getPropertyStr(RefererValidate.PROP_NAME_PREFIX + "Referers", referers);
			if (debugLog)
				logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + ", referers: " + referers + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
			if (StringUtils.isEmpty(referers))
			{
				if (debugLog)
					logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + "] referers is empty. Allowing connections", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
				validRequest = true;
				break;
			}

			allowBlankReferers = props.getPropertyBoolean(RefererValidate.PROP_NAME_PREFIX + "AllowBlankReferers", allowBlankReferers);
			if (debugLog)
				logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + ", allowBlankReferers: " + allowBlankReferers + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
			if (StringUtils.isEmpty(referer) && allowBlankReferers)
			{
				if (debugLog)
					logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + "] Session referer is empty and blank referers allowed. Allowing connection", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
				validRequest = true;
				break;
			}

			if (StringUtils.isEmpty(referer))
			{
				if (debugLog)
					logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + "] Session referer is empty and blank referers not allowed. Blocking connection", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
				break;
			}

			String[] referersArr = referers.split(",");
			for (int i = 0; i < referersArr.length; i++)
			{
				if (debugLog)
					logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + "] checking  " + referer + " against " + referersArr[i].trim(), WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);

				if (referersArr[i].trim().startsWith("*"))
				{
					String checkStr = referersArr[i].trim().substring(1);
					if (referer.endsWith(checkStr))
					{
						if (debugLog)
							logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + "] match found. referer: " + referer + " against " + referersArr[i].trim(), WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
						validRequest = true;
						break;
					}
				}
				else if (referer.equalsIgnoreCase(referersArr[i].trim()))
				{
					if (debugLog)
						logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + "] match found. referer: " + referer + " against " + referersArr[i].trim(), WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
					validRequest = true;
					break;
				}
			}

			if (!validRequest)
				break;

			sessionDuration = props.getPropertyLong(RefererValidate.PROP_NAME_PREFIX + "SessionValidDuration", sessionDuration);
			String aliasedStreamName = RefererValidate.decodeStreamName(((ApplicationInstance)appInstance).internalResolvePlayAlias(streamName));
			addNewSession(appName, appInstName, aliasedStreamName, ipAddress, queryStr, referer, sessionDuration);

			break;

		}

		boolean return404OnNotValidated = props != null ? props.getPropertyBoolean(RefererValidate.PROP_NAME_PREFIX + "Return404OnNotValidated", true) : true;
		if (!validRequest && return404OnNotValidated)
		{
			resp.setResponseCode(404);
			return;
		}
		imagePath = props.getPropertyStr(RefererValidate.PROP_NAME_PREFIX + "ImagePath", "");

		if (!StringUtils.isEmpty(imagePath))
		{
			imagePath = decodeImagePath(appInstance, RefererValidate.decodeStreamName(streamName), validRequest, imagePath);
			imageBytes = getImageBytes(imagePath);
		}
		String mimeType = "image/gif";
		if (imageBytes != null)
		{
			mimeType = getMimeType(imagePath);
		}
		else
		{
			imageBytes = PIXEL_BYTES;
		}

		try
		{
			resp.setHeader("Content-Type", mimeType);
			OutputStream out = resp.getOutputStream();
			out.write(imageBytes);
		}
		catch (Exception e)
		{
			logger.error(CLASS_NAME + "onHttpRequest Exception: " + e.toString(), e);
		}
	}

	public void onUnbind(IVHost vhost, HostPort hostPort)
	{
		if(validator == this)
		{
			if (timer != null)
				timer.cancel();
			timer = null;
			sessionInfoList = null;
			lock = null;
		}
	}

	public void addNewSession(String appName, String appInstName, String streamName, String ipAddress, String queryStr, String referer, long sessionDuration)
	{
		synchronized(validator.lock)
		{
			SessionInfo info = new SessionInfo();
			info.setAppName(appName);
			info.setAppInstName(appInstName);
			info.setStreamName(streamName);
			info.setIpAddress(ipAddress);
			info.setQueryStr(queryStr);

			if (debugLog)
			{
				logger.info(CLASS_NAME + ".onHttpRequest storing in [" +validator.sessionInfoList + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
			}

			if (validator.sessionInfoList.contains(info))
			{
				info = validator.sessionInfoList.get(validator.sessionInfoList.indexOf(info));
			}
			else
			{
				validator.sessionInfoList.add(info);
			}
			if (debugLog)
				logger.info(CLASS_NAME + ".onHttpRequest [appName: " + appName + ", appInstName: " + appInstName + "] saving sessionInfo " + info, WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
			if (referer != null)
			{
				info.setReferer(referer);
				if (debugLog)
				{
					logger.info(CLASS_NAME + ".onHttpRequest referer set is [" + info.getReferer() + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
				}
			}
			info.setExpireTime(System.currentTimeMillis() + sessionDuration);

			if (debugLog)
			{
				logger.info(CLASS_NAME + ".onHttpRequest expiry time for the session now [" + System.currentTimeMillis() + "] duration is [" + sessionDuration + "] session expire is [" + info.getExpireTime() + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
				logger.info(CLASS_NAME + ".onHttpRequest size of session list now [" + validator.sessionInfoList.size() + "]", WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
			}
		}
	}

	private String getMimeType(String path)
	{
		String mimeType = "image/gif";

		if (!StringUtils.isEmpty(path))
		{
			MimetypesFileTypeMap mimeTypesMap = new MimetypesFileTypeMap();
			File file = new File(path);
			if (file.exists() && file.isFile())
				mimeType = mimeTypesMap.getContentType(file);
		}

		return mimeType;
	}

	private byte[] getImageBytes(String path)
	{
		File file = new File(path);

		byte[] bytes = null;

		if (file.exists() && file.isFile())
		{
			FileInputStream fis = null;
			try
			{
				bytes = new byte[(int)file.length()];
				fis = new FileInputStream(file);
				fis.read(bytes);
			}
			catch (Exception e)
			{
				logger.error(CLASS_NAME + ".getImageBytes Exception: " + e.toString(), e);
			}
			finally
			{
				if (fis != null)
					try
					{
						fis.close();
					}
					catch (IOException e)
					{
					}
				fis = null;
			}
		}
		return bytes;
	}

	private String decodeImagePath(IApplicationInstance appInstance, String streamName, boolean valid, String path)
	{
		Map<String, String> envMap = new HashMap<String, String>();
		envMap.put("com.wowza.wms.context.VHost", appInstance.getVHost().getName());
		envMap.put("com.wowza.wms.context.VHostConfigHome", appInstance.getVHost().getHomePath());
		envMap.put("com.wowza.wms.context.Application", appInstance.getApplication().getName());
		envMap.put("com.wowza.wms.context.ApplicationInstance", appInstance.getName());
		envMap.put("com.wowza.wms.context.Stream", streamName);
		envMap.put("VHost.Name", appInstance.getVHost().getName());
		envMap.put("Application.Name", appInstance.getApplication().getName());
		envMap.put("ApplicationInstance.Name", appInstance.getName());
		envMap.put("AppInstance.Name", appInstance.getName());
		envMap.put("Stream.Name", streamName);
		envMap.put("Valid", Boolean.toString(valid));

		path = SystemUtils.expandEnvironmentVariables(path, envMap);
		return path;
	}
}
