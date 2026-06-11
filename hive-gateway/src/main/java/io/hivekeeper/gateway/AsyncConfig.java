package io.hivekeeper.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Runs the {@code Callable}-returning controller methods (the agent round-trips) on a bounded executor
 * instead of holding a servlet container thread. Bounds concurrency and keeps the request pool free.
 */
@Configuration
class AsyncConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("gw-dispatch-");
        executor.setDaemon(true);
        executor.initialize();

        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(120_000);
    }
}
