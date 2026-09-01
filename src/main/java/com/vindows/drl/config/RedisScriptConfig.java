package com.vindows.drl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.util.List;

@Configuration
public class RedisScriptConfig {

  @Bean
  public RedisScript<List> tokenBucketScript() {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
    script.setResultType(List.class);
    return script;
  }

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
