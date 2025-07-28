package redis.clients.jedis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import redis.clients.jedis.util.ReflectionTestUtils;
import redis.clients.jedis.util.server.CommandHandler;
import redis.clients.jedis.util.server.TcpMockServer;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Connection class focusing on relaxed timeout expiration functionality.
 * Tests verify that relaxed timeouts are properly managed and expired.
 * <p>
 * Tests verify the correct behavior where future expiry times are set and past times are ignored.
 * </p>
 */
@Tag("unit")
@Tag("upgrade")
public class ConnectionCommandTimeoutTest {

    private final int originalTimeoutMs = 2000;
    private final Duration relaxedTimeout = Duration.ofSeconds(5);
    private final Duration relaxedBlockingTimeout = Duration.ofSeconds(10);
    private final CommandHandler mockHandler = Mockito.mock(CommandHandler.class);
    private final CommandObjects commands = new CommandObjects();

    private TcpMockServer mockServer;
    private JedisClientConfig clientConfig;
    private Connection connection;
    private HostAndPort hostAndPort;

    @BeforeEach
    void setUp() throws Exception {
        // Start the mock TCP server
        mockServer = new TcpMockServer();
        mockServer.setCommandHandler(mockHandler);
        mockServer.start();

        hostAndPort = new HostAndPort("localhost", mockServer.getPort());

        // Create client config with relaxed timeouts enabled
        clientConfig = DefaultJedisClientConfig.builder()
            .socketTimeoutMillis(originalTimeoutMs)
            .timeoutOptions(TimeoutOptions.builder()
                .proactiveTimeoutsRelaxing(relaxedTimeout)
                .proactiveBlockingTimeoutsRelaxing(relaxedBlockingTimeout)
                .build())
            .protocol(RedisProtocol.RESP3)
            .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (connection != null && connection.isConnected()) {
            connection.close();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    void testRelaxedTimeoutBasicFunctionality() throws Exception {
        // Create and connect to mock server
        connection = new Connection(new DefaultJedisSocketFactory(hostAndPort), clientConfig);
        connection.connect();

        // Initially relaxed timeout should not be active
        assertFalse(connection.isRelaxedTimeoutActive());
        assertNull(connection.getRelaxedTimeoutExpiryTime());

        // Enable relaxed timeout without expiry time
        connection.relaxTimeouts();

        // Verify relaxed timeout is now active
        assertTrue(connection.isRelaxedTimeoutActive());
        assertNull(connection.getRelaxedTimeoutExpiryTime()); // No expiry time set

        // Verify socket timeout is changed to relaxed timeout
        assertEquals( (int) relaxedTimeout.toMillis(), ConnectionTestHelper.socketTimeout(connection));
    }

    @Test
    void testRelaxedTimeoutWithPastExpiryTime() throws Exception {
        // Create and connect to mock server
        connection = new Connection(new DefaultJedisSocketFactory(hostAndPort), clientConfig);
        connection.connect();

        // Past times should NOT be set as expiry time (fixed behavior)
        Instant pastTime = Instant.now().minusSeconds(10);
        connection.relaxTimeouts(pastTime);

        assertFalse(connection.isRelaxedTimeoutActive());
        assertNull(connection.getRelaxedTimeoutExpiryTime()); // Past time not set

        // Verify socket timeout is original timeout
        assertEquals( originalTimeoutMs, ConnectionTestHelper.socketTimeout(connection));
    }

    @Test
    void testRelaxedTimeoutWithFutureExpiryTime() throws Exception {
        // Create and connect to mock server
        connection = new Connection(new DefaultJedisSocketFactory(hostAndPort), clientConfig);
        connection.connect();

        // Future times should be set as expiry time (fixed behavior)
        Instant futureTime = Instant.now().plusSeconds(30);
        connection.relaxTimeouts(futureTime);

        // Verify relaxed timeout is active and expiry time IS set for future times
        assertTrue(connection.isRelaxedTimeoutActive());
        assertEquals(futureTime, connection.getRelaxedTimeoutExpiryTime()); // Fixed: future time is set

        // Verify socket timeout is changed to relaxed timeout
        Socket socket = ReflectionTestUtils.getField(connection, "socket");

        assertEquals((int) relaxedTimeout.toMillis(), ConnectionTestHelper.socketTimeout(connection));
    }

    @Test
    void testRollbackTimeouts() throws Exception {
        // Create and connect to mock server
        connection = new Connection(new DefaultJedisSocketFactory(hostAndPort), clientConfig);
        connection.connect();

        // Initially should have original timeout
        assertEquals(originalTimeoutMs, ConnectionTestHelper.socketTimeout(connection));

        // Enable relaxed timeout
        connection.relaxTimeouts();
        assertTrue(connection.isRelaxedTimeoutActive());

        // Verify socket timeout is changed to relaxed timeout
        assertEquals((int) relaxedTimeout.toMillis(), ConnectionTestHelper.socketTimeout(connection));

        // Disable relaxed timeout
        connection.disableRelaxedTimeout();

        // Verify relaxed timeout is no longer active
        assertFalse(connection.isRelaxedTimeoutActive());
        assertNull(connection.getRelaxedTimeoutExpiryTime());

        // Verify socket timeout is back to original
        assertEquals(originalTimeoutMs, ConnectionTestHelper.socketTimeout(connection));
    }

    @Test
    void testRelaxedTimeoutDisabledWhenNotConfigured() throws Exception {
        // Create config without relaxed timeouts
        JedisClientConfig configWithoutRelaxed = DefaultJedisClientConfig.builder()
            .socketTimeoutMillis(originalTimeoutMs)
            .protocol(RedisProtocol.RESP3)
            .build();

        // Create and connect to mock server
        connection = new Connection(new DefaultJedisSocketFactory(hostAndPort), configWithoutRelaxed);
        connection.connect();

        // Try to enable relaxed timeout - should have no effect
        connection.relaxTimeouts(Instant.now().plusSeconds(10));

        // Should not be active since relaxed timeouts are not configured
        assertFalse(connection.isRelaxedTimeoutActive());
        assertNull(connection.getRelaxedTimeoutExpiryTime());

        // Socket timeout should remain at original value
        Socket socket = ReflectionTestUtils.getField(connection, "socket");
        assertEquals(originalTimeoutMs, socket.getSoTimeout());
    }

    @Test
    void testGetDesiredTimeoutMethod() throws Exception {
        // Create and connect to mock server
        connection = new Connection(new DefaultJedisSocketFactory(hostAndPort), clientConfig);
        connection.connect();

        // Use reflection to access the private getDesiredTimeout method
        java.lang.reflect.Method getDesiredTimeoutMethod = connection.getClass().getDeclaredMethod("getDesiredTimeout");
        getDesiredTimeoutMethod.setAccessible(true);

        // Initially should return original timeout
        int desiredTimeout = (Integer) getDesiredTimeoutMethod.invoke(connection);
        assertEquals(originalTimeoutMs, desiredTimeout);

        // Enable relaxed timeout
        connection.relaxTimeouts();

        // Should now return relaxed timeout
        desiredTimeout = (Integer) getDesiredTimeoutMethod.invoke(connection);
        assertEquals((int) relaxedTimeout.toMillis(), desiredTimeout);

        // Disable relaxed timeout
        connection.disableRelaxedTimeout();

        // Should return original timeout again
        desiredTimeout = (Integer) getDesiredTimeoutMethod.invoke(connection);
        assertEquals(originalTimeoutMs, desiredTimeout);
    }

    @Test
    void testIsRelaxedTimeoutActiveMethod() throws Exception {
        // Create and connect to mock server
        connection = new Connection(new DefaultJedisSocketFactory(hostAndPort), clientConfig);
        connection.connect();

        // Initially should not be active
        assertFalse(connection.isRelaxedTimeoutActive());

        // Enable relaxed timeout
        connection.relaxTimeouts();
        assertTrue(connection.isRelaxedTimeoutActive());

        // Disable relaxed timeout
        connection.disableRelaxedTimeout();
        assertFalse(connection.isRelaxedTimeoutActive());
    }

    @Test
    void testRelaxedTimeoutExpiryTimeValidation() throws Exception {
        // Create and connect to mock server
        connection = new Connection(new DefaultJedisSocketFactory(hostAndPort), clientConfig);
        connection.connect();

        // Test with null expiry time - should work
        connection.relaxTimeouts(null);
        assertTrue(connection.isRelaxedTimeoutActive());
        assertNull(connection.getRelaxedTimeoutExpiryTime());
        // Verify socket timeout is changed to relaxed timeout
        assertEquals((int) relaxedTimeout.toMillis(), ConnectionTestHelper.socketTimeout(connection));

        // Reset
        connection.disableRelaxedTimeout();


        // Test with future time - should be set
        Instant future = Instant.now().plusMillis(2);
        connection.relaxTimeouts(future);
        assertTrue(connection.isRelaxedTimeoutActive());
        assertEquals(future, connection.getRelaxedTimeoutExpiryTime());
        // Verify socket timeout is changed to relaxed timeout
        assertEquals((int) relaxedTimeout.toMillis(), ConnectionTestHelper.socketTimeout(connection));

        connection.executeCommand(commands.ping());
        await().pollDelay(Duration.ofMillis(1)).atMost(Duration.ofMillis(10)).until(() -> {
            connection.executeCommand(commands.ping());
            return !connection.isRelaxedTimeoutActive();
        });
        assertFalse(connection.isRelaxedTimeoutActive());

    }
}
