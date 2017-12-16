package hu.blackbelt.jaxrs;

import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;
import org.osgi.util.tracker.ServiceTracker;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Component(immediate = true)
@Slf4j
public class ApplicationManager {

    private static final String JAXRS_PROVIDER_COMPONENTS = "jaxrs.provider.components";
    private static final String JAXRS_PROVIDER_OBJECTS = "jaxrs.provider.objects";
    private static final String APPLICATION_ID = "application.id";

    private static final String GENERATED_BY_KEY = "__generated.by";
    private static final String GENERATED_BY_VALUE = UUID.randomUUID().toString();
    private static final String GENERATED_HASHCODE = "__generated.hashCode";

    private static final String APPLICATIONS_FILTER = "applications.filter";

    // define target filter in configuration in case of multiple JAX-RS implementations
    @Reference(target = "(" + ServerManager.ALIAS_KEY + "=" + CxfServerManager.ALIAS_VALUE + ")")
    ServerManager serverManager;

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    ConfigurationAdmin configAdmin;

    private BundleContext context;

    private ApplicationTracker applicationTracker;
    private SharedProviderTracker sharedProviderTracker;
    private ProviderTracker providerTracker;

    private final Map<Long, Application> applications = new HashMap<>();

    private final Map<Long, Map<String, Configuration>> providerComponentConfigurations = new HashMap<>();
    private final Map<Long, Set<String>> missingComponents = new HashMap<>();
    private final Map<Long, Map<String, Object>> providerComponents = new HashMap<>();
    private final Map<Long, Map<String, Object>> providerObjects = new HashMap<>();
    private final Map<Long, Set<Long>> sharedApplicationProviders = new HashMap<>();

