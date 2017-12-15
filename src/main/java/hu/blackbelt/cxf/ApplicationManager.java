package hu.blackbelt.cxf;

import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;
import org.osgi.util.tracker.ServiceTracker;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.RuntimeDelegate;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component(immediate = true)
@Slf4j
public class ApplicationManager {

    private static final String JAXRS_PROVIDER_COMPONENTS = "jaxrs.provider.components";
    private static final String JAXRS_PROVIDER_OBJECTS = "jaxrs.provider.objects";
    private static final String APPLICATION_ID = "application.id";

    private static final String GENERATED_BY_KEY = "__generated.by";
    private static final String GENERATED_BY_VALUE = UUID.randomUUID().toString();

    private static final String APPLICATIONS_FILTER = "applications.filter";

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    ConfigurationAdmin configAdmin;

    private BundleContext context;

    private ApplicationTracker applicationTracker;
    private SharedProviderTracker sharedProviderTracker;
    private ProviderTracker providerTracker;

    private final Map<Long, Application> applications = new HashMap<>();
    private final Map<Application, Map<String, Configuration>> configurations = new HashMap<>();
    private final Map<Long, AtomicInteger> semaphores = new HashMap<>();
    private final Map<Long, Server> servers = new HashMap<>();
    private final Map<Long, List<Object>> providerComponents = new HashMap<>();
    private final Map<Long, List<Object>> providerObjects = new HashMap<>();
    private final Map<Long, Map<Long, Object>> sharedProviders = new HashMap<>();
    private final Map<Object, Object> globalProviders = new LinkedHashMap<>();

    @Activate
    void start(final BundleContext context) {
        this.context = context;
        applicationTracker = new ApplicationTracker(context);
        applicationTracker.open();

        try {
            sharedProviderTracker = new SharedProviderTracker(context);
            sharedProviderTracker.open();
        } catch (InvalidSyntaxException ex) {
            throw new IllegalStateException("Unable to start shared JAX-RS provider tracker", ex);
        }

        try {
            providerTracker = new ProviderTracker(context);
            providerTracker.open();
        } catch (InvalidSyntaxException ex) {
            throw new IllegalStateException("Unable to start JAX-RS provider tracker", ex);
        }
    }

    @Modified
    void update() {
        // do not restart application manager
    }

    @Deactivate
    void stop() {
        if (sharedProviderTracker != null) {
            sharedProviderTracker.close();
            sharedProviderTracker = null;
        }
        if (providerTracker != null) {
            providerTracker.close();
            providerTracker = null;
        }
        if (applicationTracker != null) {
            applicationTracker.close();
            applicationTracker = null;
        }
        for (final Iterator<Map.Entry<Long, Server>> it = servers.entrySet().iterator(); it.hasNext(); ) {
            stopApplication(it.next().getKey());
        }
        context = null;
    }

    private static Dictionary<String, Object> prepareConfiguration(final ServiceReference reference, final Object id) {
        final Dictionary<String, Object> dictionary = new Hashtable<>();

        for (final String key : reference.getPropertyKeys()) {
            if (!key.startsWith("service.") && !key.startsWith("component.") && !key.startsWith("felix.") && !key.startsWith("objectClass")) {
                dictionary.put(key, reference.getProperty(key));
            }
        }
        dictionary.put(APPLICATION_ID, id);
        dictionary.put(GENERATED_BY_KEY, GENERATED_BY_VALUE);

        return dictionary;
    }

    private class ApplicationTracker extends ServiceTracker<Application, Application> {
        ApplicationTracker(final BundleContext context) {
            super(context, Application.class, null);
        }

