package com.audiobookfactory.control;

import com.audiobookfactory.control.config.AccessTokenFilter;
import com.audiobookfactory.control.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class AudiobookFactoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(AudiobookFactoryApplication.class, args);
    }

    @Bean
    AccessTokenFilter accessTokenFilter(AppProperties appProperties) {
        return new AccessTokenFilter(appProperties);
    }
}
