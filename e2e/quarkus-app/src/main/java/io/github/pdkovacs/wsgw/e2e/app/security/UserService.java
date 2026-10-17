package io.github.pdkovacs.wsgw.e2e.app.security;

import io.github.pdkovacs.wsgw.e2e.app.config.AppConfig;
import io.github.pdkovacs.wsgw.e2e.app.config.PasswordCredentials;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class UserService {

    private final AppConfig appConfig;

    public UserService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public String getDisplayName(String userId) {
        return userId;
    }

    public UserInfo getUserInfo(String userId) {
        return new UserInfo(userId, getDisplayName(userId));
    }

    public List<UserInfo> getUsers() {
        List<PasswordCredentials> creds = appConfig.passwordCredentialsList();
        if (creds.isEmpty()) {
            throw new IllegalStateException("no password credentials");
        }
        return creds.stream()
                .map(c -> new UserInfo(c.username(), c.username()))
                .toList();
    }
}
