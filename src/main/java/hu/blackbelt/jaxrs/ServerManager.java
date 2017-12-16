package hu.blackbelt.jaxrs;

import javax.ws.rs.core.Application;
import java.util.*;

public interface ServerManager {
    String ALIAS_KEY = "alias";

    void startApplication(final Long applicationId, final Application application, final List<Object> providers);

    Application stopApplication(final Long applicationId);

    void restartApplications(final Collection<Long> applicationIds, final Map<Long, List<Object>> providers);

    void restartAllApplications(final Map<Long, List<Object>> providers);

    void shutdown();
}
