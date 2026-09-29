package com.meonggo.backend.auth.web;

import com.meonggo.backend.auth.exception.InputValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

/** 신뢰한 direct peer만 단일 X-Forwarded-For를 전달할 수 있다. DNS 조회는 하지 않는다. */
public class ClientIpResolver {
    private final List<Network> trustedNetworks;

    public ClientIpResolver(List<String> trustedCidrs) {
        try {
            trustedNetworks = trustedCidrs.stream().map(Network::parse).toList();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid trusted proxy CIDR configuration");
        }
    }

    public byte[] resolve(HttpServletRequest request) {
        byte[] peer = parseAddress(request.getRemoteAddr());
        if (trustedNetworks.stream().noneMatch(network -> network.contains(peer))) {
            return peer;
        }
        List<String> headers = Collections.list(request.getHeaders("X-Forwarded-For"));
        if (headers.size() != 1) {
            throw invalid();
        }
        return parseAddress(headers.getFirst().strip());
    }

    private static byte[] parseAddress(String value) {
        if (value == null || value.isEmpty()) {
            throw invalid();
        }
        if (value.matches("[0-9.]+")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) {
                throw invalid();
            }
            byte[] address = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
                    throw invalid();
                }
                int number = Integer.parseInt(parts[i]);
                if (number > 255) {
                    throw invalid();
                }
                address[i] = (byte) number;
            }
            return address;
        }
        if (!value.contains(":") || !value.matches("[0-9a-fA-F:.]+")) {
            throw invalid();
        }
        try {
            // Java는 IPv4-mapped IPv6를 4바이트 주소로 반환한다.
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException ex) {
            throw invalid();
        }
    }

    private static InputValidationException invalid() {
        return new InputValidationException("clientIp", "요청 IP 정보를 확인해 주세요.");
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String value) {
            String[] parts = value.split("/", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            byte[] address = parseAddress(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > address.length * 8) {
                throw new IllegalArgumentException();
            }
            return new Network(address, prefix);
        }

        boolean contains(byte[] peer) {
            if (peer.length != address.length) {
                return false;
            }
            for (int i = 0; i < prefix; i++) {
                int mask = 1 << (7 - i % 8);
                if ((peer[i / 8] & mask) != (address[i / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
