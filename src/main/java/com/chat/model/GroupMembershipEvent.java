package com.chat.model;

/**
 * Published to RabbitMQ whenever a user is added to (or removed from) a group.
 * Exchange: "membership.events" (topic)
 * Routing key: "user.{userId}.group-membership"
 *
 * Active WebSocket sessions subscribe to their own routing key so they
 * can dynamically subscribe to a new group channel without reconnecting.
 */
public record GroupMembershipEvent(String groupId, String userId, String action) {}

