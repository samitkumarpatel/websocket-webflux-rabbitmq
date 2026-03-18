package com.chat.config;

public class RabbitConfig {

    /**
     * Two exchange namespaces:
     *
     *   user.{userId}.inbox   — direct, durable, one per user sender writes to RECIPIENT's exchange
     *
     *   group.{groupId}.events — fanout, durable, one per group any member writes to it; all members receive
     *
     * That's the entire routing model for the whole app.
     */
    public static String userInbox(String userId)   { return "user."  + userId  + ".inbox";  }
    public static String groupExchange(String groupId) { return "group." + groupId + ".events"; }
}
