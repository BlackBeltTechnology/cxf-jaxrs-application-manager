package hu.blackbelt.cxf;

import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;
import org.osgi.util.tracker.ServiceTracker;

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

    private ApplicationTracker applicationTracker;
    private SharedProviderTracker sharedProviderTracker;
    private ProviderTracker providerTracker;

    private final Map<Object, Application> applications = new HashMap<>();
    private final Map<Application, Map<String, Configuration>> configurations = new HashMap<>();
    private final Map<Object, AtomicInteger> semaphores = new HashMap<>();
    private final Map<Object, Server> servers = new HashMap<>();
    private final Map<Object, List<Object>> providerComponents = new HashMap<>();
    private final Map<Object, List<Object>> providerObjects = new HashMap<>();

    private final Map<Object, Object> globalProviders = new LinkedHashMap<>();

    @Activate
    void start(final BundleContext context) {
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
        for (final Iterator<Map.Entry<Object, Server>> it = servers.entrySet().iterator(); it.hasNext(); ) {
            stopApplication(it.next().getKey());
        }
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
        public Application addingService(ServiceReference<Application> reference) {
            final Application application = super.addingService(reference);
            final Object id = reference.getProperty(Constants.SERVICE_ID);

            applications.put(id, application);
            providerComponents.put(id, new LinkedList<>());
            providerObjects.put(id, new LinkedList<>());

            if (log.isDebugEnabled()) {
                log.debug("Register JAX-RS application: " + application + "; id = " + id);
            }

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
                        providerObjects.get(id).add(provider);
                    } catch (ClassNotFoundException ex) {
                        log.error("Missing JAX-RS provider class: " + providerName, ex);
                    } catch (InstantiationException | IllegalAccessException ex) {
                        log.error("Unable to create provider object", ex);
                    }
                }
            }

            final String providerComponentList = (String) reference.getProperty(JAXRS_PROVIDER_COMPONENTS);
            if (providerComponentList != null) {
                final String[] providerClasses = providerComponentList.split("\\s*,\\s*");
                semaphores.put(id, new AtomicInteger(providerClasses.length));
                for (final String providerName : providerClasses) {
                    if (log.isDebugEnabled()) {
                        log.debug("Creating JAX-RS provider component: " + providerName);
                    }

                    try {
                        final Configuration cfg = configAdmin.createFactoryConfiguration(providerName, "?");
                        cfg.update(prepareConfiguration(reference, id));
                        providerConfigs.put(providerName, cfg);
                    } catch (IOException ex) {
                        log.error("Unable to create provider component", ex);
                    }
                }
            } else {
                startApplication(id);
            }

            configurations.put(application, providerConfigs);
            return application;
        }

        @Override
        public void modifiedService(ServiceReference<Application> reference, Application service) {
            super.modifiedService(reference, service);
            // TODO - check list of providerComponents (recreate JAX-RS server if changed)
            final Map<String, Configuration> providers = configurations.get(service);
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
        public void removedService(ServiceReference<Application> reference, Application service) {
            final Object id = reference.getProperty(Constants.SERVICE_ID);
            stopApplication(id);

            super.removedService(reference, service);
            final Map<String, Configuration> providerConfigs = configurations.get(service);
            if (providerConfigs != null) {
                providerConfigs.forEach((k, v) -> {
                    try {
                        v.delete();
                    } catch (IOException ex) {
                        log.warn("Unable to delete JAX-RS provider configuration", ex);
                    }
                });
            }
            configurations.remove(service);

            applications.remove(id);
            providerComponents.remove(id);
            providerObjects.remove(id);
            semaphores.remove(id);
        }
    }

    private class ProviderTracker extends ServiceTracker<Object, Object> {
        ProviderTracker(final BundleContext context) throws InvalidSyntaxException {
            super(context, context.createFilter("(" + GENERATED_BY_KEY + "=" + GENERATED_BY_VALUE + ")"), null);
        }

        @Override
        public Object addingService(ServiceReference<Object> reference) {
            final Object service = super.addingService(reference);
            if (service.getClass().isAnnotationPresent(Provider.class)) {
                final Object id = reference.getProperty(APPLICATION_ID);
                addedLocalProvider(id, service);
            }
            return service;
        }

        @Override
        public void removedService(ServiceReference<Object> reference, Object service) {
            if (service.getClass().isAnnotationPresent(Provider.class)) {
                final Object id = reference.getProperty(APPLICATION_ID);
                removedLocalProvider(id, service);
            }
            super.removedService(reference, service);
        }
    }

    private class SharedProviderTracker extends ServiceTracker<Object, Object> {
        SharedProviderTracker(final BundleContext context) throws InvalidSyntaxException {
            super(context, context.createFilter("(" + Constants.OBJECTCLASS + "=*)"), null);
        }

        @Override
        public Object addingService(ServiceReference<Object> reference) {
            final Object service = super.addingService(reference);
            if (!Objects.equals(reference.getProperty(GENERATED_BY_KEY), GENERATED_BY_VALUE) && service.getClass().isAnnotationPresent(Provider.class)) {
                final Object id = reference.getProperty(Constants.SERVICE_ID);
                final String filter = (String) reference.getProperty(APPLICATIONS_FILTER);
                if (filter != null) {
                    addedSharedProvider(id, service, filter);
                } else {
                    addedGlobalProvider(reference.getProperty(Constants.SERVICE_ID), service);
                }
            }
            return service;
        }

        @Override
        public void modifiedService(ServiceReference<Object> reference, Object service) {
            super.modifiedService(reference, service);
            if (!Objects.equals(reference.getProperty(GENERATED_BY_KEY), GENERATED_BY_VALUE) && service.getClass().isAnnotationPresent(Provider.class)) {
                final Object id = reference.getProperty(Constants.SERVICE_ID);
                final String filter = (String) reference.getProperty(APPLICATIONS_FILTER);
                if (filter == null && !globalProviders.containsKey(id)) {
                    // change provider to global
                    //removedSharedProvider(id); --> TODO
                    addedGlobalProvider(id, service);
                } else if (filter != null && globalProviders.containsKey(id)) {
                    // change provider to shared
                    removeGlobalProvider(id);
                    addedSharedProvider(id, service, filter);
                }
            }
        }

        @Override
        public void removedService(ServiceReference<Object> reference, Object service) {
            super.removedService(reference, service);
            if (!Objects.equals(reference.getProperty(GENERATED_BY_KEY), GENERATED_BY_VALUE) && service.getClass().isAnnotationPresent(Provider.class)) {
                final Object id = reference.getProperty(Constants.SERVICE_ID);
                final String filter = (String) reference.getProperty(APPLICATIONS_FILTER);
                if (filter != null) {
                    removedSharedProvider(id);
                } else {
                    removeGlobalProvider(reference.getProperty(Constants.SERVICE_ID));
                }
            }
        }
    }

    private void addedLocalProvider(final Object id, final Object service) {
        providerComponents.get(id).add(service);
        final AtomicInteger semaphore = semaphores.get(id);
        if (semaphore != null) {
            final int missing = semaphore.decrementAndGet();
            if (missing == 0) {
                startApplication(id);
            } else {
                log.debug("Waiting for providerComponents: " + missing);
            }
        } else {
            log.error("Missing semaphore for application: " + id);
        }
    }

    private void removedLocalProvider(final Object id, final Object service) {
        stopApplication(id);
        final AtomicInteger semaphore = semaphores.get(id);
        if (semaphore != null) {
            semaphore.incrementAndGet();
        }
        List<Object> providerList = providerComponents.get(id);
        if (providerList != null) {
            providerList.remove(service);
        }
    }

    private void addedSharedProvider(final Object id, final Object service, final String correlationKey) {
        log.warn("Shared providerComponents are not supported yet.");
    }

    private void removedSharedProvider(final Object id) {
        log.warn("Shared providerComponents are not supported yet.");
    }

    private void addedGlobalProvider(final Object id, final Object service) {
        globalProviders.put(id, service);
        restartAllApplications();
    }

    private void removeGlobalProvider(final Object id) {
        globalProviders.remove(id);
        restartAllApplications();
    }

    private void restartAllApplications() {
        final List<Object> ids = new ArrayList<>(servers.keySet());
        ids.forEach(id -> {
            stopApplication(id);
            startApplication(id);
        });
    }

    private synchronized void startApplication(final Object id) {
        final Application application = applications.get(id);
        final RuntimeDelegate delegate = RuntimeDelegate.getInstance();
        final JAXRSServerFactoryBean serverFactory = delegate.createEndpoint(application, JAXRSServerFactoryBean.class);

        final List<Object> providersToAdd = new LinkedList<>();
        providersToAdd.addAll(globalProviders.values());
        providersToAdd.addAll(providerComponents.get(id));
        providersToAdd.addAll(providerObjects.get(id));

        serverFactory.setProviders(Collections.unmodifiableList(providersToAdd));

        final Server server = serverFactory.create();
        server.start();

        servers.put(id, server);
    }

    private synchronized void stopApplication(final Object id) {
        final Server server = servers.remove(id);
        if (server != null) {
            server.stop();
        }
    }
}
