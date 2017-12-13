package com.avon.choice.cxf.providers;

import lombok.extern.slf4j.Slf4j;

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

    public void configure(final Map<String, Object> config) {
        final String newDateFormat = (String) config.get("ISO8601DateParamHandler." + DATE_FORMAT_KEY);
        if (newDateFormat != null) {
            log.info("Update ISO8601DateParamHandler date format: " + newDateFormat);
            dateFormat = new SimpleDateFormat(newDateFormat);
        }
    }
}