        @Override
        public Application addingService(final ServiceReference<Application> reference) {
            final Long applicationId = (Long) reference.getProperty(Constants.SERVICE_ID);
            providerComponents.put(applicationId, new LinkedList<>());
            providerObjects.put(applicationId, new LinkedList<>());
            sharedProviders.put(applicationId, new HashMap<>());

            final Application application = super.addingService(reference);

            if (!application.getClass().isAnnotationPresent(ApplicationPath.class)) {
                log.warn("No @ApplicationPath found on component, service.id = " + applicationId);
                return application;
            }
            if (log.isDebugEnabled()) {
                log.debug("Register JAX-RS application: " + application + "; id = " + applicationId);
            }
            applications.put(applicationId, application);

            final Map<String, Configuration> providerConfigs = new TreeMap<>();

            final String providerObjectList = (String) reference.getProperty(JAXRS_PROVIDER_OBJECTS);
            if (providerObjectList != null) {
                final String[] providerClasses = providerObjectList.split("\\s*,\\s*");
                for (final String providerName : providerClasses) {
                    if (log.isDebugEnabled()) {
                        log.debug("Creating JAX-RS provider object: " + providerName);
                    }

                    try {
                        final Class clazz = Class.forName(providerName);
                        final Object provider = clazz.newInstance();
                        providerObjects.get(applicationId).add(provider);
                    } catch (ClassNotFoundException ex) {
                        log.error("Missing JAX-RS provider class: " + providerName, ex);
                    } catch (InstantiationException | IllegalAccessException ex) {
                        log.error("Unable to create provider object", ex);
                    }
                }
            }

            // TODO - initialize map of shared providers for the new JAX-RS application

            final String providerComponentList = (String) reference.getProperty(JAXRS_PROVIDER_COMPONENTS);
            if (providerComponentList != null) {
                final String[] providerClasses = providerComponentList.split("\\s*,\\s*");
                semaphores.put(applicationId, new AtomicInteger(providerClasses.length));
                for (final String providerName : providerClasses) {
                    if (log.isDebugEnabled()) {
                        log.debug("Creating JAX-RS provider component: " + providerName);
                    }

                    try {
                        final Configuration cfg = configAdmin.createFactoryConfiguration(providerName, "?");
                        cfg.update(prepareConfiguration(reference, applicationId));
                        providerConfigs.put(providerName, cfg);
                    } catch (IOException ex) {
                        log.error("Unable to create provider component", ex);
                    }
                }
            } else {
                startApplication(applicationId);
            }

            configurations.put(application, providerConfigs);
            return application;
        }

        @Override
        public void modifiedService(final ServiceReference<Application> reference, final Application application) {
            super.modifiedService(reference, application);
            if (!application.getClass().isAnnotationPresent(ApplicationPath.class)) {
                return;
            }

            // TODO - check list of providerComponents (recreate JAX-RS server if changed)
            final Map<String, Configuration> providers = configurations.get(application);
            if (providers != null) {
                providers.forEach((k, v) -> {
                    try {
                        // TODO - update only if proprties are changed (checksum)
                        v.update(prepareConfiguration(reference, reference.getProperty(Constants.SERVICE_ID)));
                    } catch (IOException ex) {
                        log.warn("Unable to update JAX-RS provider configuration", ex);
                    }
                });
            }
        }

        @Override
        public void removedService(final ServiceReference<Application> reference, final Application application) {
            super.removedService(reference, application);
            if (!application.getClass().isAnnotationPresent(ApplicationPath.class)) {
                return;
            }

            final Long applicationId = (Long) reference.getProperty(Constants.SERVICE_ID);
            stopApplication(applicationId);

            final Map<String, Configuration> providerConfigs = configurations.get(application);
            if (providerConfigs != null) {
                providerConfigs.forEach((k, v) -> {
                    try {
                        v.delete();
                    } catch (IOException ex) {
                        log.warn("Unable to delete JAX-RS provider configuration", ex);
                    }
                });
            }
            configurations.remove(application);

            applications.remove(applicationId);
            providerComponents.remove(applicationId);
            providerObjects.remove(applicationId);
            sharedProviders.remove(applicationId);
            semaphores.remove(applicationId);
        }
    }

    private class ProviderTracker extends ServiceTracker<Object, Object> {
        ProviderTracker(final BundleContext context) throws InvalidSyntaxException {
            super(context, context.createFilter("(" + GENERATED_BY_KEY + "=" + GENERATED_BY_VALUE + ")"), null);
        }

