package hu.blackbelt.jaxrs;

import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.Bus;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component(property = ServerManager.ALIAS_KEY + "=" + CxfServerManager.ALIAS_VALUE)
@Designate(ocd = CxfServerManager.Config.class)
@Slf4j
public class CxfServerManager implements ServerManager {

    @ObjectClassDefinition
    public @interface Config {

        @AttributeDefinition(required = false, name = "Skip default JSON provider registration", description = "Do not use CXF JSON provider as default message body reader.", type = AttributeType.BOOLEAN)
        boolean skipDefaultJsonProviderRegistration() default SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_DEFAULT;

        @AttributeDefinition(required = false, name = "WADL service description available", type = AttributeType.BOOLEAN)
        boolean wadlServiceDescriptionAvailable() default WADL_SERVICE_DESCRIPTION_AVAILABLE_DEFAULT;
    }

    private static final boolean SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_DEFAULT = true;
    private static final String SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_KEY = "skip.default.json.provider.registration";
    private Boolean skipDefaultJsonProviderRegistration;

    private static final boolean WADL_SERVICE_DESCRIPTION_AVAILABLE_DEFAULT = true;
    private static final String WADL_SERVICE_DESCRIPTION_AVAILABLE_KEY = "wadl.service.description.available";
    private Boolean wadlServiceDescriptionAvailable;

    public static final String ALIAS_VALUE = "cxf";

    private static final String APPLICATION_PATH = "applicationPath";

    private final Map<Long, Server> servers = new ConcurrentHashMap<>();
    private final Map<Long, Application> applications = new ConcurrentHashMap<>();

    private BundleContext context;

    private final Map<String, ServiceRegistration<Bus>> busRegistrations = new ConcurrentHashMap<>();

    @Activate
    void start(final BundleContext context, final Config config) {
        this.context = context;
        skipDefaultJsonProviderRegistration = config.skipDefaultJsonProviderRegistration();
        wadlServiceDescriptionAvailable = config.wadlServiceDescriptionAvailable();
    }

    void stop() {
        busRegistrations.forEach((id, sr) -> sr.unregister());
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

        serverFactory.setProviders(providers);
        final Bus bus = serverFactory.getBus();
        if (bus != null) {
            final String id = bus.getId();
            if (id != null && !busRegistrations.containsKey(id)) {
                if (skipDefaultJsonProviderRegistration != null) {
                    bus.setProperty(SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_KEY, skipDefaultJsonProviderRegistration);
                }
                if (wadlServiceDescriptionAvailable != null) {
                    bus.setProperty(WADL_SERVICE_DESCRIPTION_AVAILABLE_KEY, wadlServiceDescriptionAvailable);
                }
                if (log.isDebugEnabled()) {
                    log.debug("Created CXF bus: {} [{}={}; {}={}]", id, SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_KEY, skipDefaultJsonProviderRegistration, WADL_SERVICE_DESCRIPTION_AVAILABLE_KEY, wadlServiceDescriptionAvailable);
                }
                log.info("BUS properties: " + bus.getProperties());
                final Dictionary<String, Object> props = new Hashtable<>();
                props.put("id", id);
                final ServiceRegistration<Bus> busServiceRegistration = context.registerService(Bus.class, bus, props);
                busRegistrations.put(id, busServiceRegistration);
            }
        }

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
