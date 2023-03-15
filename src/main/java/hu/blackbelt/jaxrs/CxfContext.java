package hu.blackbelt.jaxrs;

/*-
 * #%L
 * CXF JAX-RS application manager
 * %%
 * Copyright (C) 2018 - 2023 BlackBelt Technology
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.ext.logging.LoggingFeature;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.message.Message;
import org.apache.cxf.metrics.MetricsFeature;
import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.util.tracker.ServiceTracker;

import java.io.IOException;
import java.util.*;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE, service = CxfContext.class)
@Designate(ocd = CxfContext.Config.class)
@Slf4j
public class CxfContext {

    @ObjectClassDefinition
    public @interface Config {

        @AttributeDefinition(name = "CXF bus ID")
        String busId();

        @AttributeDefinition(required = false, name = "Skip default JSON provider registration", description = "Do not use CXF JSON provider as default message body reader.", type = AttributeType.BOOLEAN)
        boolean skipDefaultJsonProviderRegistration() default SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_DEFAULT;

        @AttributeDefinition(required = false, name = "WADL service description available", type = AttributeType.BOOLEAN)
        boolean wadlServiceDescriptionAvailable() default WADL_SERVICE_DESCRIPTION_AVAILABLE_DEFAULT;

        @AttributeDefinition(required = false, name = "IN interceptors filter expression")
        String interceptors_in_components();

        @AttributeDefinition(required = false, name = "OUT interceptors filter expression")
        String interceptors_out_components();

        @AttributeDefinition(required = false, name = "FAULT interceptors filter expression")
        String interceptors_fault_components();

        @AttributeDefinition(required = false, name = "Enable metrics", type = AttributeType.BOOLEAN)
        boolean metrics_enabled() default METRICS_ENABLED_DEFAULT;

        @AttributeDefinition(required = false, name = "Enable logging", type = AttributeType.BOOLEAN)
        boolean logging_enabled() default LOGGING_ENABLED_DEFAULT;
    }

    private static final boolean SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_DEFAULT = true;
    private static final String SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_KEY = "skip.default.json.provider.registration";
    private Boolean skipDefaultJsonProviderRegistration;

    private static final boolean WADL_SERVICE_DESCRIPTION_AVAILABLE_DEFAULT = true;
    private static final String WADL_SERVICE_DESCRIPTION_AVAILABLE_KEY = "wadl.service.description.available";
    private static final boolean METRICS_ENABLED_DEFAULT = false;
    private static final boolean LOGGING_ENABLED_DEFAULT = false;
    private Boolean wadlServiceDescriptionAvailable;

    private Boolean metricsEnabled;
    private Boolean loggingEnabled;

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    private ConfigurationAdmin configAdmin;

    private String id;

    public static final String LAST_CHANGED_CONFIGURATION = "__lastChangedConfiguration";

    private String inInterceptorsFilter;
    private String outInterceptorsFilter;
    private String faultInterceptorsFilter;

    private List<Interceptor<? extends Message>> inInterceptors = new LinkedList<>();
    private List<Interceptor<? extends Message>> outInterceptors = new LinkedList<>();
    private List<Interceptor<? extends Message>> faultInterceptors = new LinkedList<>();

    private InterceptorTracker inInterceptorTracker;
    private InterceptorTracker outInterceptorTracker;
    private InterceptorTracker faultInterceptorTracker;

    private Bus bus;
    private ServiceRegistration<Bus> serviceRegistration;

    private String pid;

    @Activate
    void start(final BundleContext context, final Config config, final Map<String, Object> dynamicConfig) {
        pid = (String) dynamicConfig.get(Constants.SERVICE_PID);
        id = config.busId();
        Objects.requireNonNull(id, "Property 'busId' is not set");

        inInterceptorsFilter = config.interceptors_in_components();
        if (inInterceptorsFilter != null && !inInterceptorsFilter.trim().isEmpty()) {
            try {
                inInterceptorTracker = new InterceptorTracker(context, inInterceptorsFilter, inInterceptors);
                inInterceptorTracker.open();
            } catch (InvalidSyntaxException ex) {
                log.error("Invalid IN interceptor filter, ignore it", ex);
            }
        }
        outInterceptorsFilter = config.interceptors_out_components();
        if (outInterceptorsFilter != null && !outInterceptorsFilter.trim().isEmpty()) {
            try {
                outInterceptorTracker = new InterceptorTracker(context, outInterceptorsFilter, outInterceptors);
                outInterceptorTracker.open();
            } catch (InvalidSyntaxException ex) {
                log.error("Invalid OUT interceptor filter, ignore it", ex);
            }
        }
        faultInterceptorsFilter = config.interceptors_fault_components();
        if (faultInterceptorsFilter != null && !faultInterceptorsFilter.trim().isEmpty()) {
            try {
                faultInterceptorTracker = new InterceptorTracker(context, faultInterceptorsFilter, faultInterceptors);
                faultInterceptorTracker.open();
            } catch (InvalidSyntaxException ex) {
                log.error("Invalid FAULT interceptor filter, ignore it", ex);
            }
        }

        skipDefaultJsonProviderRegistration = config.skipDefaultJsonProviderRegistration();
        wadlServiceDescriptionAvailable = config.wadlServiceDescriptionAvailable();
        metricsEnabled = config.metrics_enabled();
        loggingEnabled = config.logging_enabled();

        bus = BusFactory.newInstance().createBus();
        bus.setId(id);
        final Dictionary<String, Object> props = new Hashtable<>();
        props.put("id", id);
        registerBus(context, bus);
    }

    @Modified
    void update(final BundleContext context, final Config config) {
        final boolean newSkipDefaultJsonProviderRegistration = config.skipDefaultJsonProviderRegistration();
        final boolean newWadlServiceDescriptionAvailable = config.wadlServiceDescriptionAvailable();
        final boolean newMetricsEnabled = config.metrics_enabled();
        final boolean newLoggingEnabled = config.logging_enabled();

        boolean updated = false;
        if (skipDefaultJsonProviderRegistration != null && !skipDefaultJsonProviderRegistration.equals(newSkipDefaultJsonProviderRegistration)) {
            skipDefaultJsonProviderRegistration = newSkipDefaultJsonProviderRegistration;
            bus.setProperty(SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_KEY, skipDefaultJsonProviderRegistration);
            updated = true;
        }
        if (wadlServiceDescriptionAvailable != null && !wadlServiceDescriptionAvailable.equals(newWadlServiceDescriptionAvailable)) {
            wadlServiceDescriptionAvailable = newWadlServiceDescriptionAvailable;
            bus.setProperty(WADL_SERVICE_DESCRIPTION_AVAILABLE_KEY, wadlServiceDescriptionAvailable);
            updated = true;
        }
        if (!metricsEnabled.equals(newMetricsEnabled)) {
            metricsEnabled = newMetricsEnabled;
            if (metricsEnabled) {
                bus.getFeatures().add(new MetricsFeature());
            } else {
                bus.getFeatures().removeIf(f -> f instanceof MetricsFeature);
            }
            updated = true;
        }
        if (!loggingEnabled.equals(newMetricsEnabled)) {
            loggingEnabled = newLoggingEnabled;
            if (loggingEnabled) {
                bus.getFeatures().add(new LoggingFeature());
            } else {
                bus.getFeatures().removeIf(f -> f instanceof LoggingFeature);
            }
            updated = true;
        }

        final String newInInterceptorsFilter = config.interceptors_in_components();
        if (!Objects.equals(inInterceptorsFilter, newInInterceptorsFilter)) {
            log.debug("IN interceptors have been changed");
            inInterceptorsFilter = newInInterceptorsFilter;
            updated = true;
            if (inInterceptorTracker != null) {
                inInterceptorTracker.close();
                inInterceptorTracker = null;
            }
            if (inInterceptorsFilter != null && !inInterceptorsFilter.trim().isEmpty()) {
                try {
                    inInterceptorTracker = new InterceptorTracker(context, inInterceptorsFilter, inInterceptors);
                    inInterceptorTracker.open();
                } catch (InvalidSyntaxException ex) {
                    log.error("Invalid IN interceptor filter, ignore it", ex);
                }
            }
        }
        final String newOutInterceptorsFilter = config.interceptors_out_components();
        if (!Objects.equals(outInterceptorsFilter, newOutInterceptorsFilter)) {
            log.debug("OUT interceptors have been changed");
            outInterceptorsFilter = newOutInterceptorsFilter;
            updated = true;
            if (outInterceptorTracker != null) {
                outInterceptorTracker.close();
                outInterceptorTracker = null;
            }
            if (outInterceptorsFilter != null && !outInterceptorsFilter.trim().isEmpty()) {
                try {
                    outInterceptorTracker = new InterceptorTracker(context, outInterceptorsFilter, outInterceptors);
                    outInterceptorTracker.open();
                } catch (InvalidSyntaxException ex) {
                    log.error("Invalid OUT interceptor filter, ignore it", ex);
                }
            }
        }
        final String newFaultInterceptorsFilter = config.interceptors_fault_components();
        if (!Objects.equals(faultInterceptorsFilter, newFaultInterceptorsFilter)) {
            log.debug("FAULT interceptors have been changed");
            faultInterceptorsFilter = newFaultInterceptorsFilter;
            updated = true;
            if (faultInterceptorTracker != null) {
                faultInterceptorTracker.close();
                faultInterceptorTracker = null;
            }
            if (faultInterceptorsFilter != null && !faultInterceptorsFilter.trim().isEmpty()) {
                try {
                    faultInterceptorTracker = new InterceptorTracker(context, faultInterceptorsFilter, faultInterceptors);
                    faultInterceptorTracker.open();
                } catch (InvalidSyntaxException ex) {
                    log.error("Invalid FAULT interceptor filter, ignore it", ex);
                }
            }
        }

        if (updated) {
            log.debug("CXF bus registered: {} [{}={}; {}={}]", id, SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_KEY, skipDefaultJsonProviderRegistration, WADL_SERVICE_DESCRIPTION_AVAILABLE_KEY, wadlServiceDescriptionAvailable);
        }
    }

    @Deactivate
    void stop() {
        if (inInterceptorTracker != null) {
            inInterceptorTracker.close();
            inInterceptorTracker = null;
        }
        if (outInterceptorTracker != null) {
            outInterceptorTracker.close();
            outInterceptorTracker = null;
        }
        if (faultInterceptorTracker != null) {
            faultInterceptorTracker.close();
            faultInterceptorTracker = null;
        }

        if (bus != null) {
            bus.shutdown(false);
        }
        if (serviceRegistration != null) {
            try {
                serviceRegistration.unregister();
            } catch (IllegalStateException ex) {
                log.debug("Unable to unregister CXF bus", ex);
            }
        }
        pid = null;
        bus = null;
        serviceRegistration = null;
    }

    private void registerBus(final BundleContext context, final Bus bus) {
        if (skipDefaultJsonProviderRegistration != null) {
            bus.setProperty(SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_KEY, skipDefaultJsonProviderRegistration);
        }
        if (wadlServiceDescriptionAvailable != null) {
            bus.setProperty(WADL_SERVICE_DESCRIPTION_AVAILABLE_KEY, wadlServiceDescriptionAvailable);
        }
        if (log.isDebugEnabled()) {
            log.debug("CXF bus registered: {} [{}={}; {}={}]", id, SKIP_DEFAULT_JSON_PROVIDER_REGISTRATION_KEY, skipDefaultJsonProviderRegistration, WADL_SERVICE_DESCRIPTION_AVAILABLE_KEY, wadlServiceDescriptionAvailable);
        }
        if (metricsEnabled) {
            bus.getFeatures().add(new MetricsFeature());
        }
        if (loggingEnabled) {
            bus.getFeatures().add(new LoggingFeature());
        }
        final Dictionary<String, Object> props = new Hashtable<>();
        props.put("id", id);
        serviceRegistration = context.registerService(Bus.class, bus, props);
    }

    private class InterceptorTracker extends ServiceTracker<Interceptor<? extends Message>, Interceptor<? extends Message>> {

        final List<Interceptor<? extends Message>> interceptors;

        InterceptorTracker(final BundleContext context, final String filter, final List<Interceptor<? extends Message>> interceptors) throws InvalidSyntaxException {
            super(context, context.createFilter("(&(" + Constants.OBJECTCLASS + "=" + Interceptor.class.getName() + ")" + filter + ")"), null);
            this.interceptors = interceptors;
        }

        @Override
        public Interceptor<? extends Message> addingService(final ServiceReference<Interceptor<? extends Message>> reference) {
            final Interceptor<? extends Message> interceptor = super.addingService(reference);
            if (interceptor != null) {
                interceptors.add(interceptor);
                changedConfiguration();
            }
            return interceptor;
        }

        @Override
        public void removedService(final ServiceReference<Interceptor<? extends Message>> reference, final Interceptor<? extends Message> interceptor) {
            super.removedService(reference, interceptor);
            if (interceptor != null) {
                interceptors.remove(interceptor);
                changedConfiguration();
            }
        }
    }

    public Bus getBus() {
        return bus;
    }

    public List<Interceptor<? extends Message>> getInInterceptors() {
        return Collections.unmodifiableList(inInterceptors);
    }

    public List<Interceptor<? extends Message>> getOutInterceptors() {
        return Collections.unmodifiableList(outInterceptors);
    }

    public List<Interceptor<? extends Message>> getFaultInterceptors() {
        return Collections.unmodifiableList(faultInterceptors);
    }

    private void changedConfiguration() {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Changed CXF context: " + pid);
            }
            final Configuration[] cfgs = configAdmin.listConfigurations("(" + Constants.SERVICE_PID + "=" + pid + ")");
            if (cfgs != null) {
                for (final Configuration cfg : cfgs) {
                    if (cfg != null) {
                        final Dictionary<String, Object> props = cfg.getProperties();
                        props.put(LAST_CHANGED_CONFIGURATION, System.currentTimeMillis());
                        try {
                            cfg.update(props);
                        } catch (IllegalStateException ex) {
                            if (log.isTraceEnabled()) {
                                log.trace("Unable to update CXF context", ex);
                            }
                        }
                    } else {
                        log.warn("No configuration found for CXF context with PID: " + pid);
                    }
                }
            }
        } catch (IOException | InvalidSyntaxException ex) {
            log.error("Unable to notify CXF context consumers", ex);
        }
    }
}
