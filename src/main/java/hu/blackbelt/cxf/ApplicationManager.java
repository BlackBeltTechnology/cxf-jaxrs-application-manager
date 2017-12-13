package hu.blackbelt.cxf;

import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.osgi.service.component.annotations.*;

import javax.ws.rs.core.Application;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.HashMap;
import java.util.Map;

@Component(immediate = true, reference = {
        @Reference(name = "applications", service = Application.class, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MULTIPLE, bind = "registerApplication", unbind = "unregisterApplication", updated = "updateApplication")
})
@Slf4j
public class ApplicationManager {

    private static final String PROVIDERS_KEY = "providers";

    private Map<Application, Server> runningServers = new HashMap<>();

    void registerApplication(final Application application, final Map<String, Object> config) {
        if (log.isDebugEnabled()) {
            log.debug("Register JAX-RS application: " + application);
        }

        final RuntimeDelegate delegate = RuntimeDelegate.getInstance();
        final JAXRSServerFactoryBean serverFactory = delegate.createEndpoint(application, JAXRSServerFactoryBean.class);

        final String providerList = (String) config.get(PROVIDERS_KEY);
        if (providerList != null) {
            for (final String providerName : providerList.split("\\s*,\\s*")) {
                log.warn("TODO: create provider component: " + providerName);
            }
        }

        final Server server = serverFactory.create();
        server.start();

        runningServers.put(application, server);
    }

    void unregisterApplication(final Application application, final Map<String, Object> config) {
        if (log.isDebugEnabled()) {
            log.debug("Unregister JAX-RS application: " + application);
        }

        final Server server = runningServers.get(application);
        if (server != null) {
            server.stop();
        }

        runningServers.remove(application);
    }

    void updateApplication(final Application application, final Map<String, Object> config) {
        if (log.isDebugEnabled()) {
            log.debug("Updated JAX-RS application registration: " + application);
        }
    }
}
