package io.github.pdkovacs.wsgw.e2e.app.http;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class RequestUser {

    private String userId;

    public String userId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
