package hu.blackbelt.jaxrs;

import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;

import javax.ws.rs.core.Application;
import javax.ws.rs.ext.Provider;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
class SharedProviderStore {

    private static final String APPLICATIONS_FILTER = "applications.filter";

    private SharedProviderTracker sharedProviderTracker;

    private final Map<Long, Set<Long>> sharedApplicationProviders = new ConcurrentHashMap<>();
    private final Map<Long, Object> sharedProviders = new ConcurrentHashMap<>();
    private final Map<Long, String> sharedProviderFilters = new ConcurrentHashMap<>();
    private final Map<Long, Object> globalProviders = new ConcurrentHashMap<>();

    private final BundleContext context;
    private final Callback callback;

    SharedProviderStore(final BundleContext context, final Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    void start() {
        try {
            sharedProviderTracker = new SharedProviderTracker(context);
            sharedProviderTracker.open();
        } catch (InvalidSyntaxException ex) {
            throw new IllegalStateException("Unable to start shared JAX-RS provider tracker", ex);
        }
    }

    void stop() {
        if (sharedProviderTracker != null) {
            sharedProviderTracker.close();
            sharedProviderTracker = null;
        }
    }

    public void addApplication(final Long applicationId) {
        sharedApplicationProviders.put(applicationId, new HashSet<>());

        // rescan all shared providers
        sharedProviderFilters.forEach(this::changedSharedProvider);
    }

    public void removeApplication(Long applicationId) {
        sharedApplicationProviders.remove(applicationId);
    }

    public List<Object> getProviders(final Long applicationId) {
        final List<Object> providers = new LinkedList<>();
        providers.addAll(globalProviders.values());
        sharedApplicationProviders.get(applicationId).forEach(providerId -> providers.add(sharedProviders.get(providerId)));
        return providers;
    }

    private class SharedProviderTracker extends ServiceTracker<Object, Object> {
        SharedProviderTracker(final BundleContext context) throws InvalidSyntaxException {
            super(context, context.createFilter("(" + Constants.OBJECTCLASS + "=*)"), null);
        }

        @Override
        public Object addingService(ServiceReference<Object> reference) {
            Object provider = null;
            try {
                provider = super.addingService(reference);
            } catch (ServiceException ex) {
                if (log.isTraceEnabled()) {
                    log.trace("Unable to add shared provider", ex);
                }
            }
            if (provider != null && !Objects.equals(reference.getProperty(ApplicationManager.GENERATED_BY_KEY), ApplicationManager.GENERATED_BY_VALUE) && provider.getClass().isAnnotationPresent(Provider.class)) {
                final Long providerId = (Long) reference.getProperty(Constants.SERVICE_ID);
                final String filter = (String) reference.getProperty(APPLICATIONS_FILTER);
                if (filter != null) {
                    sharedProviders.put(providerId, provider);
                    sharedProviderFilters.put(providerId, filter);
                    final Collection<Long> changedApplicationIds = addedSharedProvider(providerId, filter);
                    callback.restartApplications(changedApplicationIds);
                } else {
                    addedGlobalProvider(providerId, provider);
                    callback.restartApplications(null);
                }
            }
            return provider;
        }

        @Override
        public void modifiedService(ServiceReference<Object> reference, Object provider) {
            super.modifiedService(reference, provider);
            if (provider != null && !Objects.equals(reference.getProperty(ApplicationManager.GENERATED_BY_KEY), ApplicationManager.GENERATED_BY_VALUE) && provider.getClass().isAnnotationPresent(Provider.class)) {
                final Long providerId = (Long) reference.getProperty(Constants.SERVICE_ID);
                final String filter = (String) reference.getProperty(APPLICATIONS_FILTER);
                if (filter == null && !globalProviders.containsKey(providerId)) {
                    // change provider to global
                    sharedProviderFilters.remove(providerId);
                    removedSharedProvider(providerId);
                    addedGlobalProvider(providerId, provider);
                    callback.restartApplications(null);
                } else if (filter != null && globalProviders.containsKey(providerId)) {
                    // change provider to shared
                    sharedProviderFilters.put(providerId, filter);
                    removeGlobalProvider(providerId);
                    addedSharedProvider(providerId, filter);
                    callback.restartApplications(null);
                } else if (filter != null) {
                    // check shared provider filter
                    sharedProviders.put(providerId, provider);
                    sharedProviderFilters.put(providerId, filter);
                    final Collection<Long> changedApplicationIds = changedSharedProvider(providerId, filter);
                    callback.restartApplications(changedApplicationIds);
                }
            }
        }

        @Override
        public void removedService(ServiceReference<Object> reference, Object provider) {
            super.removedService(reference, provider);
            if (provider != null && !Objects.equals(reference.getProperty(ApplicationManager.GENERATED_BY_KEY), ApplicationManager.GENERATED_BY_VALUE) && provider.getClass().isAnnotationPresent(Provider.class)) {
                final Long providerId = (Long) reference.getProperty(Constants.SERVICE_ID);

                final String filter = (String) reference.getProperty(APPLICATIONS_FILTER);
                if (filter != null) {
                    sharedProviders.remove(providerId);
                    sharedProviderFilters.remove(providerId);
                    final Collection<Long> changedApplicationIds = removedSharedProvider(providerId);
                    callback.restartApplications(changedApplicationIds);
                } else {
                    removeGlobalProvider(providerId);
                    callback.restartApplications(null);
                }
            }
        }
    }

    private Collection<Long> addedSharedProvider(final Long providerId, final String filter) {
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

    private Collection<Long> changedSharedProvider(final Long providerId, final String filter) {
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

    private Collection<Long> removedSharedProvider(final Long providerId) {
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

    private void addedGlobalProvider(final Long providerId, final Object provider) {
        globalProviders.put(providerId, provider);
    }

    private void removeGlobalProvider(final Long providerId) {
        globalProviders.remove(providerId);
    }

    interface Callback {
        void restartApplications(Collection<Long> applicationIds);
    }
}
