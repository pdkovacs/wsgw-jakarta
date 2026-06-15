package io.github.pdkovacs.wsgw.filters;

import io.github.pdkovacs.wsgw.RequestToApp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class ConnIdRequestWrapper extends HttpServletRequestWrapper {
    private final String connectionId;

    ConnIdRequestWrapper(HttpServletRequest request, String connectionId) {
        super(request);
        this.connectionId = connectionId;
    }

    @Override
    public String getHeader(String name) {
        return RequestToApp.CONN_ID_HEADER.equalsIgnoreCase(name)
                ? connectionId
                : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return RequestToApp.CONN_ID_HEADER.equalsIgnoreCase(name)
                ? Collections.enumeration(List.of(connectionId))
                : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {   // ← without this, the id is invisible to modifyHandshake
        var names = new ArrayList<>(Collections.list(super.getHeaderNames()));
        names.add(RequestToApp.CONN_ID_HEADER);
        return Collections.enumeration(names);
    }
}
