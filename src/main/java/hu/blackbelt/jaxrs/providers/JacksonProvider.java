package hu.blackbelt.jaxrs.providers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import hu.blackbelt.jaxrs.SharedProviderStore;
import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.*;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

@Provider
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
@Slf4j
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE, service = JacksonProvider.class)
public class JacksonProvider {

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    ObjectMapper objectMapper;

    private JacksonJaxbJsonProvider jacksonJaxbJsonProvider;
    private ServiceRegistration<JacksonJaxbJsonProvider> jaxbJsonProviderServiceRegistration;

    @Activate
    @Modified
    void configure(final BundleContext context, final Map<String, Object> config) {
        jacksonJaxbJsonProvider = new JacksonJaxbJsonProvider(objectMapper, JacksonJaxbJsonProvider.DEFAULT_ANNOTATIONS);
        jacksonJaxbJsonProvider.configure(SerializationFeature.INDENT_OUTPUT, false);
        jacksonJaxbJsonProvider.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        jacksonJaxbJsonProvider.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        final String className = getClass().getSimpleName();
        config.forEach((k, v) -> {
            if (k.startsWith(className + ".SerializationFeature.")) {
                try {
                    final SerializationFeature feature = SerializationFeature.valueOf(k.replace(className + ".SerializationFeature.", ""));
                    log.info("Update SerializationFeature option '" + feature + "': " + v);
                    jacksonJaxbJsonProvider.configure(feature, Boolean.parseBoolean((String) v));
                } catch (IllegalArgumentException ex) {
                    log.warn("Invalid SerializationFeature option: " + k);
                }
            } else if (k.startsWith(className + ".DeserializationFeature.")) {
                try {
                    final DeserializationFeature feature = DeserializationFeature.valueOf(k.replace(className + ".DeserializationFeature.", ""));
                    log.info("Update DeserializationFeature option '" + feature + "': " + v);
                    jacksonJaxbJsonProvider.configure(feature, Boolean.parseBoolean((String) v));
                } catch (IllegalArgumentException ex) {
                    log.warn("Invalid DeserializationFeature option: " + k);
                }
            } else if ((className + ".ObjectMapper.modules").equals(k) && v != null) {
                if (objectMapper != null) {
                    log.warn("Custom object mapper is used by Jackson JAXB provider, modules are not added: {}", v);
                } else {
                    final String moduleList = (String) v;
                    for (final String moduleName : moduleList.split("\\s*,\\s*")) {
                        try {
                            log.info("Registering ObjectMapper module: " + moduleName);
                            final Module m = (Module) Class.forName(moduleName).newInstance();
                            jacksonJaxbJsonProvider.locateMapper(ObjectMapper.class, MediaType.APPLICATION_JSON_TYPE).registerModule(m);
                        } catch (ClassNotFoundException ex) {
                            log.error("Unknown ObjectMapper module: " + moduleName, ex);
                        } catch (InstantiationException | IllegalAccessException ex) {
                            log.error("Unable to register ObjectMapper module: " + moduleName, ex);
                        }
                    }
                }
            }
        });

        // This is hack, because the interface does not work in first time, so we emulate it
        // http://stackoverflow.com/questions/10860142/appengine-java-jersey-jackson-jaxbannotationintrospector-noclassdeffounderror
        // But that solution is not correct fpr this problem, because xc cause other problem (reason: JAXB annotations)
        try {
            jacksonJaxbJsonProvider.writeTo(1L, Long.class, Long.class, new Annotation[]{}, MediaType.APPLICATION_JSON_TYPE, null, new ByteArrayOutputStream());
        } catch (IOException ex) {
            log.warn("Error on initialization of Jackson JSON provider", ex);
        }

        final Dictionary<String, Object> props = new Hashtable<>();
        final Object sharedProviderFilter = config.get(SharedProviderStore.APPLICATIONS_FILTER);
        if (sharedProviderFilter != null) {
            props.put(SharedProviderStore.APPLICATIONS_FILTER, sharedProviderFilter);
        }
        if (jaxbJsonProviderServiceRegistration != null) {
            jaxbJsonProviderServiceRegistration.unregister();
        }
        jaxbJsonProviderServiceRegistration = context.registerService(JacksonJaxbJsonProvider.class, jacksonJaxbJsonProvider, props);
    }

    @Deactivate
    void stop() {
        if (jaxbJsonProviderServiceRegistration != null) {
            jaxbJsonProviderServiceRegistration.unregister();
            jaxbJsonProviderServiceRegistration = null;
        }
        jacksonJaxbJsonProvider = null;
    }
}
