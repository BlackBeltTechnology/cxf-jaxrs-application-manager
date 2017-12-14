package hu.blackbelt.cxf;

import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.osgi.framework.*;
import org.osgi.service.component.annotations.*;

import javax.ws.rs.core.Application;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.*;

@Component(immediate = true, reference = {
        @Reference(name = "applications", service = Application.class, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MULTIPLE, bind = "registerApplication", unbind = "unregisterApplication", updated = "updateApplication")
})
@Slf4j
public class ApplicationManager {

    private static final String PROVIDERS_KEY = "providers";

    private Map<Application, Server> runningServers = new HashMap<>();
    private Map<String, ServiceRegistration> serviceRegistrations = new TreeMap<>();

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
                try {
                    final Class clazz = Class.forName(providerName);
                    final Object provider = clazz.newInstance();
                    providers.add(provider);

                    serviceRegistrations.put(providerName, registerService(clazz, provider, config));
                } catch (ClassNotFoundException ex) {
                    log.error("Unknown provider class: " + providerName, ex);
                } catch (InstantiationException | IllegalAccessException ex) {
                    log.error("Unable to create provider service", ex);
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
        serviceRegistrations.values().forEach(p -> p.setProperties(prepareConfiguration(config)));
    }

    void unregisterApplication(final Application application) {
        if (log.isDebugEnabled()) {
            log.debug("Unregister JAX-RS application: " + application);
        }

        final Server server = runningServers.get(application);
        if (server != null) {
            server.stop();
        }

        serviceRegistrations.values().forEach(p -> p.unregister());

        runningServers.remove(application);
        serviceRegistrations.clear();
    }

    private static Dictionary<String, Object> prepareConfiguration(final Map<String, Object> config) {
        return config.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("service.") && !e.getKey().startsWith("component.") && !e.getKey().startsWith("felix.") && !e.getKey().startsWith("objectClass"))
                .collect(Hashtable<String, Object>::new, (dict, entry) -> dict.put(entry.getKey(), entry.getValue()), Hashtable::putAll);
    }

    private static ServiceRegistration registerService(final Class clazz, final Object service, final Map<String, Object> applicationConfig) {
        final BundleContext context = FrameworkUtil.getBundle(ApplicationManager.class).getBundleContext();
        return context.registerService(clazz, service, prepareConfiguration(applicationConfig));
    }
}
