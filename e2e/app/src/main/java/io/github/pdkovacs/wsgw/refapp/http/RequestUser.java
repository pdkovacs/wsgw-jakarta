package io.github.pdkovacs.wsgw.refapp.http;

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
