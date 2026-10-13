package com.paf.aerovisit.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** One client-IP rule for every limiter: proxy headers count only when the TCP peer is a private address. */
class ClientIpTest {

    private static MockHttpServletRequest from(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }

    @Test
    void aPublicPeerIsTheClientAndItsHeadersAreIgnored() {
        assertThat(ClientIp.of(from("203.0.113.9", null))).isEqualTo("203.0.113.9");
        assertThat(ClientIp.of(from("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
        assertThat(ClientIp.of(from("2001:db8::7", "1.2.3.4"))).isEqualTo("2001:db8::7");
    }

    @Test
    void behindAPrivatePeerTheLastForwardedHopIsTheClient() {
        for (String proxy : new String[] {"127.0.0.1", "0:0:0:0:0:0:0:1", "10.4.0.2", "172.20.1.1", "192.168.1.10",
                                          "100.64.3.7", "100.127.255.1", "fd12:3456::1", "fe80::1"}) {
            assertThat(ClientIp.of(from(proxy, "198.51.100.7"))).as(proxy).isEqualTo("198.51.100.7");
        }
    }

    @Test
    void aSpoofedFirstHopIsIgnored() {
        assertThat(ClientIp.of(from("10.4.0.2", "6.6.6.6, 198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void addressesJustOutsideThePrivateRangesArePublic() {
        assertThat(ClientIp.isPrivate("100.63.255.255")).isFalse();
        assertThat(ClientIp.isPrivate("100.128.0.1")).isFalse();
        assertThat(ClientIp.isPrivate("172.32.0.1")).isFalse();
        assertThat(ClientIp.isPrivate("8.8.8.8")).isFalse();
        assertThat(ClientIp.isPrivate("2001:db8::1")).isFalse();
        assertThat(ClientIp.isPrivate("not-an-ip.example")).isFalse(); // never resolved through DNS
    }

    @Test
    void noUsableHeaderFallsBackToThePeer() {
        assertThat(ClientIp.of(from("127.0.0.1", null))).isEqualTo("127.0.0.1");
        assertThat(ClientIp.of(from("127.0.0.1", " , "))).isEqualTo("127.0.0.1");
        MockHttpServletRequest blank = from("127.0.0.1", null);
        blank.setRemoteAddr(null);
        assertThat(ClientIp.of(blank)).isEqualTo("unknown");
    }
}
