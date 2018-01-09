package hu.blackbelt.jaxrs;

import javax.ws.rs.core.Application;
import java.util.*;

interface ServerManager {
    String ALIAS_KEY = "alias";

    void startApplication(Long applicationId, String applicationPath, Application application, List<Object> providers);

    Application stopApplication(Long applicationId);

    void restartApplications(Collection<Long> applicationIds, Map<Long, String> applicationPaths, Map<Long, List<Object>> providers);

    void restartAllApplications(Map<Long, List<Object>> providers);

    void shutdown();
}
