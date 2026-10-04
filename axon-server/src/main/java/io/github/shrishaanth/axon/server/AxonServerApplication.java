package io.github.shrishaanth.axon.server;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AxonProperties.class)
public class AxonServerApplication {

    public static void main(String[] args) {
        // Postgres rejects some JVM default zone names (for example Asia/Calcutta); everything here is UTC anyway.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(AxonServerApplication.class, args);
    }

    @Bean
    WebMvcConfigurer cors(AxonProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(properties.getCorsOrigins().split("\\s*,\\s*"))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .maxAge(3600);
            }
        };
    }
}
