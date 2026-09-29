package com.meonggo.backend.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.InputValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {
    @Test
    void ignoresSpoofedHeaderFromDirectClient() {
        MockHttpServletRequest request = request("192.0.2.1", "203.0.113.2");
        assertThat(new ClientIpResolver(List.of()).resolve(request))
                .containsExactly((byte) 192, 0, 2, 1);
    }

    @Test
    void trustedPeerRequiresExactlyOneLiteralAddress() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.1", "203.0.113.2")))
                .containsExactly((byte) 203, 0, 113, 2);
        for (String value :
                List.of(
                        "example.com",
                        "127.1",
                        "1.2.3.4, 5.6.7.8",
                        "010.1.2.3",
                        "fe80::1%eth0",
                        "[::1]:80")) {
            assertThatThrownBy(() -> resolver.resolve(request("10.0.0.1", value)))
                    .isInstanceOf(InputValidationException.class)
                    .hasMessageNotContaining(value);
        }
        MockHttpServletRequest missing = request("10.0.0.1", null);
        assertThatThrownBy(() -> resolver.resolve(missing))
                .isInstanceOf(InputValidationException.class);
        MockHttpServletRequest multiple = request("10.0.0.1", "192.0.2.1");
        multiple.addHeader("X-Forwarded-For", "192.0.2.2");
        assertThatThrownBy(() -> resolver.resolve(multiple))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void mappedIpv6UsesSameAddressBytes() {
        ClientIpResolver resolver = new ClientIpResolver(List.of());
        assertThat(resolver.resolve(request("::ffff:192.0.2.1", null)))
                .isEqualTo(resolver.resolve(request("192.0.2.1", null)));
    }

    private MockHttpServletRequest request(String peer, String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwarded != null) {
            request.addHeader("X-Forwarded-For", forwarded);
        }
        return request;
    }
}
