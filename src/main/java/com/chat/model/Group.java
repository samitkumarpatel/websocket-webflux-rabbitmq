package com.chat.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Group lives in MongoDB and owns a RabbitMQ fan-out exchange.
 *
 * Exchange name: "group.{id}.events"
 *
 * When a member sends a message they publish to that exchange.
 * Every member's WebSocket session has a queue bound to it → all receive.
 */
@Data
@Document(collection = "groups")
public class Group {

    @Id
    private String id;          // UUID, also used in the exchange name

    private String name;
    private String createdBy;   // userId of the creator

    private List<String> memberIds = new ArrayList<>();

    private Instant createdAt = Instant.now();

    public boolean hasMember(String userId) {
        return memberIds.contains(userId);
    }
}
