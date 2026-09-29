package com.meonggo.backend.ping.service;

import com.meonggo.backend.ping.dto.PingResponse;
import org.springframework.stereotype.Service;

@Service
public class PingService {

    public PingResponse ping() {
        return PingResponse.pong();
    }
}
