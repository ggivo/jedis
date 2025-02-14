package redis.clients.jedis.commands.unified;


import org.junit.Test;
import redis.clients.jedis.RedisProtocol;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public abstract class ControlCommandsTestBase extends UnifiedJedisCommandsTestBase {

  public ControlCommandsTestBase(RedisProtocol protocol) {
    super(protocol);
  }

  @Test
  public void info() {
    String info = jedis.info();
    assertThat(info, containsString("# Server"));
  }

  @Test
  public void infoSection() {
    String info = jedis.info("server");
    assertThat(info, containsString("# Server"));
  }
}
