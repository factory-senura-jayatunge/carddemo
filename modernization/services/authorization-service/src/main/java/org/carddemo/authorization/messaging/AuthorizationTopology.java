package org.carddemo.authorization.messaging;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.carddemo.authorization.processing.AuthorizationProcessor;
import org.carddemo.authorization.processing.AuthorizationRepository;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;

/**
 * The listener's broker side (messaging.md, contract §4.1, ruling B4): exactly the topology of
 * messaging.md for the authorization names, declared at start with declaration errors not
 * ignored, so a name that exists with other arguments fails the start. Nothing is ever deleted,
 * purged or declared with other arguments. {@code carddemo.authorization.listener.enabled=false}
 * leaves the broker out, for the database and API tests.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "carddemo.authorization.listener.enabled", havingValue = "true", matchIfMissing = true)
public class AuthorizationTopology {

    public static final String EXCHANGE = "carddemo.pauth";
    public static final String REQUEST_ROUTING_KEY = "request";
    public static final String REPLY_ROUTING_KEY = "reply";
    public static final String REQUEST_QUEUE = "carddemo.pauth.request";
    public static final String REPLY_QUEUE = "carddemo.pauth.reply";
    public static final String DEAD_LETTER_EXCHANGE = "carddemo.dlx";
    public static final String REQUEST_DLQ = "carddemo.pauth.request.dlq";
    static final int DELIVERY_LIMIT = 5;
    /** Delayed retry (ruling A2): a rejected request is held min(200 ms x its delivery count, 1000 ms). */
    static final int RETRY_MIN_MS = 200;
    static final int RETRY_MAX_MS = 1000;
    static final int PREFETCH = 10;
    /** Unsent rows younger than the reply's lifetime are in flight, not stuck. */
    static final Duration UNSENT_AFTER = Duration.ofSeconds(5);

    @Bean
    public Declarables authorizationDeclarables() {
        DirectExchange pauth = ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
        DirectExchange dlx = ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
        Queue request = QueueBuilder.durable(REQUEST_QUEUE).quorum().deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE).deadLetterRoutingKey(REQUEST_QUEUE)
                .withArgument("x-delayed-retry-type", "failed").withArgument("x-delayed-retry-min", RETRY_MIN_MS)
                .withArgument("x-delayed-retry-max", RETRY_MAX_MS).build();
        Queue reply = queue(REPLY_QUEUE);
        Queue dlq = QueueBuilder.durable(REQUEST_DLQ).quorum().build();
        Binding requestBinding = BindingBuilder.bind(request).to(pauth).with(REQUEST_ROUTING_KEY);
        Binding replyBinding = BindingBuilder.bind(reply).to(pauth).with(REPLY_ROUTING_KEY);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(REQUEST_QUEUE);
        return new Declarables(pauth, dlx, request, reply, dlq, requestBinding, replyBinding, dlqBinding);
    }

    /**
     * Durable quorum queue, delivery limit 5, dead-lettered to {@code carddemo.dlx} under its own name.
     * The request queue adds delayed retry: the five holds of 200 to 1000 ms take 3 s, so a brief
     * database fault can pass before the sixth reject dead-letters, inside the reply's 5 s lifetime.
     */
    static Queue queue(String name) {
        return QueueBuilder.durable(name).quorum().deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE).deadLetterRoutingKey(name).build();
    }

    /** The reply template: mandatory, so an unroutable reply comes back instead of vanishing. */
    @Bean
    public ReplyPublisher replyPublisher(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return new ReplyPublisher(template);
    }

    @Bean
    public AuthorizationListener authorizationListener(AuthorizationProcessor processor,
            AuthorizationRepository repository, OutboxRepository outbox, ReplyPublisher replyPublisher) {
        return new AuthorizationListener(processor, repository, outbox, replyPublisher);
    }

    /**
     * Our own admin, so Boot's {@code amqpAdmin} backs off: Boot declares that one as
     * {@code AmqpAdmin}, which a {@code RabbitAdmin} parameter cannot be matched against before it
     * exists.
     */
    @Bean
    public RabbitAdmin authorizationAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setIgnoreDeclarationExceptions(false);
        return admin;
    }

    @Bean
    public SimpleMessageListenerContainer authorizationListenerContainer(ConnectionFactory connectionFactory,
            RabbitAdmin admin, AuthorizationListener listener,
            @Value("${carddemo.authorization.listener.concurrency:4}") int concurrency) {
        // Declared here rather than lazily on the first connection, so a conflict stops the start.
        admin.initialize();
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(REQUEST_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(PREFETCH);
        container.setConcurrentConsumers(concurrency);
        container.setMissingQueuesFatal(true);
        container.setMessagePropertiesConverter(new AbsentContentType());
        container.setMessageListener(listener);
        return container;
    }

    @Bean
    public MeterBinder pauthOutboxUnsent(OutboxRepository outbox) {
        return registry -> Gauge.builder("pauth.outbox.unsent", outbox, AuthorizationTopology::unsent)
                .description("Replies still unsent " + UNSENT_AFTER.toSeconds() + " s after their commit")
                .register(registry);
    }

    private static double unsent(OutboxRepository outbox) {
        try {
            return outbox.countUnsentOlderThan(UNSENT_AFTER);
        } catch (DataAccessException e) {
            return Double.NaN;
        }
    }

    /**
     * Spring's message properties start as {@code application/octet-stream}, which would make a
     * request sent without a content type unparsable; an absent content type means CSV (§4.2).
     */
    static final class AbsentContentType extends DefaultMessagePropertiesConverter {
        @Override
        public MessageProperties toMessageProperties(AMQP.BasicProperties source, Envelope envelope, String charset) {
            MessageProperties properties = super.toMessageProperties(source, envelope, charset);
            if (source.getContentType() == null) {
                properties.setContentType(null);
            }
            return properties;
        }
    }
}
