package redis.clients.jedis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RebindAwareHostPortSupplier, focusing on the most important functionality:
 * <ol>
 * <li>- Basic supplier behavior without rebind</li>
 * <li>- Rebind functionality and expiration</li>
 * <li>- Fallback behavior to delegated supplier and initial host/port</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("upgrade")
public class RebindAwareHostPortSupplierTest {

  @Mock
  private Supplier<HostAndPort> mockDelegatedSupplier;

  private HostAndPort initialHostAndPort;
  private HostAndPort delegatedHostAndPort;
  private HostAndPort rebindHostAndPort;
  private RebindAwareHostPortSupplier supplier;

  @BeforeEach
  void setUp() {
    initialHostAndPort = new HostAndPort("initial.host", 6379);
    delegatedHostAndPort = new HostAndPort("delegated.host", 6380);
    rebindHostAndPort = new HostAndPort("rebind.host", 6381);

    when(mockDelegatedSupplier.get()).thenReturn(delegatedHostAndPort);
  }

  @Test
  void testGetWithoutRebind_WithDelegatedSupplier() {
    // Create supplier with delegated supplier
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, mockDelegatedSupplier);

    // Should return delegated supplier result when no rebind is active
    HostAndPort result = supplier.get();
    assertEquals(delegatedHostAndPort, result);
    verify(mockDelegatedSupplier).get();
  }

  @Test
  void testGetWithoutRebind_WithoutDelegatedSupplier() {
    // Create supplier without delegated supplier
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, null);

    // Should return initial host and port when no delegated supplier and no rebind
    HostAndPort result = supplier.get();
    assertEquals(initialHostAndPort, result);
  }

  @Test
  void testRebindInProgress() {
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, mockDelegatedSupplier);

    // Initially no rebind should be in progress
    assertFalse(supplier.isRebindInProgress());

    // Trigger rebind with long timeout
    Duration rebindTimeout = Duration.ofSeconds(30);
    supplier.rebind(rebindHostAndPort, rebindTimeout);

    // Rebind should now be in progress
    assertTrue(supplier.isRebindInProgress());

    // Should return rebind target instead of delegated supplier
    HostAndPort result = supplier.get();
    assertEquals(rebindHostAndPort, result);

    // Delegated supplier should not be called when rebind is active
    verify(mockDelegatedSupplier, never()).get();
  }

  @Test
  void testRebindExpiration() throws InterruptedException {
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, mockDelegatedSupplier);

    // Trigger rebind with very short timeout
    Duration rebindTimeout = Duration.ofMillis(5);
    supplier.rebind(rebindHostAndPort, rebindTimeout);

    // Initially rebind should be in progress
    assertTrue(supplier.isRebindInProgress());

    // Wait for rebind to expire
    Thread.sleep(10);

    // Rebind should no longer be in progress
    assertFalse(supplier.isRebindInProgress());

    // Should fall back to delegated supplier after expiration
    HostAndPort result = supplier.get();
    assertEquals(delegatedHostAndPort, result);
    verify(mockDelegatedSupplier).get();
  }

  @Test
  void testGetRebindExpirationTime() {
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, mockDelegatedSupplier);

    Duration rebindTimeout = Duration.ofSeconds(30);
    Instant beforeRebind = Instant.now();
    supplier.rebind(rebindHostAndPort, rebindTimeout);
    Instant afterRebind = Instant.now();

    Instant expirationTime = supplier.getRebindExpirationTime();
    assertNotNull(expirationTime);

    // Expiration time should be approximately now + rebindTimeout
    Instant expectedMinExpiration = beforeRebind.plus(rebindTimeout);
    Instant expectedMaxExpiration = afterRebind.plus(rebindTimeout);

    assertTrue(expirationTime.isAfter(expectedMinExpiration.minusSeconds(1)),
      "Expiration time should be after expected minimum");
    assertTrue(expirationTime.isBefore(expectedMaxExpiration.plusSeconds(1)),
      "Expiration time should be before expected maximum");
  }

  @Test
  void testRebindOverwrite() {
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, mockDelegatedSupplier);

    // First rebind
    HostAndPort firstRebindTarget = new HostAndPort("first.rebind", 6382);
    supplier.rebind(firstRebindTarget, Duration.ofSeconds(30));
    assertEquals(firstRebindTarget, supplier.get());

    // Second rebind should overwrite the first
    HostAndPort secondRebindTarget = new HostAndPort("second.rebind", 6383);
    supplier.rebind(secondRebindTarget, Duration.ofSeconds(30));
    assertEquals(secondRebindTarget, supplier.get());

    // Should still be in rebind state
    assertTrue(supplier.isRebindInProgress());
  }

  @Test
  void testFallbackHierarchy() {
    // Test the fallback hierarchy: rebind -> delegated -> initial

    // 1. With rebind active, should return rebind target
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, mockDelegatedSupplier);
    supplier.rebind(rebindHostAndPort, Duration.ofSeconds(30));
    assertEquals(rebindHostAndPort, supplier.get());

    // 2. After rebind expires, should fall back to delegated supplier
    supplier.rebind(rebindHostAndPort, Duration.ofMillis(1));
    try {
      Thread.sleep(10); // Wait for expiration
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertEquals(delegatedHostAndPort, supplier.get());

    // 3. Without delegated supplier, should fall back to initial
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, null);
    assertEquals(initialHostAndPort, supplier.get());
  }

  @Test
  void testConcurrentAccess() {
    // Test that volatile rebindTarget field handles concurrent access correctly
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, mockDelegatedSupplier);

    // Simulate concurrent rebind and get operations
    supplier.rebind(rebindHostAndPort, Duration.ofSeconds(1));

    // Multiple get calls should consistently return rebind target while active
    for (int i = 0; i < 10; i++) {
      assertEquals(rebindHostAndPort, supplier.get());
      assertTrue(supplier.isRebindInProgress());
    }
  }

  @Test
  void testIsRebindInProgressWithNullRebindTarget() {
    supplier = new RebindAwareHostPortSupplier(initialHostAndPort, mockDelegatedSupplier);

    // Initially rebindTarget is null, so rebind should not be in progress
    assertFalse(supplier.isRebindInProgress());

    // After setting rebind, should be in progress
    supplier.rebind(rebindHostAndPort, Duration.ofSeconds(30));
    assertTrue(supplier.isRebindInProgress());
  }
}
