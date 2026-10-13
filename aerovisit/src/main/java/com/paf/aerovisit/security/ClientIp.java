package com.paf.aerovisit.security;

import jakarta.servlet.http.HttpServletRequest;

// 🌐 WHO IS ASKING: one client-IP rule for every limiter (RateLimitFilter, chatbot, OTP).
// With server.forward-headers-strategy=native, Tomcat's RemoteIpValve has already replaced getRemoteAddr() with the
// visitor's IP when the TCP peer is a trusted internal proxy (ngrok on 127.0.0.1; Railway's edge when its address is
// in Tomcat's internal-proxies list). If the peer is STILL a private address, the valve did not recognise the proxy,
// so the LAST X-Forwarded-For hop is used: the one that proxy appended. Earlier hops, and any header from a public
// peer, come from the browser and could be faked to dodge a limit, so they are never used.
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) return "unknown";
        if (!isPrivate(remote)) return remote;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            String proxyAdded = hops[hops.length - 1].trim();
            if (!proxyAdded.isEmpty()) return proxyAdded;
        }
        return remote;
    }

    // Loopback, 10/8, 172.16/12, 192.168/16, link-local, carrier-grade NAT 100.64/10 and IPv6 unique-local fc00::/7.
    // getRemoteAddr() is always an IP literal, so this parses it without any DNS lookup.
    static boolean isPrivate(String ip) {
        if (!ip.matches("[0-9A-Fa-f:.%]+")) return false;
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(ip);
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
            byte[] b = a.getAddress();
            if (b.length == 4) return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64.0.0/10
            return (b[0] & 0xFE) == 0xFC;                                           // fc00::/7
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
