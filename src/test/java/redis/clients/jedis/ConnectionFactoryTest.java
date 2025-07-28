package redis.clients.jedis;

import org.apache.commons.pool2.PooledObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import redis.clients.jedis.util.ReflectionTestUtils;
import redis.clients.jedis.util.server.CommandHandler;
import redis.clients.jedis.util.server.TcpMockServer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConnectionFactory, specifically testing relaxed timeout behavior
 * when rebind is in progress.
 */
@Tag("upgrade")
public class ConnectionFactoryTest {

    private final int originalTimeoutMs = 2000;
    private final Duration relaxedTimeout = Duration.ofSeconds(10);
    private final Duration relaxedBlockingTimeout = Duration.ofSeconds(15);
    private final CommandHandler mockHandler = Mockito.mock(CommandHandler.class);

    private TcpMockServer mockServer;
    private TcpMockServer rebindTargetServer;
    private JedisClientConfig clientConfig;
    private ConnectionFactory connectionFactory;
    private HostAndPort hostAndPort;
    private HostAndPort rebindTargetHostAndPort;

    @BeforeEach
    void setUp() throws Exception {
        // Start the original mock TCP server
        mockServer = new TcpMockServer();
        mockServer.setCommandHandler(mockHandler);
        mockServer.start();

        // Start the rebind target mock TCP server
        rebindTargetServer = new TcpMockServer();
        rebindTargetServer.setCommandHandler(mockHandler);
        rebindTargetServer.start();

        hostAndPort = new HostAndPort("localhost", mockServer.getPort());
        rebindTargetHostAndPort = new HostAndPort("localhost", rebindTargetServer.getPort());

        // Create client config with proactive rebind enabled and relaxed timeouts
        clientConfig = DefaultJedisClientConfig.builder()
            .socketTimeoutMillis(originalTimeoutMs)
            .proactiveRebindEnabled(true)
            .timeoutOptions(TimeoutOptions.builder()
                .proactiveTimeoutsRelaxing(relaxedTimeout)
                .proactiveBlockingTimeoutsRelaxing(relaxedBlockingTimeout)
                .build())
            .protocol(RedisProtocol.RESP3)
            .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockServer != null) {
            mockServer.stop();
        }
        if (rebindTargetServer != null) {
            rebindTargetServer.stop();
        }
    }

    @Test
    void testMakeObjectWithoutRebindInProgress() throws Exception {
        // Create ConnectionFactory with proactive rebind enabled
        connectionFactory = new ConnectionFactory(hostAndPort, clientConfig);

        // Create a connection when rebind is NOT in progress
        PooledObject<Connection> pooledConnection = connectionFactory.makeObject();
        Connection connection = pooledConnection.getObject();

        assertNotNull(connection);
        assertTrue(connection.isConnected());

        assertThat(connection.isRelaxedTimeoutActive(), equalTo(false));
        assertThat(ConnectionTestHelper.socketTimeout(connection), equalTo(originalTimeoutMs));
        assertThat(ConnectionTestHelper.getHostAndPort(connection), equalTo(hostAndPort));

        // Clean up
        connection.close();
    }

    @Test
    void testMakeObjectWithRebindInProgress() throws Exception {
        // Create ConnectionFactory with proactive rebind enabled
        connectionFactory = new ConnectionFactory(hostAndPort, clientConfig);

        // Trigger rebind to put the factory in "rebind in progress" state
        // Use the rebind target server that's actually running
        Duration rebindTimeout = Duration.ofSeconds(30);
        connectionFactory.rebind(rebindTargetHostAndPort, rebindTimeout);

        // Create a connection when rebind IS in progress
        // This will connect to the rebind target server and should have relaxed timeouts applied
        PooledObject<Connection> pooledConnection = connectionFactory.makeObject();
        Connection connection = pooledConnection.getObject();

        assertNotNull(connection);
        assertTrue(connection.isConnected());

        // Verify that relaxed timeout IS active since rebind is in progress
        assertThat(connection.isRelaxedTimeoutActive(), equalTo(true));
        assertThat(ConnectionTestHelper.socketTimeout(connection), equalTo((int) relaxedTimeout.toMillis()));
        assertThat(ConnectionTestHelper.getHostAndPort(connection), equalTo(rebindTargetHostAndPort));


        // Verify that the expiry time is in the future (approximately now + rebindTimeout)
        Instant now = Instant.now();
        Instant actualExpiry = connection.getRelaxedTimeoutExpiryTime();
        assertTrue(actualExpiry.isAfter(now));
        assertTrue(actualExpiry.isBefore(now.plus(rebindTimeout).plusSeconds(1)));

        // Clean up
        connection.close();
    }


    @Test
    void testMakeObjectWithRebindExpired() throws Exception {
        // Create ConnectionFactory with proactive rebind enabled
        connectionFactory = new ConnectionFactory(hostAndPort, clientConfig);

        // Trigger rebind with a very short timeout that will expire immediately
        // Use the rebind target server that's actually running
        Duration rebindTimeout = Duration.ofMillis(1);
        connectionFactory.rebind(rebindTargetHostAndPort, rebindTimeout);

        // Wait for rebind to expire
        Thread.sleep(10);

        // Create a connection when rebind has expired
        PooledObject<Connection> pooledConnection = connectionFactory.makeObject();
        Connection connection = pooledConnection.getObject();

        assertNotNull(connection);
        assertTrue(connection.isConnected());

        // Verify that relaxed timeout is NOT active since rebind has expired
        assertFalse(connection.isRelaxedTimeoutActive());

        // Verify socket timeout is back to original timeout
        assertEquals(originalTimeoutMs, ConnectionTestHelper.socketTimeout(connection));

        // Clean up
        connection.close();
    }

    @Test
    void testMakeObjectWithProactiveRebindDisabled() throws Exception {
        // Create client config with proactive rebind DISABLED
        JedisClientConfig configWithoutRebind = DefaultJedisClientConfig.builder()
            .socketTimeoutMillis(originalTimeoutMs)
            .proactiveRebindEnabled(false)  // Disabled
            .timeoutOptions(TimeoutOptions.builder()
                .proactiveTimeoutsRelaxing(relaxedTimeout)
                .proactiveBlockingTimeoutsRelaxing(relaxedBlockingTimeout)
                .build())
            .protocol(RedisProtocol.RESP3)
            .build();

        // Create ConnectionFactory with rebind disabled
        connectionFactory = new ConnectionFactory(hostAndPort, configWithoutRebind);

        // Verify that rebindAwareHostPortSupplier is null when rebind is disabled
        RebindAwareHostPortSupplier rebindAwareHostPortSupplier = ReflectionTestUtils.getField(connectionFactory, "rebindAwareHostPortSupplier");
        assertNull(rebindAwareHostPortSupplier, "rebindAwareHostPortSupplier should be null when proactive rebind is disabled");

        // Create a connection
        PooledObject<Connection> pooledConnection = connectionFactory.makeObject();
        Connection connection = pooledConnection.getObject();

        assertNotNull(connection);
        assertTrue(connection.isConnected());

        // Verify that relaxed timeout is NOT active since rebind is disabled
        assertFalse(connection.isRelaxedTimeoutActive());

        // Verify socket timeout is set to original timeout
        assertEquals(originalTimeoutMs, ConnectionTestHelper.socketTimeout(connection));

        // Clean up
        connection.close();
    }

    @Test
    void testRebindWithCustomSocketFactoryThrowsException() {
        // Create client config with proactive rebind ENABLED (this should cause exception with custom factory)
        JedisClientConfig configWithRebind = DefaultJedisClientConfig.builder()
            .socketTimeoutMillis(originalTimeoutMs)
            .proactiveRebindEnabled(true)  // Enabled - should cause exception with custom factory
            .timeoutOptions(TimeoutOptions.builder()
                .proactiveTimeoutsRelaxing(relaxedTimeout)
                .proactiveBlockingTimeoutsRelaxing(relaxedBlockingTimeout)
                .build())
            .protocol(RedisProtocol.RESP3)
            .build();

        // Create a mock custom socket factory (not DefaultJedisSocketFactory)
        JedisSocketFactory customSocketFactory = mock(JedisSocketFactory.class);

        // Creating ConnectionFactory with custom socket factory and rebind enabled should throw exception
        assertThrows(IllegalStateException.class, () -> {
            new ConnectionFactory(customSocketFactory, configWithRebind);
        }, "Should throw IllegalStateException when using custom socket factory with rebind enabled");
    }
}
