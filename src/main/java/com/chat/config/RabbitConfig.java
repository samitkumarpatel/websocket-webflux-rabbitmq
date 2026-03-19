package com.chat.config;

public class RabbitConfig {

    /**
     * Three exchange namespaces:
     *
     *   user.{userId}.inbox      — direct, durable, one per user; sender writes to RECIPIENT's exchange
     *
     *   group.{groupId}.events   — fanout, durable, one per group; any member writes; all members receive
     *
     *   membership.events        — topic, durable, single shared exchange;
     *                              routing key: "user.{userId}.group-membership"
     *                              published when a user is added to a group so their active
     *                              WS session can dynamically subscribe to the new group channel
     */
    public static String userInbox(String userId)      { return "user."  + userId  + ".inbox";  }
    public static String groupExchange(String groupId) { return "group." + groupId + ".events"; }
    public static String membershipExchange()          { return "membership.events"; }
    public static String membershipRoutingKey(String userId) { return "user." + userId + ".group-membership"; }
}
