package hu.blackbelt.jaxrs.providers;

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
