package hu.blackbelt.jaxrs.interceptors;

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

import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.slf4j.MDC;

import java.util.Objects;
import java.util.UUID;

/**
 * Exchange ID decorator.
 */
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE, service = Interceptor.class)
public class ExchangeIdDecorator extends AbstractPhaseInterceptor<Message> {

    public static final String KEY_EXCHANGE_ID = "exchangeId";
    private static final String MDC_KEY_EXCHANGE_ID = "RequestExchangeId";

    public ExchangeIdDecorator() {
        super(Phase.RECEIVE);
    }

    @Override
    public void handleMessage(final Message message) throws Fault {
        final String exchangeId = createExchangeId(message);
        Objects.requireNonNull(exchangeId, "Exchange ID creation failed");
        MDC.put(MDC_KEY_EXCHANGE_ID, exchangeId);
    }

    /**
     * Create ExchangeID that is used by cxf logging feature too (without enabling logging).
     *
     * @param message message
     */
    public String createExchangeId(final Message message) {
        final Exchange exchange = message.getExchange();
        String exchangeId = (String) exchange.get(KEY_EXCHANGE_ID);
        if (exchangeId == null) {
            exchangeId = UUID.randomUUID().toString();
            exchange.put(KEY_EXCHANGE_ID, exchangeId);
        }
        return exchangeId;
    }
}
