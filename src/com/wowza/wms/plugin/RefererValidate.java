package com.wowza.wms.plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.wowza.wms.amf.AMFDataList;
import com.wowza.wms.application.*;
import com.wowza.wms.client.IClient;
import com.wowza.wms.httpstreamer.model.IHTTPStreamerSession;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.plugin.HttpRefererValidate;
import com.wowza.wms.plugin.SessionInfo;
import com.wowza.wms.request.RequestFunction;
import com.wowza.wms.rtp.model.RTPSession;
import com.wowza.wms.rtp.model.RTPUrl;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.MediaStream;
import com.wowza.wms.util.ModuleUtils;
import com.wowza.wms.util.SecurityUtils;

public class RefererValidate extends ModuleBase
{
	public static final String MODULE_NAME = "ModuleRefererValidate";
	public static final String PROP_NAME_PREFIX = "refererValidate";
	public static final boolean ALLOW_BLANK_REFERERS = false;
	public static final long SESSION_VALID_DURATION_DEFAULT = 600000; // 10 minutes

	// Use Core Security flashver property so we don't duplicate.
	// Publish: Valid flash versions
	// Publish: allowed encoders
	private static final String PROP_PUBLISH_ALLOWED_ENCODERS = "securityPublishValidEncoders";
	private String validFlashVersionsStr = SecurityUtils.DEFAULT_AUTHFLASHVERSIONS.contains("Wowza GoCoder") ? SecurityUtils.DEFAULT_AUTHFLASHVERSIONS : SecurityUtils.DEFAULT_AUTHFLASHVERSIONS + "|Wowza GoCoder";
	private List<String> validFlashVersions = new ArrayList<String>();

	private WMSLogger logger = null;
	private HttpRefererValidate validator = null;
	private IApplicationInstance appInstance;
	private boolean debugLog = false;

	public void onAppStart(IApplicationInstance appInstance)
	{
		this.logger = WMSLoggerFactory.getLoggerObj(appInstance);
		this.appInstance = appInstance;

		this.validFlashVersionsStr = appInstance.getProperties().getPropertyStr(PROP_PUBLISH_ALLOWED_ENCODERS, this.validFlashVersionsStr);
		this.validFlashVersions = SecurityUtils.parseValidFlashStrings(validFlashVersionsStr);

		validator = (HttpRefererValidate)appInstance.getVHost().getProperties().get(HttpRefererValidate.PROP_HTTP_REFERER_VALIDATE);
		debugLog = appInstance.getProperties().getPropertyBoolean(PROP_NAME_PREFIX + "DebugLog", debugLog);
		if (logger.isDebugEnabled())
			debugLog = true;

	}

