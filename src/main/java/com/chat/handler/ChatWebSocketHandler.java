package com.chat.handler;

import com.chat.service.GroupService;
import com.chat.service.MessagingService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * One WebSocket per user: ws://host/ws/chat?userId=alice
 *
 * On connect the server subscribes Alice to:
 *   1. Her own DM inbox     ("user.alice.inbox")
 *   2. Every group she's in ("group.G1.events", "group.G2.events", ...)
 *
 * All of these flow down the same WebSocket. The client uses
 * message.type (DM | GROUP) and message.groupId to route into
 * the right conversation panel.
 *
 * Incoming commands from client:
 *   { type: "SEND_DM",    to: "bob",  content: "hi" }
 *   { type: "SEND_GROUP", to: "G1",   content: "hey team" }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler implements WebSocketHandler {

    private final MessagingService messagingService;
    private final GroupService groupService;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String query  = session.getHandshakeInfo().getUri().getQuery();
        String userId = extract(query, "userId", session.getId());

        log.info("WS connect: userId={}", userId);

        // Ensure this user's DM inbox exchange exists
        messagingService.ensureUserInbox(userId);

        // ── Outbound: merge DM inbox + all group channels ──────────
        //
        // subscribeToInbox → Flux<ChatMessage> from "user.alice.inbox"
        // groupsForUser → Flux<Group>, then flatMap each → subscribeToGroup
        //
        // Flux.merge combines them all into a single stream → one WebSocket.
        // The client inspects message.type to know which panel to update.

        Flux<String> dmStream = messagingService.subscribeToInbox(userId)
            .flatMap(m -> Mono.fromCallable(() -> objectMapper.writeValueAsString(m)));

        Flux<String> groupStreams = groupService.groupsForUser(userId)
            .flatMap(group -> {
                // Ensure the exchange exists before subscribing
                messagingService.ensureGroupExchange(group.getId());
                return messagingService.subscribeToGroup(group.getId());
            })
            .flatMap(m -> Mono.fromCallable(() -> objectMapper.writeValueAsString(m)));

        Flux<String> allOutbound = Flux.merge(dmStream, groupStreams);

        // ── Inbound: handle commands from client ────────────────────
        Mono<Void> inbound = session.receive()
            .flatMap(wsMsg -> handleCommand(userId, wsMsg.getPayloadAsText()))
            .then();

        return Mono.zip(
            session.send(allOutbound.map(session::textMessage)),
            inbound
        )
        .doFinally(sig -> log.info("WS disconnect: userId={}", userId))
        .then();
    }

    private Mono<Void> handleCommand(String userId, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String type   = node.path("type").asString();
            String to     = node.path("to").asString();
            String content = node.path("content").asString();

            if (content.isBlank()) return Mono.empty();

            return switch (type) {
                case "SEND_DM" -> {
                    // Ensure recipient inbox exists before delivery
                    messagingService.ensureUserInbox(to);
                    yield messagingService.sendDm(userId, to, content).then();
                }
                case "SEND_GROUP" ->
                    // Validate membership before allowing the send
                    groupService.findById(to)
                        .flatMap(group -> {
                            if (!group.hasMember(userId)) {
                                log.warn("User {} tried to post to group {} without membership", userId, to);
                                return Mono.empty();
                            }
                            return messagingService.sendToGroup(userId, to, content).then();
                        });
                default -> {
                    log.debug("Unknown command '{}' from {}", type, userId);
                    yield Mono.empty();
                }
            };
        } catch (Exception e) {
            log.warn("Bad command from {}: {}", userId, e.getMessage());
            return Mono.empty();
        }
    }

    private String extract(String query, String key, String fallback) {
        if (query == null) return fallback;
        for (String p : query.split("&"))
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1);
        return fallback;
    }
}
