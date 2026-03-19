package com.chat.service;

import com.chat.config.RabbitConfig;
import com.chat.model.ChatMessage;
import com.chat.repository.ChatMessageRepository;
import tools.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessagingService {

    private final Sender sender;
    private final Receiver receiver;
    private final RabbitAdmin rabbitAdmin;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────
    // Exchange bootstrap
    // ─────────────────────────────────────────────────────────────

    /** Called once when a user first connects. Creates their durable inbox exchange. */
    public void ensureUserInbox(String userId) {
        rabbitAdmin.declareExchange(
            ExchangeBuilder.directExchange(RabbitConfig.userInbox(userId))
                .durable(true).build()
        );
    }

    /**
     * Called when a group is created (or on first use after restart).
     * Fan-out exchange: every queue bound to it receives every message.
     * Durable: the group survives broker restarts.
     */
    public void ensureGroupExchange(String groupId) {
        rabbitAdmin.declareExchange(
            ExchangeBuilder.fanoutExchange(RabbitConfig.groupExchange(groupId))
                .durable(true).build()
        );
    }

    // ─────────────────────────────────────────────────────────────
    // Sending
    // ─────────────────────────────────────────────────────────────

    /**
     * DM: Alice sends to Bob.
     *
     * Key: publish to BOB'S exchange ("user.bob.inbox"), not Alice's.
     * Alice's session never sees her own outbound message via Rabbit —
     * the UI adds it optimistically, or you can echo it back separately.
     */
    public Mono<ChatMessage> sendDm(String senderId, String recipientId, String content) {
        var msg = new ChatMessage();
        msg.setId(UUID.randomUUID().toString());
        msg.setType(ChatMessage.MessageType.DM);
        msg.setSenderId(senderId);
        msg.setRecipientId(recipientId);
        msg.setContent(content);

        return chatMessageRepository.save(msg)
            // Route to RECIPIENT's inbox — the whole DM trick in one line
            .flatMap(saved -> publish(RabbitConfig.userInbox(recipientId), saved)
                .thenReturn(saved));
    }

    /**
     * Group message: any member sends to the group.
     *
     * Key: publish to the GROUP exchange ("group.G1.events").
     * Because it's fan-out, every member's queue (including the sender's)
     * gets a copy — same as Kahoot's room broadcast.
     *
     * If you don't want the sender to see their own message via Rabbit
     * (they've already added it optimistically in the UI), filter on
     * senderId client-side or add a flag to the message.
     */
    public Mono<ChatMessage> sendToGroup(String senderId, String groupId, String content) {
        var msg = new ChatMessage();
        msg.setId(UUID.randomUUID().toString());
        msg.setType(ChatMessage.MessageType.GROUP);
        msg.setSenderId(senderId);
        msg.setGroupId(groupId);
        msg.setContent(content);

        return chatMessageRepository.save(msg)
            // Route to GROUP exchange — all members receive
            .flatMap(saved -> publish(RabbitConfig.groupExchange(groupId), saved)
                .thenReturn(saved));
    }

    private Mono<Void> publish(String exchange, ChatMessage msg) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(msg))
            .flatMap(body -> {
                var props = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2) // persistent
                    .build();
                return sender.send(Mono.just(
                    new OutboundMessage(exchange, "", props, body)
                ));
            })
            .doOnError(e -> log.error("Publish failed to {}: {}", exchange, e.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────
    // Subscribing
    // ─────────────────────────────────────────────────────────────

    /**
     * Subscribe to a user's own DM inbox.
     * Each WS session gets its own exclusive auto-delete queue bound to
     * "user.{userId}.inbox". Supports multiple devices per user.
     */
    public Flux<ChatMessage> subscribeToInbox(String userId) {
        return bindAndConsume(RabbitConfig.userInbox(userId), "inbox." + userId);
    }

    /**
     * Subscribe to a group channel.
     * Each WS session gets its own exclusive auto-delete queue bound to
     * "group.{groupId}.events". Every subscriber gets every group message.
     *
     * This is IDENTICAL in mechanics to the Kahoot room subscription —
     * only the exchange name and durability differ.
     */
    public Flux<ChatMessage> subscribeToGroup(String groupId) {
        return bindAndConsume(RabbitConfig.groupExchange(groupId), "group." + groupId);
    }

    /**
     * Shared helper: declare an exclusive auto-delete queue,
     * bind it to the given exchange, consume from it.
     * Queue is cleaned up automatically when the subscriber cancels.
     */
    private Flux<ChatMessage> bindAndConsume(String exchange, String queuePrefix) {
        String queue = "ws." + queuePrefix + "." + UUID.randomUUID();

        var q = QueueBuilder.nonDurable(queue).autoDelete().build();
        rabbitAdmin.declareQueue(q);
        rabbitAdmin.declareBinding(
            new Binding(queue, Binding.DestinationType.QUEUE, exchange, "", null)
        );

        return receiver.consumeAutoAck(queue)
            .mapNotNull(delivery -> {
                try {
                    return objectMapper.readValue(
                        new String(delivery.getBody(), StandardCharsets.UTF_8),
                        ChatMessage.class
                    );
                } catch (Exception e) {
                    log.error("Deserialize error on queue {}", queue, e);
                    return null;
                }
            })
            .doOnCancel(() -> {
                rabbitAdmin.deleteQueue(queue);
                log.debug("Cleaned up queue {}", queue);
            });
    }

    // ─────────────────────────────────────────────────────────────
    // History (MongoDB)
    // ─────────────────────────────────────────────────────────────

    public Flux<ChatMessage> dmHistory(String a, String b, int limit) {
        return chatMessageRepository.findDmHistory(a, b,
            PageRequest.of(0, limit, Sort.by("sentAt").descending()));
    }

    public Flux<ChatMessage> groupHistory(String groupId, int limit) {
        return chatMessageRepository.findByGroupIdOrderBySentAtDesc(groupId,
            PageRequest.of(0, limit));
    }
}
