package redis.clients.jedis.csc;

import io.redis.test.util.TlsUtil;
import org.junit.BeforeClass;
import redis.clients.jedis.HostAndPorts;
import redis.clients.jedis.SSLJedisTest;

import static io.redis.test.util.TlsUtil.envTruststore;

public class SSLJedisPooledClientSideCacheTest extends JedisPooledClientSideCacheTestBase {

  @BeforeClass
  public static void prepare() {
    TlsUtil.createAndSaveEnvTruststore("redis1-5", "changeit");
    TlsUtil.setJvmTrustStore(envTruststore("redis1-5"));

    endpoint = HostAndPorts.getRedisEndpoint("standalone0-tls");
  }

}
