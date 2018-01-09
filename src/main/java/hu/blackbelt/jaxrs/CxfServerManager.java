package hu.blackbelt.jaxrs;

import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.osgi.service.component.annotations.Component;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component(property = ServerManager.ALIAS_KEY + "=" + CxfServerManager.ALIAS_VALUE)
@Slf4j
public class CxfServerManager implements ServerManager {

    public static final String ALIAS_VALUE = "cxf";

    private final Map<Long, Server> servers = new ConcurrentHashMap<>();
    private final Map<Long, Application> applications = new ConcurrentHashMap<>();
    private final Map<Long, String> addresses = new ConcurrentHashMap<>();

    @Override
    public void startApplication(final Long applicationId, final String applicationPath, final Application application, final List<Object> providers) {
        applications.put(applicationId, application);

        final RuntimeDelegate delegate = RuntimeDelegate.getInstance();
        final JAXRSServerFactoryBean serverFactory = delegate.createEndpoint(application, JAXRSServerFactoryBean.class);

        if (applicationPath != null) {
            serverFactory.setAddress(applicationPath);
            addresses.put(applicationId, applicationPath);
        } else if (!application.getClass().isAnnotationPresent(ApplicationPath.class)) {
            log.warn("No @ApplicationPath found on component, service.id = " + applicationId);
        }

        serverFactory.setProviders(providers);

        final Server server = serverFactory.create();
        server.start();

        servers.put(applicationId, server);
    }

    @Override
    public Application stopApplication(final Long applicationId) {
        final Server server = servers.remove(applicationId);
        if (server != null) {
            server.stop();
        }
        return applications.remove(applicationId);
    }

    @Override
    public void restartApplications(final Collection<Long> applicationIds, final Map<Long, String> applicationPaths, final Map<Long, List<Object>> providers) {
        if (applicationPaths != null) {
            addresses.putAll(applicationPaths);
        }
        applicationIds.forEach(applicationId -> {
            final Application application = stopApplication(applicationId);
            startApplication(applicationId, addresses.get(applicationId), application, providers.get(applicationId));
        });
    }

    @Override
    public void restartAllApplications(final Map<Long, List<Object>> providers) {
        restartApplications(new ArrayList<>(servers.keySet()), null, providers);
    }

    @Override
    public void shutdown() {
        final Set<Long> applicationIds = new TreeSet<>(servers.keySet());
        applicationIds.forEach(this::stopApplication);
    }
}
