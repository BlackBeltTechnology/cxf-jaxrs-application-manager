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
import java.util.concurrent.TimeUnit;

@Component(immediate = true, reference = {
        @Reference(name = "applications", service = Application.class, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MULTIPLE, bind = "registerApplication", unbind = "unregisterApplication", updated = "updateApplication")
})
@Slf4j
public class ApplicationManager {

    private static final String PROVIDERS_KEY = "providers";
    private static final String APPLICATION_PID = "application.pid";

    private static final long CONFIGADMIN_TIMEOUT = 60L;

    private Map<Application, Server> runningServers = new HashMap<>();
    private Set<Configuration> configurations = new LinkedHashSet<>();

    void registerApplication(final Application application, final Map<String, Object> config) {
        if (log.isDebugEnabled()) {
            log.debug("Register JAX-RS application: " + application);
        }

        final RuntimeDelegate delegate = RuntimeDelegate.getInstance();
        final JAXRSServerFactoryBean serverFactory = delegate.createEndpoint(application, JAXRSServerFactoryBean.class);

        final String providerList = (String) config.get(PROVIDERS_KEY);

        if (providerList != null) {
            final List<Object> providers = new LinkedList<>();
            for (final String providerName : providerList.split("\\s*,\\s*")) {
                if (log.isDebugEnabled()) {
                    log.debug("Creating JAX-RS provider instance: " + providerName);
                }

                final Object provider = registerProvider(providerName, config);
                if (provider != null) {
                    providers.add(provider);
                }
            }
            serverFactory.setProviders(Collections.unmodifiableList(providers));
        }

        final Server server = serverFactory.create();
        server.start();

        runningServers.put(application, server);
    }

    void updateApplication(final Application application, final Map<String, Object> config) {
        if (log.isDebugEnabled()) {
            log.debug("Updated JAX-RS application registration: " + application);
        }
        configurations.forEach(cfg -> {
            try {
                cfg.update(prepareConfiguration(config));
            } catch (IOException ex) {
                log.warn("Unable to update provider configuration", ex);
            }
        });
    }

    void unregisterApplication(final Application application) {
        if (log.isDebugEnabled()) {
            log.debug("Unregister JAX-RS application: " + application);
        }

        final Server server = runningServers.get(application);
        if (server != null) {
            server.stop();
        }

        configurations.forEach(cfg -> {
            try {
                cfg.delete();
            } catch (IOException ex) {
                log.warn("Unable to delete provider", ex);
            }
        });

        runningServers.remove(application);
        configurations.clear();
    }

    private static Dictionary<String, Object> prepareConfiguration(final Map<String, Object> config) {
        final Dictionary<String, Object> dictionary = config.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("service.") && !e.getKey().startsWith("component.") && !e.getKey().startsWith("felix.") && !e.getKey().startsWith("objectClass"))
                .collect(Hashtable<String, Object>::new, (dict, entry) -> dict.put(entry.getKey(), entry.getValue()), Hashtable::putAll);
        dictionary.put(APPLICATION_PID, config.get(Constants.SERVICE_PID));
        return dictionary;
    }

    private static Object registerProvider(final String componentName, final Map<String, Object> config) {
        final BundleContext context = FrameworkUtil.getBundle(ApplicationManager.class).getBundleContext();
        final ConfigurationAdmin configAdmin = waitForService(context, ConfigurationAdmin.class, CONFIGADMIN_TIMEOUT, TimeUnit.SECONDS);

        try {
            final Configuration cfg = configAdmin.getConfiguration(componentName, "?");
            cfg.update(prepareConfiguration(config));
        } catch (IOException ex) {
            log.error("Unable to create provider", ex);
        }

        final String filter = "(" + APPLICATION_PID + "=" + config.get(Constants.SERVICE_PID) + ")";
        log.warn("FILTER: " + filter);
        try {
            final ServiceReference[] srs = context.getServiceReferences(componentName, filter);
            if (srs == null || srs.length == 0) {
                log.warn("No registered provider found: " + componentName);
                return null;
            } else {
                if (srs.length > 1) {
                    log.warn("Multiple registered providers found: " + componentName);
                }
                return context.getService(srs[0]);
            }
        } catch (InvalidSyntaxException ex) {
            log.error("Getting provider failed", ex);
            return null;
        }
    }

    public static <T> ServiceReference<T> waitForServiceReference(final BundleContext context, final Class<T> clazz, final long timeout, final TimeUnit unit) {
        final ServiceTracker<T, T> tracker = new ServiceTracker<T, T>(context, clazz.getName(), null);
        tracker.open();

        ServiceReference<T> sref = null;
        try {
            if (tracker.waitForService(unit.toMillis(timeout)) != null) {
                sref = context.getServiceReference(clazz);
            }
        } catch (InterruptedException e) {
            // service will be null
        } finally {
            tracker.close();
        }

        return sref;
    }

    private static <T> T waitForService(final BundleContext context, final Class<T> clazz, final long timeout, final TimeUnit unit) {
        final ServiceReference<T> sr = waitForServiceReference(context, clazz, timeout, unit);
        return context.getService(sr);
    }
}
