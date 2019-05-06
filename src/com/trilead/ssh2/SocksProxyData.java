/**
 *
 */
package com.trilead.ssh2;

import java.net.UnknownHostException;

import net.sourceforge.jsocks.Proxy;
import net.sourceforge.jsocks.Socks5Proxy;

/**
 * @author dido
 *
 */
public class SocksProxyData implements ProxyData
{
	public final Proxy proxy;

	public SocksProxyData(String proxyhost, int proxyport) throws UnknownHostException
	{
		Socks5Proxy p = new Socks5Proxy(proxyhost, proxyport);
		p.resolveAddrLocally(false);
		proxy = p;
	}
}
