package redis.clients.jedis.util.server;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the replies of matching commands on a {@link TcpMockServer} until {@link #release()}. The
 * reply is computed when the command arrives and written once the gate opens — a slow wire, not a
 * slow server — and only the connection that sent the command waits. A released gate stays open;
 * re-arm by registering a new one. Every hold is bounded by {@code maxHold}, so a client that gave
 * up never leaves a handler thread parked.
 */
public final class Gate implements AutoCloseable {

  private final CommandMatcher matcher;
  private final long maxHoldNanos;
  private final Collection<Gate> registry;
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch firstEntered = new CountDownLatch(1);
  private final AtomicInteger entered = new AtomicInteger();

  Gate(CommandMatcher matcher, Duration maxHold, Collection<Gate> registry) {
    this.matcher = matcher;
    this.maxHoldNanos = maxHold.toNanos();
    this.registry = registry;
  }

  boolean matches(List<String> tokens) {
    return matcher.test(tokens);
  }

  boolean isReleased() {
    return release.getCount() == 0;
  }

  /** Parks the calling handler thread until release or {@code maxHold}; counts the arrival. */
  void hold() throws InterruptedException {
    entered.incrementAndGet();
    firstEntered.countDown();
    release.await(maxHoldNanos, TimeUnit.NANOSECONDS);
  }

  /** Waits until at least one matching command has reached this gate. */
  public boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
    return firstEntered.await(timeout, unit);
  }

  /** Commands that reached this gate so far, parked or already let through. */
  public int entered() {
    return entered.get();
  }

  /** Lets every parked reply through; later matching commands pass immediately. */
  public void release() {
    release.countDown();
  }

  /** Releases and unregisters the gate. */
  @Override
  public void close() {
    release();
    registry.remove(this);
  }
}
