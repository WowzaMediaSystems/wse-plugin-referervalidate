# Referer Validate 
The **RefererValidate** module and HTTP Provider for [Wowza Streaming Engine™ media server software](https://www.wowza.com/products/streaming-engine) can be used to control access to your streams based on the referer domain. It is designed for use with players that aren't RTMP-based, and uses an image tag or JavaScript added to the player webpage to validate the requester.

## Prerequisites
Wowza Streaming Engine 4.0.3 or later is required.

The Wowza™ Module Collection build 12557 or later is required.

## Usage
Adding an image tag or JavaScript to the embedded player webpage with a specified image path that points towards your Wowza server allows Wowza to validate the requestor and then enable the stream for playback. If the player is configured to start automatically or the page may take longer to load, the image tag should be positioned on the page before the embedded player. For example, you can add the following to the webpage:

<?xml version="1.0" encoding="UTF-8"?>
<image src="http://[wowza-ip-address]:1935/[stream-path]/image.gif" />
```
Where the **[stream-path]** is the stream path in the player (such as **vod/sample.mp4** or **live/_definst_/myStream**), and the **image.gif** is the **Requestfilter** defined in the default streaming hostport section of the **[install-dir]/conf/VHost.xml** configuration file. See [Configuration](https://www.wowza.com/forums/content.php?614-How-to-control-access-to-your-application-by-checking-referer-domain-(RefererValidate)#configuration) for more information.

When an HTTP request is sent to a Wowza Streaming Engine media server by an image tag or JavaScript on the player webpage,the **RefererValidate** module validates it based on the domain from which the request originated. After the requester is validated, the session information is stored and an image is returned; the image can be the default 1x1-pixel transparent GIF or a different image configured for each stream. If the request isn't validated, you can return the default 404 error code or a different HTTP error code that you've configured.

## More resources
[Wowza Streaming Engine Server-Side API Reference](https://www.wowza.com/resources/WowzaStreamingEngine_ServerSideAPI.pdf)

[How to extend Wowza Streaming Engine using the Wowza IDE](https://www.wowza.com/forums/content.php?759-How-to-extend-Wowza-Streaming-Engine-using-the-Wowza-IDE)

Wowza Media Systems™ provides developers with a platform to create streaming applications and solutions. See [Wowza Developer Tools](https://www.wowza.com/resources/developers) to learn more about our APIs and SDK.

To use the compiled version of this module, see [How to control access to your application by checking referer domain](https://www.wowza.com/forums/content.php?614-How-to-control-access-to-your-application-by-checking-referer-domain-(RefererValidate)).

## Contact
[Wowza Media Systems, LLC](https://www.wowza.com/contact)

## License
This code is distributed under the [Wowza Public License](https://github.com/WowzaMediaSystems/wse-plugin-referervalidate/blob/master/LICENSE.txt).

![alt tag](http://wowzalogs.com/stats/githubimage.php?plugin=wse-plugin-referervalidate)