    private final Map<Long, Object> sharedProviders = new HashMap<>();
    private final Map<Long, String> sharedProviderFilters = new HashMap<>();
    private final Map<Long, Object> globalProviders = new LinkedHashMap<>();

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
        serverManager.shutdown();
        context = null;
    }

    private static Dictionary<String, Object> prepareConfiguration(final ServiceReference reference, final Object id) {
        final Dictionary<String, Object> dictionary = new Hashtable<>();

        int hashCode = 1;
        for (final String key : reference.getPropertyKeys()) {
            if (!key.startsWith("service.") && !key.startsWith("component.") && !key.startsWith("felix.") && !key.startsWith("objectClass")) {
                final Object value = reference.getProperty(key);
                dictionary.put(key, value);
                hashCode = 31 * hashCode + key.hashCode() * 43 + (value == null ? 0 : value.hashCode());
            }
        }
        dictionary.put(APPLICATION_ID, id);
        dictionary.put(GENERATED_BY_KEY, GENERATED_BY_VALUE);
        dictionary.put(GENERATED_HASHCODE, hashCode);

        return dictionary;
    }

    private class ApplicationTracker extends ServiceTracker<Application, Application> {
        ApplicationTracker(final BundleContext context) {
            super(context, Application.class, null);
        }

        @Override
        public Application addingService(final ServiceReference<Application> reference) {
            final Long applicationId = (Long) reference.getProperty(Constants.SERVICE_ID);
            providerComponentConfigurations.put(applicationId, new TreeMap<>());
            providerComponents.put(applicationId, new HashMap<>());
            providerObjects.put(applicationId, new HashMap<>());
            sharedApplicationProviders.put(applicationId, new HashSet<>());

            final Application application = super.addingService(reference);

            if (!application.getClass().isAnnotationPresent(ApplicationPath.class)) {
                log.warn("No @ApplicationPath found on component, service.id = " + applicationId);
                return application;
            }
            if (log.isDebugEnabled()) {
                log.debug("Register JAX-RS application: " + application + "; id = " + applicationId);
            }
            applications.put(applicationId, application);

            // rescan all shared providers
            sharedProviderFilters.forEach((providerId, filter) -> changedSharedProvider(providerId, filter));

            // create JAX-RS provider objects
            getCommaSeparatedList((String) reference.getProperty(JAXRS_PROVIDER_OBJECTS)).forEach(providerName -> createProviderObject(applicationId, providerName));

            // create JAX-RS provider components
            final Collection<String> componentProviders = getCommaSeparatedList((String) reference.getProperty(JAXRS_PROVIDER_COMPONENTS));
            missingComponents.put(applicationId, new TreeSet<>(componentProviders));
            componentProviders.forEach(providerName -> createProviderComponent(applicationId, providerName, prepareConfiguration(reference, applicationId)));

            // start application if JAX-RS provider list is empty
            if (componentProviders.isEmpty()) {
                serverManager.startApplication(applicationId, application, getSingleApplicationProviders(applicationId));
            }

            return application;
        }

        @Override
        public void modifiedService(final ServiceReference<Application> reference, final Application application) {
            super.modifiedService(reference, application);
            if (!application.getClass().isAnnotationPresent(ApplicationPath.class)) {
                return;
            }

            final Long applicationId = (Long) reference.getProperty(Constants.SERVICE_ID);

            final Collection<String> updatedProviderObjects = getCommaSeparatedList((String) reference.getProperty(JAXRS_PROVIDER_OBJECTS));
            final Collection<String> updatedProviderComponents = getCommaSeparatedList((String) reference.getProperty(JAXRS_PROVIDER_COMPONENTS));

            final Collection<String> existingProviderObjects = providerObjects.get(applicationId).keySet();
            final Collection<String> existingProviderComponents = providerComponentConfigurations.get(applicationId).keySet();

            final Collection<String> newProviderObjects = updatedProviderObjects.stream().filter(o -> !existingProviderObjects.contains(o)).collect(Collectors.toList());
            final Collection<String> providerObjectsToDelete = existingProviderObjects.stream().filter(o -> !updatedProviderObjects.contains(o)).collect(Collectors.toList());
            final Collection<String> newProviderComponents = updatedProviderComponents.stream().filter(o -> !existingProviderComponents.contains(o)).collect(Collectors.toList());
            final Collection<String> providerComponentsToDelete = existingProviderComponents.stream().filter(o -> !updatedProviderComponents.contains(o)).collect(Collectors.toList());

            newProviderObjects.forEach(providerName -> createProviderObject(applicationId, providerName));
            providerObjectsToDelete.forEach(providerName -> deleteProviderObject(applicationId, providerName));

            if (!providerComponentsToDelete.isEmpty() || !newProviderComponents.isEmpty()) {
                serverManager.stopApplication(applicationId);
                missingComponents.get(applicationId).addAll(newProviderComponents);
                missingComponents.get(applicationId).removeAll(providerComponentsToDelete);
                providerComponentsToDelete.forEach(providerName -> deleteProviderComponent(applicationId, providerName));
                newProviderComponents.forEach(providerName -> createProviderComponent(applicationId, providerName, prepareConfiguration(reference, applicationId)));
                if (newProviderComponents.isEmpty()) {
                    // start application only if no new JAX-RS provider is added, it will be started by JAX-RS provider tracker otherwise
                    serverManager.startApplication(applicationId, application, getSingleApplicationProviders(applicationId));
                }
            } else if (!newProviderObjects.isEmpty() || !providerObjectsToDelete.isEmpty()) {
                serverManager.restartApplications(Collections.singleton(applicationId), getApplicationProviders(Collections.singleton(applicationId)));
            }

            final Map<String, Configuration> providers = providerComponentConfigurations.get(applicationId);
            if (providers != null) {
                providers.forEach((providerName, cfg) -> {
                    try {
                        final Dictionary properties = prepareConfiguration(reference, reference.getProperty(Constants.SERVICE_ID));
                        if (!Objects.equals(cfg.getProperties().get(GENERATED_HASHCODE), properties.get(GENERATED_HASHCODE))) {
                            cfg.update(properties);
                        }
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
            serverManager.stopApplication(applicationId);

            final Map<String, Object> components = providerComponents.get(applicationId);
            final Collection<String> providerNames = components != null ? components.keySet() : Collections.emptyList();
            providerNames.forEach(providerName -> deleteProviderComponent(applicationId, providerName));

            applications.remove(applicationId);
            providerComponents.remove(applicationId);
            providerObjects.remove(applicationId);
            providerComponentConfigurations.remove(applicationId);
            sharedApplicationProviders.remove(applicationId);
            missingComponents.remove(applicationId);
        }
    }

    private void createProviderObject(final Long applicationId, final String providerName) {
        if (log.isDebugEnabled()) {
            log.debug("Creating JAX-RS provider object: " + providerName);
        }

        try {
            final Class clazz = Class.forName(providerName);
            final Object provider = clazz.newInstance();
            providerObjects.get(applicationId).put(providerName, provider);
        } catch (ClassNotFoundException ex) {
            log.error("Missing JAX-RS provider class: " + providerName, ex);
        } catch (InstantiationException | IllegalAccessException ex) {
            log.error("Unable to create provider object", ex);
        }
    }

    private void createProviderComponent(final Long applicationId, final String providerName, final Dictionary<String, Object> properties) {
        if (log.isDebugEnabled()) {
            log.debug("Creating JAX-RS provider component: " + providerName);
        }

        try {
            final Configuration cfg = configAdmin.createFactoryConfiguration(providerName, "?");
            cfg.update(properties);
            providerComponentConfigurations.get(applicationId).put(providerName, cfg);
        } catch (IOException ex) {
            log.error("Unable to create provider component", ex);
        }
    }

    private void deleteProviderObject(final Long applicationId, final String providerName) {
        providerObjects.get(applicationId).remove(providerName);
    }

    private void deleteProviderComponent(final Long applicationId, final String providerName) {
        providerComponents.get(applicationId).remove(providerName);
        final Configuration cfg = providerComponentConfigurations.get(applicationId).remove(providerName);
        if (cfg != null) {
            try {
                cfg.delete();
            } catch (IOException ex) {
                log.warn("Unable to delete JAX-RS provider configuration", ex);
            }
        }
    }

    private static Collection<String> getCommaSeparatedList(final String value) {
        return value != null && !value.trim().isEmpty() ? Arrays.asList(value.split("\\s*,\\s*")) : Collections.emptyList();
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
                final String providerName = provider.getClass().getName(); // FIXME - get OSGi name instead of Java object class
                addedLocalProvider(applicationId, providerName, provider);
            }
            return provider;
        }

        @Override
        public void removedService(final ServiceReference<Object> reference, final Object provider) {
            if (provider.getClass().isAnnotationPresent(Provider.class)) {
                final Long applicationId = (Long) reference.getProperty(APPLICATION_ID);
                final String providerName = provider.getClass().getName(); // FIXME - get OSGi name instead of Java object class
                removedLocalProvider(applicationId, providerName);
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
                    sharedProviders.put(providerId, provider);
                    sharedProviderFilters.put(providerId, filter);
                    final Collection<Long> changedApplicationIds = addedSharedProvider(providerId, provider, filter);
                    serverManager.restartApplications(changedApplicationIds, getApplicationProviders(changedApplicationIds));
                } else {
                    addedGlobalProvider(providerId, provider);
                    serverManager.restartAllApplications(getApplicationProviders(null));
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
                    sharedProviderFilters.remove(providerId);
                    removedSharedProvider(providerId);
                    addedGlobalProvider(providerId, provider);
                    serverManager.restartAllApplications(getApplicationProviders(null));
                } else if (filter != null && globalProviders.containsKey(providerId)) {
                    // change provider to shared
                    sharedProviderFilters.put(providerId, filter);
                    removeGlobalProvider(providerId);
                    addedSharedProvider(providerId, provider, filter);
                    serverManager.restartAllApplications(getApplicationProviders(null));
                } else if (filter != null) {
                    // check shared provider filter
                    sharedProviders.put(providerId, provider);
                    sharedProviderFilters.put(providerId, filter);
                    final Collection<Long> changedApplicationIds = changedSharedProvider(providerId, filter);
                    serverManager.restartApplications(changedApplicationIds, getApplicationProviders(changedApplicationIds));
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
                    sharedProviders.remove(providerId);
                    sharedProviderFilters.remove(providerId);
                    final Collection<Long> changedApplicationIds = removedSharedProvider(providerId);
                    serverManager.restartApplications(changedApplicationIds, getApplicationProviders(changedApplicationIds));
                } else {
                    removeGlobalProvider(providerId);
                    serverManager.restartAllApplications(getApplicationProviders(null));
                }
            }
        }
    }

    private synchronized void addedLocalProvider(final Long applicationId, final String providerName, final Object provider) {
        providerComponents.get(applicationId).put(providerName, provider);
        final Set<String> components = missingComponents.get(applicationId);
        if (components != null) {
            components.remove(providerName);
            if (components.isEmpty()) {
                serverManager.startApplication(applicationId, applications.get(applicationId), getSingleApplicationProviders(applicationId));
            } else {
                log.debug("Waiting for JAX-RS provider components: " + components);
            }
        }
    }

    private synchronized void removedLocalProvider(final Long applicationId, final String providerName) {
        // server is not stopped is a JAX-RS provider is removed because it is managed by ApplicationTracker
        final Map<String, Object> providers = providerComponents.get(applicationId);
        if (providers != null) {
            providers.remove(providerName);
        }
        final Map<String, Configuration> configurations = providerComponentConfigurations.get(applicationId);
        if (configurations != null) {
            configurations.remove(providerName);
        }
    }

    private synchronized Collection<Long> addedSharedProvider(final Long providerId, final Object provider, final String filter) {
        final List<Long> changed = new LinkedList<>();
        try {
            final Collection<ServiceReference<Application>> srs = context.getServiceReferences(Application.class, filter);
            if (srs != null) {
                for (final ServiceReference<Application> sr : srs) {
                    final Long applicationId = (Long) sr.getProperty(Constants.SERVICE_ID);
                    sharedApplicationProviders.get(applicationId).add(providerId);
                    changed.add(applicationId);
                }
            }
        } catch (InvalidSyntaxException ex) {
            log.error("Invalid filter in shared JAX-RS provider, service.id = " + providerId, ex);
        }
        return changed;
    }

    private synchronized Collection<Long> changedSharedProvider(final Long providerId, final String filter) {
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
        for (final Map.Entry<Long, Set<Long>> apps : sharedApplicationProviders.entrySet()) {
            final Long applicationId = apps.getKey();
            final Set<Long> providerIds = apps.getValue();
            if (providerIds.contains(providerId) && !filterResult.contains(applicationId)) {
                // provider should be removed
                providerIds.remove(providerId);
                changed.add(applicationId);
            } else if (!providerIds.contains(providerId) && filterResult.contains(applicationId)) {
                // provider should be added
                providerIds.add(providerId);
                changed.add(applicationId);
            }
        }
        return changed;
    }

    private synchronized Collection<Long> removedSharedProvider(final Long providerId) {
        final List<Long> changed = new LinkedList<>();
        for (final Map.Entry<Long, Set<Long>> apps : sharedApplicationProviders.entrySet()) {
            final Long applicationId = apps.getKey();
            final Set<Long> providerIds = apps.getValue();
            if (providerIds.contains(providerId)) {
                providerIds.remove(providerId);
                changed.add(applicationId);
            }
        }
        return changed;
    }

    private synchronized void addedGlobalProvider(final Long providerId, final Object provider) {
        globalProviders.put(providerId, provider);
    }

    private synchronized void removeGlobalProvider(final Long providerId) {
        globalProviders.remove(providerId);
    }

    private List<Object> getSingleApplicationProviders(final Long applicationId) {
        final List<Object> providersToAdd = new LinkedList<>();
        providersToAdd.addAll(globalProviders.values());
        sharedApplicationProviders.get(applicationId).forEach(providerId -> {
            providersToAdd.add(sharedProviders.get(providerId));
        });
        providersToAdd.addAll(providerComponents.get(applicationId).values());
        providersToAdd.addAll(providerObjects.get(applicationId).values());

        return Collections.unmodifiableList(providersToAdd);
    }

    private Map<Long, List<Object>> getApplicationProviders(Collection<Long> applicationIds) {
        final Map<Long, List<Object>> providers = new HashMap<>();

        if (applicationIds == null) {
            // collect providers for all applications
            applicationIds = applications.keySet();
        }

        applicationIds.forEach(applicationId -> providers.put(applicationId, getSingleApplicationProviders(applicationId)));

        return providers;
    }
}
