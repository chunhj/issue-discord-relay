package com.example.githubwebhook.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** On by default; tests switch the worker off with app.worker.enabled=false (D-18). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
