package hu.blackbelt.jaxrs.application;

import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.*;
import org.osgi.util.tracker.ServiceTracker;

import javax.ws.rs.core.Application;
import java.util.*;

@Component(immediate = true, service = Application.class, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Slf4j
public class BasicApplication extends Application {

    private static final String CLASSES_KEY = "jaxrs.application.classes";
    private static final String COMPONENTS_KEY = "jaxrs.application.components";

    private static final String CHANGED_RESOURCES_KEY = "__lastChangedResources";

    private final Set<Class<?>> classes = new LinkedHashSet<>();
    private final Set<Object> components = new LinkedHashSet<>();

    private Map<String, Object> properties = Collections.emptyMap();

    private ResourceTracker tracker;

    private String classesDef;
    private String componentFilter;

    @Activate
    void start(final BundleContext context, final Map<String, Object> config) {
        final String classesDef = (String) config.get(CLASSES_KEY);
        this.classesDef = classesDef;
        properties = new TreeMap<>(config);

        final String filter = (String) config.get(COMPONENTS_KEY);

        if (classesDef != null) {
            setClasses(classesDef);
        }

        if (filter != null) {
            startResourceTracker(context, filter);
        }
    }

    @Modified
    void update(final BundleContext context, final Map<String, Object> config) {
        final String classesDef = (String) config.get(CLASSES_KEY);
        final String filter = (String) config.get(COMPONENTS_KEY);
        properties = new TreeMap<>(config);

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

        if (changedResources) {
            properties.put(CHANGED_RESOURCES_KEY, System.currentTimeMillis());
        }
    }

    @Deactivate
    void stop() {
        if (tracker != null) {
            tracker.close();
            tracker = null;
        }

        classes.clear();
        components.clear();
        classesDef = null;
        componentFilter = null;
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
            components.add(resource);

            // TODO - update application resources

            return resource;
        }

        @Override
        public void removedService(final ServiceReference<Object> reference, final Object resource) {
            super.removedService(reference, resource);
            components.remove(resource);

            // TODO - update application resources
        }
    }
}
