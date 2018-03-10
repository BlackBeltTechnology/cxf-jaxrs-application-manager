package hu.blackbelt.jaxrs;

import hu.blackbelt.jaxrs.application.BasicApplication;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.RuntimeDelegate;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component(property = ServerManager.ALIAS_KEY + "=" + CxfServerManager.ALIAS_VALUE, configurationPolicy = ConfigurationPolicy.IGNORE)
@Slf4j
public class CxfServerManager implements ServerManager {

    public static final String ALIAS_VALUE = "cxf";

    private static final String APPLICATION_PATH = "applicationPath";

    private final Map<Long, Server> servers = new ConcurrentHashMap<>();
    private final Map<Long, Application> applications = new ConcurrentHashMap<>();
    private final Map<Long, List<Object>> applicationProviders = new ConcurrentHashMap<>();

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    private ConfigurationAdmin configAdmin;

    private static final String DEFAULT_BUS_ID = "DEFAULT_CXF_BUS_FOR_JAXRS_APPLICATIONS";

    @Activate
    void start() {
        try {
            final Configuration cfg = configAdmin.createFactoryConfiguration(CxfContext.class.getName(), "?");
            final Dictionary<String, Object> properties = new Hashtable<>();
            properties.put("busId", DEFAULT_BUS_ID);
            cfg.update(properties);
        } catch (IOException ex) {
            log.error("Unable to create default CXF bus");
        }
    }

    @Deactivate
    void stop() {
        unregister("(&(service.factoryPid=" + CxfContext.class.getName() + ")(busId=" + DEFAULT_BUS_ID + "))");
    }

    private void unregister(final String filter) {
        try {
            final Configuration[] cfgsToDelete = configAdmin.listConfigurations(filter);
            if (cfgsToDelete != null) {
                for (final Configuration c : cfgsToDelete) {
                    try {
                        c.delete();
                    } catch (IOException ex2) {
                        log.error("Unable to delete service: " + filter, ex2);
                    }
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("No configuration found: " + filter);
                }
            }
        } catch (InvalidSyntaxException ex) {
            log.error("Invalid filter expression: " + filter, ex);
        } catch (IOException ex) {
            log.error("Unable to list services: " + filter, ex);
        }
    }

    @Override
    public synchronized void startApplication(final Long applicationId, final Application application, final List<Object> providers) {
        if (servers.containsKey(applicationId)) {
            stopApplication(applicationId);
        }

        applications.put(applicationId, application);

        final Set<Class<?>> classes = application.getClasses();
        final Set<Object> singletons = application.getSingletons();

        if ((classes == null || classes.isEmpty()) && (singletons == null || singletons.isEmpty())) {
            log.warn("No resource classes found, do not start JAX-RS application");
            return;
        }

        final RuntimeDelegate delegate = RuntimeDelegate.getInstance();
        final JAXRSServerFactoryBean serverFactory = delegate.createEndpoint(application, JAXRSServerFactoryBean.class);

        final Map<String, Object> properties = application.getProperties();
        final String applicationPath = properties != null ? (String) properties.get(APPLICATION_PATH) : null;
        if (applicationPath != null) {
            serverFactory.setAddress(applicationPath);
        } else if (!application.getClass().isAnnotationPresent(ApplicationPath.class)) {
            log.warn("No @ApplicationPath found on component, service.id = " + applicationId);
        }
        final CxfContext cxfContext = properties != null ? (CxfContext) properties.get(BasicApplication.CONTEXT_PROPERTY_KEY) : null;
        if (cxfContext != null) {
            serverFactory.setBus(cxfContext.getBus());

            serverFactory.setInInterceptors(cxfContext.getInInterceptors());
            serverFactory.setOutInterceptors(cxfContext.getOutInterceptors());
            serverFactory.setOutFaultInterceptors(cxfContext.getFaultInterceptors());
        }

        final List<Object> _providers;
        if (providers == null) {
            _providers = applicationProviders.containsKey(applicationId) ? applicationProviders.get(applicationId) : providers;
        } else {
            _providers = providers;
        }
        serverFactory.setProviders(_providers);
        applicationProviders.put(applicationId, _providers);

        final Server server = serverFactory.create();
        if (log.isDebugEnabled()) {
            log.debug("Starting JAX-RS application, service.id = " + applicationId);
        }
        server.start();

        servers.put(applicationId, server);
    }

    @Override
    public synchronized void updateApplicationResources(final Long applicationId, final Application application, final List<Object> providers) {
        applications.put(applicationId, application);
        restartApplications(Collections.singleton(applicationId), Collections.singletonMap(applicationId, providers));
    }

    @Override
    public synchronized Application stopApplication(final Long applicationId) {
        final Server server = servers.remove(applicationId);
        if (server != null) {
            if (log.isDebugEnabled()) {
                log.debug("Stopping JAX-RS application, service.id = " + applicationId);
            }
            server.stop();
            server.destroy();
        }
        return applications.remove(applicationId);
    }

    @Override
    public void restartApplications(final Collection<Long> applicationIds, final Map<Long, List<Object>> providers) {
        applicationIds.forEach(applicationId -> {
            final Application application = stopApplication(applicationId);
            startApplication(applicationId, application, providers != null ? providers.get(applicationId) : null);
        });
    }

    @Override
    public void restartAllApplications(final Map<Long, List<Object>> providers) {
        restartApplications(new ArrayList<>(applications.keySet()), providers);
    }

    @Override
    public void shutdown() {
        final Set<Long> applicationIds = new TreeSet<>(servers.keySet());
        applicationIds.forEach(this::stopApplication);
    }
}
