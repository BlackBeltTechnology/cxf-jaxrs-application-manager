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

import lombok.extern.slf4j.Slf4j;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.ParamConverter;
import javax.ws.rs.ext.ParamConverterProvider;
import javax.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

@Provider
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
@Slf4j
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE, service = ISO8601DateParamHandler.class)
public class ISO8601DateParamHandler implements ParamConverterProvider {

    private static final SimpleDateFormat DEFAULT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private DateFormat dateFormat = DEFAULT_DATE_FORMAT;

    private static final String DATE_FORMAT_KEY = "DATE_FORMAT";

    @Override
    public <T> ParamConverter<T> getConverter(final Class<T> clazz, final Type type, final Annotation[] annotations) {
        if (Date.class.equals(type)) {
            @SuppressWarnings("unchecked")
            final ParamConverter<T> paramConverter = (ParamConverter<T>) new DateParameterConverter();
            return paramConverter;
        }
        return null;
    }

    class DateParameterConverter implements ParamConverter<Date> {

        @Override
        public Date fromString(final String string) {
            try {
                return dateFormat.parse(string);
            } catch (ParseException ex) {
                throw new IllegalArgumentException("Invalid date parameter: " + string + ex);
            }
        }

        @Override
        public String toString(final Date date) {
            return dateFormat.format(date);
        }
    }

    @Activate
    @Modified
    void configure(final Map<String, Object> config) {
        final String newDateFormat = (String) config.get(getClass().getSimpleName() + "." + DATE_FORMAT_KEY);
        if (newDateFormat != null) {
            log.info("Update ISO8601DateParamHandler date format: " + newDateFormat);
            dateFormat = new SimpleDateFormat(newDateFormat);
        }
    }
}
