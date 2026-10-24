package io.github.pdkovacs.wsgw.web;

import jakarta.servlet.http.HttpFilter;
import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

public final class TomcatFilters {

    private TomcatFilters() {
    }

    public static void addFilter(Context ctx, HttpFilter filter, String urlPattern) {
        // The filter name only has to be unique within the context; the URL pattern
        // already is, so use it as the name and avoid a redundant, easily-mismatched arg.
        FilterDef fd = new FilterDef();
        fd.setFilterName(urlPattern);
        fd.setFilter(filter);
        ctx.addFilterDef(fd);
        FilterMap fm = new FilterMap();
        fm.setFilterName(urlPattern);
        fm.addURLPattern(urlPattern);
        ctx.addFilterMap(fm);
    }
}
