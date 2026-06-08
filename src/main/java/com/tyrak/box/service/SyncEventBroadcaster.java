package com.tyrak.box.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class SyncEventBroadcaster {

    private final SyncWebSocketHandler handler;
    private final ObjectMapper objectMapper;

    public SyncEventBroadcaster(SyncWebSocketHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    public void broadcastStatus(Map<String, Object> status) {
        try {
            handler.broadcast(objectMapper.writeValueAsString(status));
        } catch (JsonProcessingException ignored) {
        }
    }

    public void broadcastEvent(String type, String message) {
        try {
            handler.broadcast(objectMapper.writeValueAsString(Map.of("type", type, "message", message, "ts", System.currentTimeMillis())));
        } catch (JsonProcessingException ignored) {
        }
    }
}

