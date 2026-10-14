package redis.clients.jedis.util.server;

import java.util.List;
import java.util.function.Predicate;

/**
 * Matches a command as the mock server decoded it: the command name followed by its arguments, e.g.
 * {@code ["BLPOP", "key", "0"]}. Command and subcommand tokens match case-insensitively, keys
 * exactly.
 */
@FunctionalInterface
public interface CommandMatcher extends Predicate<List<String>> {

  /** Commands starting with the given tokens, e.g. {@code command("CLUSTER", "SLOTS")}. */
  static CommandMatcher command(String... leadingTokens) {
    return tokens -> {
      if (tokens.size() < leadingTokens.length) {
        return false;
      }
      for (int i = 0; i < leadingTokens.length; i++) {
        if (!leadingTokens[i].equalsIgnoreCase(tokens.get(i))) {
          return false;
        }
      }
      return true;
    };
  }

  /** A keyed command on exactly {@code key} (its first argument). */
  static CommandMatcher keyed(String command, String key) {
    return tokens -> tokens.size() > 1 && command.equalsIgnoreCase(tokens.get(0))
        && key.equals(tokens.get(1));
  }
}