        @Override
        public Object addingService(final ServiceReference<Object> reference) {
            final Object provider = super.addingService(reference);
            if (provider.getClass().isAnnotationPresent(Provider.class)) {
                final Long applicationId = (Long) reference.getProperty(APPLICATION_ID);
                addedLocalProvider(applicationId, provider);
            }
            return provider;
        }

        @Override
        public void removedService(final ServiceReference<Object> reference, final Object provider) {
            if (provider.getClass().isAnnotationPresent(Provider.class)) {
                final Long applicationId = (Long) reference.getProperty(APPLICATION_ID);
                removedLocalProvider(applicationId, provider);
            }
            super.removedService(reference, provider);
        }
    }

    private class SharedProviderTracker extends ServiceTracker<Object, Object> {
        SharedProviderTracker(final BundleContext context) throws InvalidSyntaxException {
            super(context, context.createFilter("(" + Constants.OBJECTCLASS + "=*)"), null);
        }

        @Override
        public Object addingService(ServiceReference<Object> reference) {
            final Object provider = super.addingService(reference);
            if (!Objects.equals(reference.getProperty(GENERATED_BY_KEY), GENERATED_BY_VALUE) && provider.getClass().isAnnotationPresent(Provider.class)) {
                final Long providerId = (Long) reference.getProperty(Constants.SERVICE_ID);
                final String filter = (String) reference.getProperty(APPLICATIONS_FILTER);
                if (filter != null) {
                    final Collection<Long> changedApplicationIds = addedSharedProvider(providerId, provider, filter);
                    restartApplications(changedApplicationIds);
                } else {
                    addedGlobalProvider(providerId, provider);
                    restartAllApplications();
                }
            }
            return provider;
        }

        @Override
        public void modifiedService(ServiceReference<Object> reference, Object provider) {
            super.modifiedService(reference, provider);
            if (!Objects.equals(reference.getProperty(GENERATED_BY_KEY), GENERATED_BY_VALUE) && provider.getClass().isAnnotationPresent(Provider.class)) {
                final Long providerId = (Long) reference.getProperty(Constants.SERVICE_ID);
                final String filter = (String) reference.getProperty(APPLICATIONS_FILTER);
                if (filter == null && !globalProviders.containsKey(providerId)) {
                    // change provider to global
                    removedSharedProvider(providerId);
                    addedGlobalProvider(providerId, provider);
                    restartAllApplications();
                } else if (filter != null && globalProviders.containsKey(providerId)) {
                    // change provider to shared
                    removeGlobalProvider(providerId);
                    addedSharedProvider(providerId, provider, filter);
                    restartAllApplications();
                } else if (filter != null) {
                    Collection<Long> changedApplicationIds = changedSharedProvider(providerId, provider, filter);
                    restartApplications(changedApplicationIds);
                }
            }
        }

        @Override
        public void removedService(ServiceReference<Object> reference, Object provider) {
            super.removedService(reference, provider);
            if (!Objects.equals(reference.getProperty(GENERATED_BY_KEY), GENERATED_BY_VALUE) && provider.getClass().isAnnotationPresent(Provider.class)) {
                final Long providerId = (Long) reference.getProperty(Constants.SERVICE_ID);
                final String filter = (String) reference.getProperty(APPLICATIONS_FILTER);
                if (filter != null) {
                    final Collection<Long> changedApplicationIds = removedSharedProvider(providerId);
                    restartApplications(changedApplicationIds);
                } else {
                    removeGlobalProvider(providerId);
                    restartAllApplications();
                }
            }
        }
    }

    private void addedLocalProvider(final Long applicationId, final Object provider) {
        providerComponents.get(applicationId).add(provider);
        final AtomicInteger semaphore = semaphores.get(applicationId);
        if (semaphore != null) {
            final int missing = semaphore.decrementAndGet();
            if (missing == 0) {
                startApplication(applicationId);
            } else {
                log.debug("Waiting for providerComponents: " + missing);
            }
        } else {
            log.error("Missing semaphore for application: " + applicationId);
        }
    }

    private void removedLocalProvider(final Long applicationId, final Object provider) {
        stopApplication(applicationId);
        final AtomicInteger semaphore = semaphores.get(applicationId);
        if (semaphore != null) {
            semaphore.incrementAndGet();
        }
        List<Object> providerList = providerComponents.get(applicationId);
        if (providerList != null) {
            providerList.remove(provider);
        }
    }

