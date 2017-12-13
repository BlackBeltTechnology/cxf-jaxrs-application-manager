package hu.blackbelt.cxf.providers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import lombok.extern.slf4j.Slf4j;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;
import java.util.Map;

@Provider
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
@Slf4j
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class JacksonProvider extends JacksonJaxbJsonProvider {

    public JacksonProvider() {
        super(JacksonProvider.DEFAULT_ANNOTATIONS);

        _mapperConfig.getConfiguredMapper().registerModule(new JavaTimeModule());

        configure(SerializationFeature.INDENT_OUTPUT, false);
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false); // write timestamps as text instead of epoch to output
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Activate
    @Modified
    void start(final Map<String, Object> config) {
        final String className = getClass().getSimpleName();
        config.forEach((k, v) -> {
            if (k.startsWith(className + ".SerializationFeature.")) {
                try {
                    final SerializationFeature feature = SerializationFeature.valueOf(k.replace(className + ".SerializationFeature.", ""));
                    log.info("Update SerializationFeature option '" + feature + "': " + v);
                    configure(feature, Boolean.parseBoolean((String) v));
                } catch (IllegalArgumentException ex) {
                    log.warn("Invalid SerializationFeature option: " + k);
                }
            } else if (k.startsWith(className + ".DeserializationFeature.")) {
                try {
                    final DeserializationFeature feature = DeserializationFeature.valueOf(k.replace(className + ".DeserializationFeature.", ""));
                    log.info("Update DeserializationFeature option '" + feature + "': " + v);
                    configure(feature, Boolean.parseBoolean((String) v));
                } catch (IllegalArgumentException ex) {
                    log.warn("Invalid DeserializationFeature option: " + k);
                }
            } else if ((className + ".ObjectMapper.modules").equals(k) && v != null) {
                final String moduleList = (String) v;
                for (final String moduleName : moduleList.split("\\s*,\\s*")) {
                    try {
                        log.info("Registering ObjectMapper module: " + moduleName);
                        final Module m = (Module) Class.forName(moduleName).newInstance();
                        _mapperConfig.getConfiguredMapper().registerModule(m);
                    } catch (ClassNotFoundException ex) {
                        log.error("Unknown ObjectMapper module: " + moduleName, ex);
                    } catch (InstantiationException | IllegalAccessException ex) {
                        log.error("Unable to register ObjectMapper module: " + moduleName, ex);
                    }
                }
            }
        });
    }
}
