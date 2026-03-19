package com.chat.config;

import com.chat.controller.ChatController;
import com.chat.handler.ChatWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
public class WebConfig {

    @Bean
    public HandlerMapping wsMapping(ChatWebSocketHandler chatWebSocketHandler) {
        return new SimpleUrlHandlerMapping(Map.of("/ws/chat", chatWebSocketHandler), -1);
    }

    @Bean
    public WebSocketHandlerAdapter wsAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public RouterFunction<ServerResponse> routes(ChatController chatController) {
        return RouterFunctions.route()
                .GET("/api/users", chatController::listUsers)
                .POST("/api/groups", chatController::createGroup)
                .POST("/api/groups/{groupId}/members", chatController::addMember)
                .DELETE("/api/groups/{groupId}/members/{userId}", chatController::removeMember)
                .GET("/api/groups", chatController::listGroups)
                .GET("/api/history/dm/{a}/{b}", chatController::dmHistory)
                .GET("/api/history/group/{groupId}", chatController::groupHistory)
                .build();
    }
}