    private synchronized Collection<Long> addedSharedProvider(final Long providerId, final Object provider, final String filter) {
        final List<Long> changed = new LinkedList<>();
        try {
            final Collection<ServiceReference<Application>> srs = context.getServiceReferences(Application.class, filter);
            if (srs != null) {
                for (final ServiceReference<Application> sr : srs) {
                    final Long applicationId = (Long) sr.getProperty(Constants.SERVICE_ID);
                    sharedProviders.get(applicationId).put(providerId, provider);
                    changed.add(applicationId);
                }
            }
        } catch (InvalidSyntaxException ex) {
            log.error("Invalid filter in shared JAX-RS provider, service.id = " + providerId, ex);
        }
        return changed;
    }

    private synchronized Collection<Long> changedSharedProvider(final Long providerId, final Object provider, final String filter) {
        final List<Long> changed = new LinkedList<>();
        final Set<Long> filterResult = new HashSet<>();
        try {
            final Collection<ServiceReference<Application>> srs = context.getServiceReferences(Application.class, filter);
            if (srs != null) {
                for (final ServiceReference<Application> sr : srs) {
                    final Long applicationId = (Long) sr.getProperty(Constants.SERVICE_ID);
                    filterResult.add(applicationId);
                }
            }
        } catch (InvalidSyntaxException ex) {
            log.error("Invalid filter in shared JAX-RS provider, service.id = " + providerId, ex);
        }
        for (final Map.Entry<Long, Map<Long, Object>> apps : sharedProviders.entrySet()) {
            final Long applicationId = apps.getKey();
            final Map<Long, Object> providers = apps.getValue();
            if (providers.containsKey(providerId) && !filterResult.contains(applicationId)) {
                // provider should be removed
                providers.remove(providerId);
                changed.add(applicationId);
            } else if (!providers.containsKey(providerId) && filterResult.contains(applicationId)) {
                // provider should be added
                providers.put(providerId, provider);
                changed.add(applicationId);
            }
        }
        return changed;
    }

    private synchronized Collection<Long> removedSharedProvider(final Long providerId) {
        final List<Long> changed = new LinkedList<>();
        for (final Map.Entry<Long, Map<Long, Object>> apps : sharedProviders.entrySet()) {
            final Long applicationId = apps.getKey();
            final Map<Long, Object> providers = apps.getValue();
            final Object provider = providers.remove(providerId);
            if (provider != null) {
                changed.add(applicationId);
            }
        }
        return changed;
    }

    private void addedGlobalProvider(final Long providerId, final Object provider) {
        globalProviders.put(providerId, provider);
    }

    private void removeGlobalProvider(final Long providerId) {
        globalProviders.remove(providerId);
    }

    private void restartApplications(final Collection<Long> applicationIds) {
        applicationIds.forEach(id -> {
            stopApplication(id);
            startApplication(id);
        });
    }

    private void restartAllApplications() {
        restartApplications(new ArrayList<>(servers.keySet()));
    }

    private synchronized void startApplication(final Long applicationId) {
        final Application application = applications.get(applicationId);
        final RuntimeDelegate delegate = RuntimeDelegate.getInstance();
        final JAXRSServerFactoryBean serverFactory = delegate.createEndpoint(application, JAXRSServerFactoryBean.class);

        final List<Object> providersToAdd = new LinkedList<>();
        providersToAdd.addAll(globalProviders.values());
        providersToAdd.addAll(sharedProviders.get(applicationId).values());
        providersToAdd.addAll(providerComponents.get(applicationId));
        providersToAdd.addAll(providerObjects.get(applicationId));

        serverFactory.setProviders(Collections.unmodifiableList(providersToAdd));

        final Server server = serverFactory.create();
        server.start();

        servers.put(applicationId, server);
    }

    private synchronized void stopApplication(final Long applicationId) {
        final Server server = servers.remove(applicationId);
        if (server != null) {
            server.stop();
        }
    }
}
