package hu.blackbelt.cxf.providers;

import hu.blackbelt.cxf.extension.Configurable;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;
import java.util.Map;

@Provider
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
@Slf4j
public class JacksonProvider extends JacksonJaxbJsonProvider implements Configurable {

    public JacksonProvider(final ObjectMapper objectMapper) {
        super(objectMapper, JacksonProvider.DEFAULT_ANNOTATIONS);

        configure(SerializationFeature.INDENT_OUTPUT, false);
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false); // write timestamps as text instead of epoch to output
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void configure(final Map<String, Object> config) {
        config.forEach((k, v) -> {
            if (k.startsWith("JacksonProvider.SerializationFeature.")) {
                try {
                    final SerializationFeature feature = SerializationFeature.valueOf(k.replace("JacksonProvider.SerializationFeature.", ""));
                    log.info("Update SerializationFeature option '" + feature + "': " + v);
                    configure(feature, Boolean.parseBoolean((String) v));
                } catch (IllegalArgumentException ex) {
                    log.warn("Invalid SerializationFeature option: " + k);
                }
            } else if (k.startsWith("JacksonProvider.DeserializationFeature.")) {
                try {
                    final DeserializationFeature feature = DeserializationFeature.valueOf(k.replace("JacksonProvider.DeserializationFeature.", ""));
                    log.info("Update DeserializationFeature option '" + feature + "': " + v);
                    configure(feature, Boolean.parseBoolean((String) v));
                } catch (IllegalArgumentException ex) {
                    log.warn("Invalid DeserializationFeature option: " + k);
                }
            }
        });
    }
}
