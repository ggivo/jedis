package redis.clients.jedis;

import redis.clients.jedis.util.Expirable;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

public class RebindAwareHostPortSupplier implements Supplier<HostAndPort>, RebindAware {
  private final Supplier<HostAndPort> delegatedSupplier;
  private final HostAndPort initialHostAndPort;
  private volatile Expirable<HostAndPort> rebindTarget;

  public RebindAwareHostPortSupplier(HostAndPort initialHostAndPort,
      Supplier<HostAndPort> hostAndPortSupplier) {
    this.initialHostAndPort = initialHostAndPort;
    this.delegatedSupplier = hostAndPortSupplier;
  }

  public void rebind(HostAndPort rebindTarget, Duration rebindTimeout) {
    this.rebindTarget = new Expirable<>(rebindTarget, rebindTimeout);
  }

  public HostAndPort get() {
    Expirable<HostAndPort> rebindHostPort = this.rebindTarget;
    if (rebindHostPort != null && rebindHostPort.isValid()) {
      return rebindHostPort.getValue();
    }

    if (delegatedSupplier != null) {
      return delegatedSupplier.get();
    }

    return initialHostAndPort;
  }

  public boolean isRebindInProgress() {
    Expirable<HostAndPort> rebindHostPort = rebindTarget;
    return rebindHostPort != null && rebindHostPort.isValid();
  }

  public Instant getRebindExpirationTime() {
    return rebindTarget.getExpirationTime();
  }

}
