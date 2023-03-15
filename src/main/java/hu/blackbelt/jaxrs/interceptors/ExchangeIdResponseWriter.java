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
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exchange ID writer to response messages.
 */
@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE, service = Interceptor.class)
public class ExchangeIdResponseWriter extends AbstractPhaseInterceptor<Message> {

    private static final String RESPONSE_HEADER_KEY_EXCHANGE_ID = "X-Exchange-Id";

    public ExchangeIdResponseWriter() {
        super(Phase.POST_LOGICAL);
    }

    @Override
    public void handleMessage(final Message message) throws Fault {
        if (message.getExchange() != null) {
            final String exchangeId = (String) message.getExchange().get(ExchangeIdDecorator.KEY_EXCHANGE_ID);
            if (exchangeId != null) {
                Map<String, List> headers = (Map<String, List>) message.get(Message.PROTOCOL_HEADERS);
                if (headers == null) {
                    headers = new HashMap<>();
                    message.put(Message.PROTOCOL_HEADERS, headers);
                }
                headers.put(RESPONSE_HEADER_KEY_EXCHANGE_ID, Collections.singletonList(exchangeId));
            }
        }
    }

    @Override
    public void handleFault(Message message) {
        handleMessage(message);
    }
}
