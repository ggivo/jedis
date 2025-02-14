package redis.clients.jedis.util;

import redis.clients.jedis.HostAndPort;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The collection of replies where a command is broadcasted to multiple nodes, but the replies are expected to differ
 * even in ideal situation.
 */
public class JedisBroadcastReplies<T> {

  private final Map<HostAndPort, T> replies = new HashMap<>();

  public void addReply(HostAndPort node, T reply) {
    replies.put(node, reply);
  }

  public Map<Object, T> getReplies() {
    return Collections.unmodifiableMap(replies);
  }

}
