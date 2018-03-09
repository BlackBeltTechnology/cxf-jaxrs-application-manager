package hu.blackbelt.jaxrs;

import hu.blackbelt.jaxrs.application.BasicApplication;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.message.Message;
import org.osgi.framework.*;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.util.tracker.ServiceTracker;

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

        @AttributeDefinition(required = false, name = "IN interceptors filter expression")
        String interceptors_in_components();

        @AttributeDefinition(required = false, name = "OUT interceptors filter expression")
        String interceptors_out_components();

        @AttributeDefinition(required = false, name = "FAULT interceptors filter expression")
        String interceptors_fault_components();
    }

    private static final boolean SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_DEFAULT = true;
    private static final String SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_KEY = "skip.default.json.provider.registration";
    private Boolean skipDefaultJsonProviderRegistration;

    private static final boolean WADL_SERVICE_DESCRIPTION_AVAILABLE_DEFAULT = true;
    private static final String WADL_SERVICE_DESCRIPTION_AVAILABLE_KEY = "wadl.service.description.available";
    private Boolean wadlServiceDescriptionAvailable;

    private String inInterceptorsFilter;
    private String outInterceptorsFilter;
    private String faultInterceptorsFilter;

    private List<Interceptor<? extends Message>> inInterceptors = new LinkedList<>();
    private List<Interceptor<? extends Message>> outInterceptors = new LinkedList<>();
    private List<Interceptor<? extends Message>> faultInterceptors = new LinkedList<>();

    private InterceptorTracker inInterceptorTracker;
    private InterceptorTracker outInterceptorTracker;
    private InterceptorTracker faultInterceptorTracker;

    public static final String ALIAS_VALUE = "cxf";

    private static final String APPLICATION_PATH = "applicationPath";

    private final Map<Long, Server> servers = new ConcurrentHashMap<>();
    private final Map<Long, Application> applications = new ConcurrentHashMap<>();
    private final Map<Long, List<Object>> applicationProviders = new ConcurrentHashMap<>();

    private BundleContext context;

    private final Map<String, ServiceRegistration<Bus>> busRegistrations = new ConcurrentHashMap<>();

    @Activate
    void start(final BundleContext context, final Config config) {
        this.context = context;

        inInterceptorsFilter = config.interceptors_in_components();
        if (inInterceptorsFilter != null) {
            try {
                inInterceptorTracker = new InterceptorTracker(context, inInterceptorsFilter, inInterceptors);
                inInterceptorTracker.open();
            } catch (InvalidSyntaxException ex) {
                log.error("Invalid IN interceptor filter, ignore it", ex);
            }
        }
        outInterceptorsFilter = config.interceptors_out_components();
        if (outInterceptorsFilter != null) {
            try {
                outInterceptorTracker = new InterceptorTracker(context, outInterceptorsFilter, outInterceptors);
                outInterceptorTracker.open();
            } catch (InvalidSyntaxException ex) {
                log.error("Invalid OUT interceptor filter, ignore it", ex);
            }
        }
        faultInterceptorsFilter = config.interceptors_fault_components();
        if (faultInterceptorsFilter != null) {
            try {
                faultInterceptorTracker = new InterceptorTracker(context, faultInterceptorsFilter, faultInterceptors);
                faultInterceptorTracker.open();
            } catch (InvalidSyntaxException ex) {
                log.error("Invalid FAULT interceptor filter, ignore it", ex);
            }
        }

        skipDefaultJsonProviderRegistration = config.skipDefaultJsonProviderRegistration();
        wadlServiceDescriptionAvailable = config.wadlServiceDescriptionAvailable();
    }

    @Modified
    void update(final Config config) {
        // TODO - reconfigure trackers on filter changes
    }

    @Deactivate
    void stop() {
        busRegistrations.forEach((id, sr) -> {
            context.getService(sr.getReference()).shutdown(false);
            sr.unregister();
        });

        if (inInterceptorTracker != null) {
            inInterceptorTracker.close();
            inInterceptorTracker = null;
        }
        if (outInterceptorTracker != null) {
            outInterceptorTracker.close();
            outInterceptorTracker = null;
        }
        if (faultInterceptorTracker != null) {
            faultInterceptorTracker.close();
            faultInterceptorTracker = null;
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
        final String busId = properties != null ? (String) properties.get(BasicApplication.BUS_ID_KEY) : null;
        if (busId != null) {
            final ServiceRegistration<Bus> busRegistration = busRegistrations.get(busId);
            final Bus bus;
            if (busRegistration != null) {
                bus = context.getService(busRegistration.getReference());
            } else {
                bus = BusFactory.newInstance().createBus();
                bus.setId(busId);
            }
            serverFactory.setBus(bus);
        }

        final List<Object> _providers;
        if (providers == null) {
            _providers = applicationProviders.containsKey(applicationId) ? applicationProviders.get(applicationId) : providers;
        } else {
            _providers = providers;
        }
        serverFactory.setProviders(_providers);
        applicationProviders.put(applicationId, _providers);

        serverFactory.setInInterceptors(inInterceptors);
        serverFactory.setOutInterceptors(outInterceptors);
        serverFactory.setOutFaultInterceptors(faultInterceptors);

        final Bus bus = serverFactory.getBus();
        if (bus != null) {
            registerBus(bus);
        }

        final Server server = serverFactory.create();
        if (log.isDebugEnabled()) {
            log.debug("Starting JAX-RS application, service.id = " + applicationId);
        }
        server.start();

        servers.put(applicationId, server);
    }

    private void registerBus(final Bus bus) {
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
            final Dictionary<String, Object> props = new Hashtable<>();
            props.put("id", id);
            final ServiceRegistration<Bus> busServiceRegistration = context.registerService(Bus.class, bus, props);
            busRegistrations.put(id, busServiceRegistration);
        }
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
        restartApplications(new ArrayList<>(servers.keySet()), providers);
    }

    @Override
    public void shutdown() {
        final Set<Long> applicationIds = new TreeSet<>(servers.keySet());
        applicationIds.forEach(this::stopApplication);
    }

    private class InterceptorTracker extends ServiceTracker<Interceptor<? extends Message>, Interceptor<? extends Message>> {

        final List<Interceptor<? extends Message>> interceptors;

        InterceptorTracker(final BundleContext context, final String filter, final List<Interceptor<? extends Message>> interceptors) throws InvalidSyntaxException {
            super(context, context.createFilter("(&(" + Constants.OBJECTCLASS + "=" + Interceptor.class.getName() + ")" + filter + ")"), null);
            this.interceptors = interceptors;
        }

        @Override
        public Interceptor<? extends Message> addingService(final ServiceReference<Interceptor<? extends Message>> reference) {
            final Interceptor<? extends Message> interceptor = super.addingService(reference);
            if (interceptor != null) {
                interceptors.add(interceptor);
                restartAllApplications(null);
            }
            return interceptor;
        }

        @Override
        public void removedService(final ServiceReference<Interceptor<? extends Message>> reference, final Interceptor<? extends Message> interceptor) {
            super.removedService(reference, interceptor);
            if (interceptor != null) {
                interceptors.remove(interceptor);
                restartAllApplications(null);
            }
        }
    }
}
