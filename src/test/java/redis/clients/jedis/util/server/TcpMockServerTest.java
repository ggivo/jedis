package redis.clients.jedis.util.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static redis.clients.jedis.util.server.CommandMatcher.command;
import static redis.clients.jedis.util.server.CommandMatcher.keyed;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.util.SafeEncoder;

/** The reply gates of {@link TcpMockServer}: hold, release, bound, matching precedence. */
public class TcpMockServerTest {

  private TcpMockServer server;
  private Connection connection;

  @BeforeEach
  public void setUp() throws IOException {
    server = new TcpMockServer();
    server.start();
    connection = new Connection(new HostAndPort("127.0.0.1", server.getPort()),
        DefaultJedisClientConfig.builder().socketTimeoutMillis(5000).build());
  }

  @AfterEach
  public void tearDown() throws IOException {
    connection.close();
    server.stop();
  }

  @Test
  public void gateHoldsTheReplyUntilReleased() throws Exception {
    Gate ping = server.gate(command("PING"));
    Future<Boolean> pong = CompletableFuture.supplyAsync(connection::ping);

    assertTrue(ping.awaitEntered(5, TimeUnit.SECONDS));
    Thread.sleep(100);
    assertFalse(pong.isDone(), "held");
    assertEquals(1, ping.entered());

    ping.release();
    assertTrue(pong.get(5, TimeUnit.SECONDS));
    assertTrue(connection.ping(), "a released gate no longer holds");
  }

  @Test
  public void firstUnreleasedMatchingGateWinsAndGatesNeverChain() throws Exception {
    Gate first = server.gate(command("PING"));
    Gate second = server.gate(command("PING"));
    Future<Boolean> pong = CompletableFuture.supplyAsync(connection::ping);

    assertTrue(first.awaitEntered(5, TimeUnit.SECONDS));
    assertEquals(0, second.entered(), "the command parks at one gate only");
    first.release();
    assertTrue(pong.get(5, TimeUnit.SECONDS), "released without waiting on the second gate");

    Future<Boolean> next = CompletableFuture.supplyAsync(connection::ping);
    assertTrue(second.awaitEntered(5, TimeUnit.SECONDS), "the next command parks at the next gate");
    second.release();
    assertTrue(next.get(5, TimeUnit.SECONDS));
  }

  @Test
  public void matchersSelectByTokens() throws Exception {
    Gate echoOnly = server.gate(keyed("ECHO", "held"));
    server.setCommandHandler(
      (args, clientId) -> "ECHO".equalsIgnoreCase(SafeEncoder.encode(args.getCommand().getRaw()))
          ? RespResponse.bulkString("echoed")
          : null);

    assertTrue(connection.ping(), "PING does not match a keyed ECHO gate");
    Future<Object> held = CompletableFuture.supplyAsync(
      () -> connection.executeCommand(new CommandArguments(Protocol.Command.ECHO).add("held")));
    assertTrue(echoOnly.awaitEntered(5, TimeUnit.SECONDS));
    echoOnly.release();
    held.get(5, TimeUnit.SECONDS);
  }

  @Test
  public void delayLetsTheReplyThroughAfterTheBound() throws Exception {
    server.delay(command("PING"), Duration.ofMillis(200));
    long start = System.nanoTime();
    assertTrue(connection.ping());
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsedMillis >= 150, "reply delayed, elapsed=" + elapsedMillis);
  }

  @Test
  public void stopReleasesParkedHandlers() throws Exception {
    Gate ping = server.gate(command("PING"));
    Future<Boolean> pong = CompletableFuture.supplyAsync(connection::ping);
    assertTrue(ping.awaitEntered(5, TimeUnit.SECONDS));

    server.stop();

    // the parked reply is let through or the socket is closed first; either way nothing is stuck
    try {
      pong.get(2, TimeUnit.SECONDS);
    } catch (ExecutionException closedFirst) {
      // acceptable: the client saw the connection close
    }
    assertEquals(0, server.getConnectedClientCount());
  }

  @Test
  public void closedGateIsUnregistered() throws Exception {
    try (Gate ping = server.gate(command("PING"))) {
      assertEquals(0, ping.entered());
    }
    assertTrue(connection.ping(), "nothing holds after close()");
  }
}
