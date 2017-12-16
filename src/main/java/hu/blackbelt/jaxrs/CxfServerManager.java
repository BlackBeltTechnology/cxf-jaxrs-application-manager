package hu.blackbelt.jaxrs;

import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.osgi.service.component.annotations.Component;

import javax.ws.rs.core.Application;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component(property = ServerManager.ALIAS_KEY + "=" + CxfServerManager.ALIAS_VALUE)
public class CxfServerManager implements ServerManager {

    public static final String ALIAS_VALUE = "cxf";

    private final Map<Long, Server> servers = new ConcurrentHashMap<>();
    private final Map<Long, Application> applications = new ConcurrentHashMap<>();

    @Override
    public void startApplication(final Long applicationId, final Application application, final List<Object> providers) {
        applications.put(applicationId, application);

        final RuntimeDelegate delegate = RuntimeDelegate.getInstance();
        final JAXRSServerFactoryBean serverFactory = delegate.createEndpoint(application, JAXRSServerFactoryBean.class);

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
    public void restartApplications(final Collection<Long> applicationIds, final Map<Long, List<Object>> providers) {
        applicationIds.forEach(applicationId -> {
            final Application application = stopApplication(applicationId);
            startApplication(applicationId, application, providers.get(applicationId));
        });
    }

    @Override
    public void restartAllApplications(final Map<Long, List<Object>> providers) {
        restartApplications(new ArrayList<>(servers.keySet()), providers);
    }

    @Override
    public void shutdown() {
        final Set<Long> applicationIds = new TreeSet<>(servers.keySet());
        applicationIds.forEach(this::stopApplication);
    }
}
