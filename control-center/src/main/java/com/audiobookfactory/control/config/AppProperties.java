package com.audiobookfactory.control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Path storageRoot = Path.of("./data");
    private String workerEnrollToken;
    private String accessToken;

    public Path storageRoot() {
        return storageRoot;
    }

    public String workerEnrollToken() {
        return workerEnrollToken;
    }

    public String accessToken() {
        return StringUtils.hasText(accessToken) ? accessToken : null;
    }

    public void setStorageRoot(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    public void setWorkerEnrollToken(String workerEnrollToken) {
        this.workerEnrollToken = workerEnrollToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }
}
