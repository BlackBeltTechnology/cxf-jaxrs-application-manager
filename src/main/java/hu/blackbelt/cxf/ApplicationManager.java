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

    private Map<Application, Server> runningServers = new HashMap<>();
    private Map<Application, CxfProviderProvider> providerProviders = new HashMap<>();

    void registerApplication(final Application application, final Map<String, Object> config) {
        if (log.isDebugEnabled()) {
            log.debug("Register JAX-RS application: " + application);
        }

        final RuntimeDelegate delegate = RuntimeDelegate.getInstance();
        final JAXRSServerFactoryBean serverFactory = delegate.createEndpoint(application, JAXRSServerFactoryBean.class);

        final CxfProviderProvider cxfProviderProvider = new CxfProviderProvider();
        cxfProviderProvider.configure(config);

        serverFactory.setProviders(cxfProviderProvider.getProviders());
        final Server server = serverFactory.create();
        server.start();

        runningServers.put(application, server);
        providerProviders.put(application, cxfProviderProvider);
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
        providerProviders.remove(application);
    }

    void updateApplication(final Application application, final Map<String, Object> config) {
        if (log.isDebugEnabled()) {
            log.debug("Update JAX-RS application registration: " + application);
        }

        final CxfProviderProvider cxfProviderProvider = providerProviders.get(application);
        if (cxfProviderProvider != null) {
            cxfProviderProvider.configure(config);
        }
    }
}
