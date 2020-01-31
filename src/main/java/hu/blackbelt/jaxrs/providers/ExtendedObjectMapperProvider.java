package hu.blackbelt.jaxrs.providers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr353.JSR353Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;

import java.util.Dictionary;
import java.util.Hashtable;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ExtendedObjectMapperProvider {

    ServiceRegistration<ObjectMapper> objectMapperServiceRegistration;

    public static ObjectMapper getExtendedObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .registerModule(new ParameterNamesModule())
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .registerModule(new GuavaModule())
                .registerModule(new JSR353Module())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Activate
    public void activate(BundleContext bundleContext) {

        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put("source", "cxf-jaxrs-application-manager");
        properties.put("type", "extended");
        objectMapperServiceRegistration = bundleContext.registerService(ObjectMapper.class,
                getExtendedObjectMapper(), properties);
    }

    @Deactivate
    public void deactivate() {
        objectMapperServiceRegistration.unregister();
    }

}