	public void onConnect(IClient client, RequestFunction function, AMFDataList params)
	{
		String ipAddress = client.getIp();
		String flashVersion = client.getFlashVer();
		boolean isPublisher = SecurityUtils.isValidFlashVersion(flashVersion, this.validFlashVersions);
		if (isPublisher)
		{
			if (debugLog)
				logger.info(MODULE_NAME + ": Client is publisher. Passing further checks to ModuleCoreSecurity. " + ipAddress + ": " + flashVersion, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			return;
		}
		if (!checkSession(ipAddress, null))
		{
			client.rejectConnection();
			if (debugLog)
				logger.info(MODULE_NAME + ": Client connection rejected. " + ipAddress, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		}
	}

	public void play(IClient client, RequestFunction function, AMFDataList params)
	{
		String ipAddress = client.getIp();
		String streamName = params.getString(PARAM1);
		String aliasedName = ((ApplicationInstance)appInstance).internalResolvePlayAlias(streamName, client);
		if (checkSession(ipAddress, aliasedName))
		{
			invokePrevious(client, function, params);
		}
		else
		{
			IMediaStream clientStream = getStream(client, function);
			sendStreamOnStatusError(clientStream, "NetStream.Play.Failed", "Playback not allowed for " + streamName);
			if (debugLog)
				logger.info(MODULE_NAME + ": Client stream play rejected. " + ipAddress + ": " + streamName, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		}
	}

	public void onHTTPSessionCreate(IHTTPStreamerSession httpSession)
	{
		String ipAddress = httpSession.getIpAddress();
		String streamName = httpSession.getStreamName();

		if (debugLog)
		{
			logger.info(MODULE_NAME + ": http request ipAddress [" + ipAddress + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			logger.info(MODULE_NAME + ": http request streamName [" + streamName + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		}

		if (!checkSession(ipAddress, streamName))
		{
			httpSession.rejectSession();
			if (debugLog)
				logger.info(MODULE_NAME + ": http stream play rejected. " + ipAddress + ": " + streamName, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		}
	}

	public void onRTPSessionCreate(RTPSession rtpSession)
	{
		String ipAddress = rtpSession.getIp();
		String userAgent = rtpSession.getUserAgent();
		boolean isPublisher = SecurityUtils.isValidFlashVersion(userAgent, this.validFlashVersions);
		if (isPublisher)
		{
			if (debugLog)
				logger.info(MODULE_NAME + ": Client is publisher. Passing further checks to ModuleCoreSecurity. " + ipAddress + ": " + userAgent, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			return;
		}

		String uri = rtpSession.getUri();
		RTPUrl url = new RTPUrl(uri);
		String streamName = url.getStreamName();
		String aliasedName = ((ApplicationInstance)appInstance).internalResolvePlayAlias(streamName, rtpSession);
		if (checkSession(ipAddress, aliasedName))
		{
			rtpSession.rejectSession();
			if (debugLog)
				logger.info(MODULE_NAME + ": rtp stream play rejected. " + ipAddress + ": " + streamName, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		}
	}

	private boolean checkSession(String ipAddress, String streamName)
	{
		if (debugLog)
			logger.info(MODULE_NAME + ".checkSession [" + ipAddress + ": " + streamName + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		boolean pass = false;

		if (streamName != null)
			streamName = decodeStreamName(streamName);

		if (validator == null)
		{
			logger.warn(MODULE_NAME + ".checkSession HTTPProvider, " + HttpRefererValidate.CLASS_NAME + ", not enabled for VHost. Allowing connection", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			return true;
		}

		List<SessionInfo> sessions = validator.getSessionInfoList();
		Iterator<SessionInfo> iter = sessions.iterator();

		if (debugLog)
			logger.info(MODULE_NAME + ".checkSession size of valid sessions is [" + sessions.size() + "]");

		while (iter.hasNext())
		{
			SessionInfo session = iter.next();
			
			if (debugLog)
			{
				logger.info(MODULE_NAME + ".checkSession : session info IPAddress [" + session.getIpAddress() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				logger.info(MODULE_NAME + ".checkSession : session info Referer [" + session.getReferer() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				logger.info(MODULE_NAME + ".checkSession : session info QueryStr [" + session.getQueryStr() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				logger.info(MODULE_NAME + ".checkSession : session info ExpireTime [" + session.getExpireTime() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				logger.info(MODULE_NAME + ".checkSession : session info StreamName [" + session.getStreamName() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				logger.info(MODULE_NAME + ".checkSession : session info AppInstance [" + session.getAppInstName() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				logger.info(MODULE_NAME + ".checkSession : session info AppName [" + session.getAppName() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			}

			if (debugLog)
				logger.info(
						MODULE_NAME + ".checkSession [session: " + session + "] [ running appInstance: '" + appInstance.getName() + "'] [ running appName: '" + appInstance.getApplication().getName() + "'] [ session appInstance: '" + session.getAppInstName() + "'] [ session appName: '"
								+ session.getAppName() + "'] ", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

			if (!appInstance.getApplication().getName().equals(session.getAppName()))
			{
				if (debugLog)
				{
					logger.info(MODULE_NAME + ".checkSession [session: " + session + "] [ running appName does not equal session appName ]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				}
				continue;
			}
			if (!appInstance.getName().equals(session.getAppInstName()))
			{
				if (debugLog)
				{
					logger.info(MODULE_NAME + ".checkSession [session: " + session + "] [ running appInstance does not equal session appInstance ]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				}
				continue;
			}
			if (debugLog)
			{
				logger.info(MODULE_NAME + ".checkSession [session: " + session + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				logger.info(MODULE_NAME + ".checkSession [session: " + session + "] [ running ipAddress: '" + ipAddress + "' ] [ session ipAddress: '" + session.getIpAddress() + "' ]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				logger.info(MODULE_NAME + ".checkSession [session: " + session + "] [ running streamName: '" + streamName + "'] [ session streamName: '" + session.getStreamName() + "']", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			}
			if (ipAddress.equalsIgnoreCase(session.getIpAddress()))
			{
				if (streamName == null)
				{
					if (debugLog)
						logger.info(MODULE_NAME + ".checkSession [ipAddress passed. streamName not checked. session: " + session + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					pass = true;
					break;
				}
				else if(streamName.equalsIgnoreCase(session.getStreamName()))
				{
					if (debugLog)
						logger.info(MODULE_NAME + ".checkSession [ipAddress passed. streamName passed. session: " + session + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					pass = true;
					break;
				}
				else
				{
					logger.info(MODULE_NAME + ".checkSession [session: " + session + "] [ running streamName does not equal session streamName ]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				}
			}
		}

		if (!pass && appInstance.getProperties().getPropertyBoolean(PROP_NAME_PREFIX + "AllowBlankReferers", ALLOW_BLANK_REFERERS))
		{
			long sessionDuration = appInstance.getProperties().getPropertyLong(PROP_NAME_PREFIX + "SessionValidDuration", SESSION_VALID_DURATION_DEFAULT);
			validator.addNewSession(appInstance.getApplication().getName(), appInstance.getName(), streamName, ipAddress, null, null, sessionDuration);
			pass = true;
			if (debugLog)
				logger.info(MODULE_NAME + ".checkSession [appName: " + appInstance.getApplication().getName() + ", appInstName: " + appInstance.getName() + ", streamName: " + streamName + ", ipAddress: " + ipAddress + "] Session referer is empty and blank referers allowed. Allowing connection",
						WMSLoggerIDs.CAT_vhost, WMSLoggerIDs.EVT_comment);
		}

		if (debugLog)
		{
			logger.info(MODULE_NAME + ".checkSession end [ passFlag: " + pass + "] ", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		}

		return pass;
	}

	public static String decodeStreamName(String streamName)
	{
		String streamExt = MediaStream.BASE_STREAM_EXT;
		if (streamName != null)
		{
			String[] streamDecode = ModuleUtils.decodeStreamExtension(streamName, streamExt);
			streamName = streamDecode[0];
			streamExt = streamDecode[1];

			boolean isStreamNameURL = streamName.indexOf("://") >= 0;
			int streamQueryIdx = streamName.indexOf("?");
			if (!isStreamNameURL && streamQueryIdx >= 0)
			{
				streamName = streamName.substring(0, streamQueryIdx);
			}
		}
		return streamName;
	}
}