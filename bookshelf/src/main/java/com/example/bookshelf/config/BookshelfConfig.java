package com.example.bookshelf.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FeatureFlags.class)
public class BookshelfConfig {
}
