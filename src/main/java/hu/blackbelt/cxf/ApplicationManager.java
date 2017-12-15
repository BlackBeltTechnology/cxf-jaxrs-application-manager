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
import javax.ws.rs.ext.RuntimeDelegate;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component(immediate = true)
@Slf4j
public class ApplicationManager {

    private static final String PROVIDERS_KEY = "providers";
    private static final String APPLICATION_ID = "application.id";

    private static final String GENERATED_BY_KEY = "generated.by";
    private static final String GENERATED_BY_VALUE = UUID.randomUUID().toString();

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    ConfigurationAdmin configAdmin;

    private ApplicationTracker applicationTracker;
    private ProviderTracker providerTracker;

    private final Map<Object, Application> applications = new HashMap<>();
    private final Map<Application, Map<String, Configuration>> configurations = new HashMap<>();
    private final Map<Object, AtomicInteger> semaphores = new HashMap<>();
    private final Map<Object, Server> servers = new HashMap<>();
    private final Map<Object, List<Object>> providers = new HashMap<>();

    @Activate
    void start(final BundleContext context) {
        applicationTracker = new ApplicationTracker(context);
        applicationTracker.open();

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
        if (applicationTracker != null) {
            applicationTracker.close();
            applicationTracker = null;
        }
        if (providerTracker != null) {
            providerTracker.close();
            providerTracker = null;
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
            providers.put(id, new LinkedList<>());

            if (log.isDebugEnabled()) {
                log.debug("Register JAX-RS application: " + application + "; id = " + id);
            }

            final Map<String, Configuration> providerConfigs = new TreeMap<>();

            final String providerList = (String) reference.getProperty(PROVIDERS_KEY);
            if (providerList != null) {
                final String[] providerClasses = providerList.split("\\s*,\\s*");
                semaphores.put(id, new AtomicInteger(providerClasses.length));
                for (final String providerName : providerClasses) {
                    if (log.isDebugEnabled()) {
                        log.debug("Creating JAX-RS provider instance: " + providerName);
                    }

                    try {
                        // TODO - create configuration only if not created manually
                        final Configuration cfg = configAdmin.createFactoryConfiguration(providerName, "?");
                        cfg.update(prepareConfiguration(reference, id));
                        providerConfigs.put(providerName, cfg);
                    } catch (IOException ex) {
                        log.error("Unable to create provider", ex);
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
            // TODO - check list of providers (recreate JAX-RS server if changed)
            final Map<String, Configuration> providers = configurations.get(service);
            if (providers != null) {
                providers.forEach((k, v) -> {
                    try {
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
            providers.remove(id);
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
            final Object id = reference.getProperty(APPLICATION_ID);
            addedProvider(id, service);
            return service;
        }

        @Override
        public void removedService(ServiceReference<Object> reference, Object service) {
            final Object id = reference.getProperty(APPLICATION_ID);
            removedProvider(id, service);
            super.removedService(reference, service);
        }
    }

    private void addedProvider(final Object id, final Object service) {
        providers.get(id).add(service);
        final AtomicInteger semaphore = semaphores.get(id);
        if (semaphore != null) {
            final int missing = semaphore.decrementAndGet();
            if (missing == 0) {
                startApplication(id);
            } else {
                log.debug("Waiting for providers: " + missing);
            }
        } else {
            log.error("Missing semaphore for application: " + id);
        }
    }

    private void removedProvider(final Object id, final Object service) {
        stopApplication(id);
        final AtomicInteger semaphore = semaphores.get(id);
        if (semaphore != null) {
            semaphore.incrementAndGet();
        }
        List<Object> providerList = providers.get(id);
        if (providerList != null) {
            providerList.remove(service);
        }
    }

    private void startApplication(final Object id) {
        final Application application = applications.get(id);
        final RuntimeDelegate delegate = RuntimeDelegate.getInstance();
        final JAXRSServerFactoryBean serverFactory = delegate.createEndpoint(application, JAXRSServerFactoryBean.class);

        serverFactory.setProviders(Collections.unmodifiableList(providers.get(id)));

        final Server server = serverFactory.create();
        server.start();

        servers.put(id, server);
    }

    private void stopApplication(final Object id) {
        final Server server = servers.remove(id);
        if (server != null) {
            server.stop();
        }
    }
}
