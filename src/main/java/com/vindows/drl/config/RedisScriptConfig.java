package com.vindows.drl.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisScriptConfig {

	@Bean
	public DefaultRedisScript<List> tokenBucketScript() {
		var script = new DefaultRedisScript<List>();
		script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
		script.setResultType(List.class);
		return script;
	}

}
