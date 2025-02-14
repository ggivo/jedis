package redis.clients.jedis.commands.unified.cluster;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPorts;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.commands.unified.ControlCommandsTestBase;
import redis.clients.jedis.util.EnabledOnCommandRule;
import redis.clients.jedis.util.JedisBroadcastReplies;
import redis.clients.jedis.util.RedisVersionRule;

import static org.hamcrest.MatcherAssert.assertThat;

@RunWith(Parameterized.class)
public class ClusterControlCommandsTest extends ControlCommandsTestBase {


  public ClusterControlCommandsTest(RedisProtocol protocol) {
    super(protocol);
  }

  @Rule
  public RedisVersionRule versionRule = new RedisVersionRule(
            HostAndPorts.getStableClusterServers().get(0),
            DefaultJedisClientConfig.builder().password("cluster").build());
  @Rule
  public EnabledOnCommandRule enabledOnCommandRule = new EnabledOnCommandRule(
          HostAndPorts.getStableClusterServers().get(0),
          DefaultJedisClientConfig.builder().password("cluster").build());

  private JedisCluster jedisCluster;

  @Before
  public void setUp() {
    // Preserve as JedisCluster to use cluster specific commands
    jedisCluster = ClusterCommandsTestHelper.getCleanCluster(protocol);
    // Use save Jedis client in base tests
    jedis = jedisCluster;
  }

  @Test
  public void infoAllNodes() {
    JedisBroadcastReplies<String> infoReplies = jedisCluster.infoAllNodes();
    assertThat(infoReplies.getReplies(), Matchers.aMapWithSize(3));
    infoReplies.getReplies().values().forEach(infoValue -> {
      assertThat(infoValue, Matchers.containsString("# Server"));
    });
  }

  @Test
  public void infoAllNodesSection() {
    JedisBroadcastReplies<String> infoReplies = jedisCluster.infoAllNodes("server");
    assertThat(infoReplies.getReplies(), Matchers.aMapWithSize(3));
    infoReplies.getReplies().values().forEach(infoValue -> {
      assertThat( infoValue, Matchers.notNullValue());
    });
  }

  @After
  public void tearDown() {
    jedis.close();
    ClusterCommandsTestHelper.clearClusterData();
  }
}
