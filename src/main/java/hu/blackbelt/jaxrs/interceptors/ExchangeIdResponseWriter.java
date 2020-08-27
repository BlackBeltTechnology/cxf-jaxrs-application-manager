package hu.blackbelt.jaxrs.interceptors;

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
