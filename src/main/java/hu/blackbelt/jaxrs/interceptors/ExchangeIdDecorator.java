package hu.blackbelt.jaxrs.interceptors;

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
    private static final String MDC_KEY_EXCHANGE_ID = "ExchangeId";

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
