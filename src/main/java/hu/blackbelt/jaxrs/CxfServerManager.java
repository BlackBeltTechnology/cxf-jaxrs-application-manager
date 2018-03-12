package hu.blackbelt.jaxrs;

import hu.blackbelt.jaxrs.application.BasicApplication;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.RuntimeDelegate;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component(property = ServerManager.ALIAS_KEY + "=" + CxfServerManager.ALIAS_VALUE)
@Designate(ocd = CxfServerManager.Config.class)
@Slf4j
public class CxfServerManager implements ServerManager {

    @ObjectClassDefinition
    public @interface Config {

        @AttributeDefinition(required = false, name = "Skip default JSON provider registration", description = "Do not use CXF JSON provider as default message body reader.", type = AttributeType.BOOLEAN)
        boolean skipDefaultJsonProviderRegistration();

        @AttributeDefinition(required = false, name = "WADL service description available", type = AttributeType.BOOLEAN)
        boolean wadlServiceDescriptionAvailable();

        @AttributeDefinition(required = false, name = "IN interceptors filter expression")
        String interceptors_in_components();

        @AttributeDefinition(required = false, name = "OUT interceptors filter expression")
        String interceptors_out_components();

        @AttributeDefinition(required = false, name = "FAULT interceptors filter expression")
        String interceptors_fault_components();
    }

    public static final String ALIAS_VALUE = "cxf";

    private static final String APPLICATION_PATH = "applicationPath";

    private final Map<Long, Server> servers = new ConcurrentHashMap<>();
    private final Map<Long, Application> applications = new ConcurrentHashMap<>();
    private final Map<Long, List<Object>> applicationProviders = new ConcurrentHashMap<>();

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    private ConfigurationAdmin configAdmin;

    private static final String DEFAULT_BUS_ID = "DEFAULT_CXF_BUS_FOR_JAXRS_APPLICATIONS";
    private Configuration cxfContextConfig;

    @Activate
    void start(final Config config) {
        try {
            cxfContextConfig = configAdmin.createFactoryConfiguration(CxfContext.class.getName(), "?");
            final Dictionary<String, Object> properties = setProperties(config, new Hashtable<>());
            properties.put("busId", DEFAULT_BUS_ID);
            cxfContextConfig.update(properties);
        } catch (IOException ex) {
            log.error("Unable to create default CXF bus");
        }
    }

    @Modified
    void update(final Config config) {
        try {
            cxfContextConfig.update(setProperties(config, cxfContextConfig.getProperties()));
        } catch (IOException ex) {
            log.error("Unable to update default CXF bus");
        }
    }

    private static Dictionary<String, Object> setProperties(final Config config, final Dictionary<String, Object> properties) {
        properties.put("skipDefaultJsonProviderRegistration", config.skipDefaultJsonProviderRegistration());
        properties.put("wadlServiceDescriptionAvailable", config.wadlServiceDescriptionAvailable());
        if (config.interceptors_in_components() != null) {
            properties.put("interceptors.in.components", config.interceptors_in_components());
        } else {
            properties.remove("interceptors.in.components");
        }
        if (config.interceptors_out_components() != null) {
            properties.put("interceptors.out.components", config.interceptors_out_components());
        } else {
            properties.remove("interceptors.out.components");
        }
        if (config.interceptors_fault_components() != null) {
            properties.put("interceptors.fault.components", config.interceptors_fault_components());
        } else {
            properties.remove("interceptors.fault.components");
        }
        return properties;
    }

    @Deactivate
    void stop() {
        unregister("(&(service.factoryPid=" + CxfContext.class.getName() + ")(busId=" + DEFAULT_BUS_ID + "))");
        cxfContextConfig = null;
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

            if (log.isTraceEnabled()) {
                log.trace("IN interceptors: {}", cxfContext.getInInterceptors());
                log.trace("OUT interceptors: {}", cxfContext.getOutInterceptors());
                log.trace("FAULT interceptors: {}", cxfContext.getFaultInterceptors());
            }

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
        log.trace("UPDATE JAX-RS application resources: " + applicationId);
        applications.put(applicationId, application);
        restartApplications(Collections.singleton(applicationId), Collections.singletonMap(applicationId, providers));
    }

    @Override
    public synchronized Application stopApplication(final Long applicationId) {
        log.trace("STOP JAX-RS application: " + applicationId);
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
            log.trace("RESTART JAX-RS application: " + applicationId);
            final Application application = stopApplication(applicationId);
            startApplication(applicationId, application, providers != null ? providers.get(applicationId) : null);
        });
    }

    @Override
    public void restartAllApplications(final Map<Long, List<Object>> providers) {
        log.trace("RESTART all JAX-RS applications");
        restartApplications(new ArrayList<>(applications.keySet()), providers);
    }

    @Override
    public void shutdown() {
        final Set<Long> applicationIds = new TreeSet<>(servers.keySet());
        applicationIds.forEach(this::stopApplication);
    }
}
