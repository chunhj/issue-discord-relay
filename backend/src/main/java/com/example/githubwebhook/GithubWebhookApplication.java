package com.example.githubwebhook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GithubWebhookApplication {

    public static void main(String[] args) {
        SpringApplication.run(GithubWebhookApplication.class, args);
    }
}
