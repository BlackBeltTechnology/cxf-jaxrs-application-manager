package hu.blackbelt.jaxrs;

import org.osgi.framework.Bundle;

import javax.ws.rs.core.Application;
import java.util.*;

interface ServerManager {
    String ALIAS_KEY = "alias";

    void startApplication(Long applicationId, Application application, Bundle applicationBundle, List<Object> providers);

    Application stopApplication(Long applicationId);

    void updateApplicationResources(Long applicationId, Application application, List<Object> providers);

    void restartApplications(Collection<Long> applicationIds, Map<Long, List<Object>> providers);

    void restartAllApplications(Map<Long, List<Object>> providers);

    void shutdown();
}
