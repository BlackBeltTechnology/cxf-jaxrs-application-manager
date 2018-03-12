package hu.blackbelt.jaxrs.application;

import hu.blackbelt.jaxrs.CxfContext;
import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;
import org.osgi.util.tracker.ServiceTracker;

import javax.ws.rs.core.Application;
import java.io.IOException;
import java.util.*;

@Component(immediate = true, service = Application.class, configurationPolicy = ConfigurationPolicy.REQUIRE, reference = {
        @Reference(name = "cxf.context", policyOption = ReferencePolicyOption.GREEDY, service = CxfContext.class, bind = "setContext", unbind = "unsetContext", updated = "updateContext")
})
@Slf4j
public class BasicApplication extends Application {

    private static final String CLASSES_KEY = "jaxrs.resource.classes";
    private static final String COMPONENTS_KEY = "jaxrs.resource.components";

    public static final String CONTEXT_PROPERTY_KEY = "cxf.context";

    private static final String CHANGED_RESOURCES_KEY = "__lastChangedResources";

    private final Set<Class<?>> classes = new LinkedHashSet<>();
    private final Set<Object> components = new LinkedHashSet<>();

    private final Map<String, Object> properties = new TreeMap<>();

    private ResourceTracker tracker;

    private String classesDef;
    private String componentFilter;

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    private ConfigurationAdmin configAdmin;

    private String pid;

    private CxfContext cxfContext;
    private Object lastChangedContext;

    private Object lastChangedResources;

    @Activate
    void start(final BundleContext context, final Map<String, Object> config) {
        pid = (String) config.get(Constants.SERVICE_PID);
        log.info("Starting JAX-RS application: " + pid);

        final String classesDef = (String) config.get(CLASSES_KEY);
        this.classesDef = classesDef;
        properties.putAll(config);

        final String filter = (String) config.get(COMPONENTS_KEY);

        if (classesDef != null) {
            setClasses(classesDef);
        }

        if (filter != null) {
            startResourceTracker(context, filter);
        }

        properties.put(CONTEXT_PROPERTY_KEY, this.cxfContext);
    }

    @Modified
    void update(final BundleContext context, final Map<String, Object> config) {
        log.info("Updating JAX-RS application: " + pid);

        final String classesDef = (String) config.get(CLASSES_KEY);
        final String filter = (String) config.get(COMPONENTS_KEY);

        boolean changedResources = false;

        if (!Objects.equals(this.classesDef, classesDef)) {
            this.classesDef = classesDef;
            setClasses(classesDef);
            changedResources = true;
        }

        if (!Objects.equals(filter, componentFilter)) {
            componentFilter = filter;
            if (tracker != null) {
                tracker.close();
                tracker = null;
            }
            if (filter != null) {
                startResourceTracker(context, filter);
            }
            changedResources = true;
        }

        final Object lastChanged = config.get(CHANGED_RESOURCES_KEY);
        if (!Objects.equals(lastChanged, lastChangedResources)) {
            lastChangedResources = lastChanged;
            changedResources = true;
        }

        if (changedResources) {
            properties.put(CHANGED_RESOURCES_KEY, lastChanged);
        }
    }

    @Deactivate
    void stop() {
        log.info("Stopping JAX-RS application: " + pid);
        if (tracker != null) {
            tracker.close();
            tracker = null;
        }

        pid = null;
        classes.clear();
        components.clear();
        classesDef = null;
        componentFilter = null;
        properties.clear();
    }

    void setContext(final CxfContext cxfContext, final Map<String, Object> props) {
        this.cxfContext = cxfContext;
        lastChangedContext = props.get(CxfContext.LAST_CHANGED_CONFIGURATION);
    }

    void updateContext(final CxfContext cxfContext, final Map<String, Object> props) {
        this.cxfContext = cxfContext;

        final Object newLastChangedContext = props.get(CxfContext.LAST_CHANGED_CONFIGURATION);
        if (!Objects.equals(lastChangedContext, newLastChangedContext)) {
            lastChangedContext = newLastChangedContext;
            changedResources();
        }
    }

    void unsetContext() {
        this.cxfContext = null;
    }

    private void startResourceTracker(final BundleContext context, final String filter) {
        try {
            componentFilter = filter;
            tracker = new ResourceTracker(context, filter);
            tracker.open();
        } catch (InvalidSyntaxException ex) {
            log.error("Invalid resource filter", ex);
        }
    }

    private void setClasses(final String classesDef) {
        classes.clear();
        for (final String className : classesDef.split("\\s,\\s")) {
            try {
                classes.add(Class.forName(className));
            } catch (ClassNotFoundException ex) {
                log.error("Class not added to application: " + className, ex);
            }
        }
    }

    @Override
    public Set<Class<?>> getClasses() {
        return Collections.unmodifiableSet(classes);
    }

    @Override
    public Set<Object> getSingletons() {
        return Collections.unmodifiableSet(components);
    }

    @Override
    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    private class ResourceTracker extends ServiceTracker<Object, Object> {

        ResourceTracker(final BundleContext context, final String filter) throws InvalidSyntaxException {
            super(context, context.createFilter(filter), null);
        }

        @Override
        public Object addingService(final ServiceReference<Object> reference) {
            final Object resource = super.addingService(reference);
            if (resource != null) {
                components.add(resource);
                changedResources();

            }
            return resource;
        }

        @Override
        public void removedService(final ServiceReference<Object> reference, final Object resource) {
            super.removedService(reference, resource);
            if (resource != null) {
                components.remove(resource);
                changedResources();
            }
        }
    }

    private void changedResources() {
        try {
            log.debug("Changed OSGi component resources in JAX-RS application: {}", pid);
            final Configuration[] cfgs = configAdmin.listConfigurations("(" + Constants.SERVICE_PID + "=" + pid + ")");
            final Object lastChangedResources = System.currentTimeMillis();
            if (cfgs != null) {
                for (final Configuration cfg : cfgs) {
                    if (cfg != null) {
                        final Dictionary<String, Object> props = cfg.getProperties();
                        props.put(CHANGED_RESOURCES_KEY, lastChangedResources);
                        try {
                            cfg.update(props);
                        } catch (IllegalStateException ex) {
                            if (log.isTraceEnabled()) {
                                log.trace("Unable to update JAX-RS resource", ex);
                            }
                        }
                    } else {
                        log.warn("No configuration found for JAX-RS application with PID: " + pid);
                    }
                }
            }
        } catch (IOException | InvalidSyntaxException ex) {
            log.error("Unable to notify JAX-RS application", ex);
        }
    }
}
