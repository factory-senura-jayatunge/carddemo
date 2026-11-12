package org.carddemo.authorization.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.carddemo.authorization.AuthorizationApplication;
import org.carddemo.authorization.TestBroker;
import org.carddemo.authorization.processing.AuthorizationProcessor;
import org.carddemo.authorization.processing.AuthorizationStoreException;
import org.carddemo.authorization.processing.FailurePoint;
import org.carddemo.authorization.request.MessageKey;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.CoreFixtures.Account;
import org.carddemo.platform.testsupport.TestClock;
import org.carddemo.platform.testsupport.TestClockConfiguration;
import org.carddemo.platform.testsupport.TestDatabase;
import org.carddemo.platform.testsupport.TestTokensConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The listener against RabbitMQ in a private virtual host and the migrated database (contract §11
 * M14, M17 to M19, M22 to M26, M28). Requests that write a detail need backend's {@code V505}
 * grant; until it is merged those tests fail.
 */
@SpringBootTest(classes = AuthorizationApplication.class, properties = "carddemo.ledger.dir=target/no-ledger")
@Import({TestClockConfiguration.class, TestTokensConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthorizationListenerBrokerTest {

    static final TestDatabase DB = TestDatabase.shared();
    static final TestBroker BROKER = TestBroker.create();

    static final String CARD = "4111111111111111";
    static final String ACCT = "00000000091";
    static final String CUST = "000000091";
    static final Duration WAIT = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.AUTHORIZATION);
        BROKER.register(registry);
    }

    @AfterAll
    static void deleteVirtualHost() {
        BROKER.close();
    }

    @MockitoSpyBean
    ReplyPublisher publisher;

    @MockitoSpyBean
    AuthorizationProcessor processor;

    @Autowired
    CachingConnectionFactory serviceConnections;

    final JdbcClient admin = DB.admin();
    Connection requester;
    Channel channel;
    String replyQueue;

    @BeforeEach
    void fixtures() throws Exception {
        TestClock.shared().reset();
        DB.fixtures().deleteAll()
                .account(new Account(ACCT, "Y", new BigDecimal("200.00"), new BigDecimal("1000.00"),
                        new BigDecimal("300.00"), LocalDate.of(2014, 11, 20), LocalDate.of(2030, 5, 20),
                        LocalDate.of(2025, 5, 20), new BigDecimal("0.00"), new BigDecimal("0.00"), "A000000000", ""))
                .customer(CUST).card(CARD, ACCT, CUST);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(BROKER.amqpPort());
        factory.setVirtualHost(BROKER.vhost());
        factory.setUsername(BROKER.user());
        factory.setPassword(BROKER.password());
        requester = factory.newConnection();
        channel = requester.createChannel();
        replyQueue = channel.queueDeclare().getQueue();
        channel.queuePurge(AuthorizationTopology.REPLY_QUEUE);
        channel.queuePurge(AuthorizationTopology.REQUEST_DLQ);
    }

    @AfterEach
    void closeRequester() throws Exception {
        requester.close();
    }

    static String csv(String amount, String tranId) {
        return String.join(",", "261106", "093015", CARD, "SALE", "0426", "0100", "POS", "000000", amount, "5411",
                "840", "01", "MERCH0000000001", "GROCERY STORE", "SPRINGFIELD", "IL", "62701", tranId);
    }

    void send(String body, String contentType, String messageId, String correlationId, String replyTo)
            throws Exception {
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .contentType(contentType).messageId(messageId).correlationId(correlationId).replyTo(replyTo).build();
        channel.basicPublish(AuthorizationTopology.EXCHANGE, AuthorizationTopology.REQUEST_ROUTING_KEY, props,
                body.getBytes(StandardCharsets.UTF_8));
    }

    GetResponse receive(String queue) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            GetResponse r = channel.basicGet(queue, true);
            if (r != null) {
                return r;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no message on " + queue + " within " + WAIT);
    }

    /** Nothing more arrives on the queue within {@code quiet}. */
    void assertNothingMore(String queue, Duration quiet) throws Exception {
        Thread.sleep(quiet.toMillis());
        assertThat(channel.basicGet(queue, true)).isNull();
    }

    static String body(GetResponse r) {
        return new String(r.getBody(), StandardCharsets.UTF_8);
    }

    int details() {
        return admin.sql("SELECT COUNT(*) FROM pauth.auth_detail").query(Integer.class).single();
    }

    @Test
    @DisplayName("M1 over the broker: one 63-character reply with the request's correlation ID, 5000 ms expiration")
    void approvalReply() throws Exception {
        send(csv("50.00", "TRN000000000001"), "text/csv", "msg-1", "corr-1", replyQueue);
        GetResponse r = receive(replyQueue);
        String code = admin.sql("SELECT auth_id_code FROM pauth.auth_detail").query(String.class).single();
        assertThat(body(r)).isEqualTo(CARD + ",TRN000000000001," + code + ",00,0000,         50.00,");
        assertThat(r.getProps().getCorrelationId()).isEqualTo("corr-1");
        assertThat(r.getProps().getContentType()).isEqualTo("text/csv");
        assertThat(r.getProps().getExpiration()).isEqualTo("5000");
        assertThat(admin.sql("SELECT COUNT(*) FROM pauth.outbox WHERE sent_at IS NOT NULL AND attempts = 1")
                .query(Integer.class).single()).isEqualTo(1);
        assertNothingMore(replyQueue, Duration.ofMillis(500));
    }

    @Test
    @DisplayName("M17: a JSON request gets the JSON reply")
    void jsonReply() throws Exception {
        String json = "{\"authDate\":\"261106\",\"authTime\":\"093015\",\"cardNum\":\"" + CARD + "\",\"authType\":\"SALE\","
                + "\"cardExpiryDate\":\"0426\",\"messageType\":\"0100\",\"messageSource\":\"POS\","
                + "\"processingCode\":\"0\",\"transactionAmt\":50.00,\"merchantCategoryCode\":\"5411\","
                + "\"acqrCountryCode\":\"840\",\"posEntryMode\":\"1\",\"merchantId\":\"MERCH0000000001\","
                + "\"merchantName\":\"GROCERY STORE\",\"merchantCity\":\"SPRINGFIELD\",\"merchantState\":\"IL\","
                + "\"merchantZip\":\"62701\",\"transactionId\":\"TRN000000000001\"}";
        send(json, "application/json", "msg-1", "corr-1", replyQueue);
        GetResponse r = receive(replyQueue);
        String code = admin.sql("SELECT auth_id_code FROM pauth.auth_detail").query(String.class).single();
        assertThat(r.getProps().getContentType()).isEqualTo("application/json");
        assertThat(body(r)).isEqualTo("{\"cardNum\":\"" + CARD + "\",\"transactionId\":\"TRN000000000001\","
                + "\"authIdCode\":\"" + code + "\",\"respCode\":\"00\",\"respReason\":\"0000\",\"approvedAmt\":\"50.00\"}");
    }

    @Test
    @DisplayName("M18: the same message ID twice: one decision, the identical reply twice")
    void sameMessageIdTwice() throws Exception {
        send(csv("50.00", "TRN000000000001"), "text/csv", "msg-1", "corr-1", replyQueue);
        String first = body(receive(replyQueue));
        send(csv("50.00", "TRN000000000001"), "text/csv", "msg-1", "corr-2", replyQueue);
        GetResponse second = receive(replyQueue);
        assertThat(body(second)).isEqualTo(first).hasSize(63);
        assertThat(second.getProps().getCorrelationId()).isEqualTo("corr-2");
        assertThat(details()).isEqualTo(1);
        assertThat(admin.sql("SELECT approved_auth_cnt FROM pauth.auth_summary").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("M19: without a message ID, the same card, transaction ID, date and time is a duplicate")
    void sameContentWithoutMessageId() throws Exception {
        send(csv("50.00", "TRN000000000001"), "text/csv", null, "corr-1", replyQueue);
        String first = body(receive(replyQueue));
        send(csv("50.00", "TRN000000000001"), "text/csv", null, "corr-2", replyQueue);
        assertThat(body(receive(replyQueue))).isEqualTo(first);
        assertThat(details()).isEqualTo(1);
        assertThat(admin.sql("SELECT key_kind FROM pauth.processed_message").query(String.class).single())
                .isEqualTo("K");
    }

    @Test
    @DisplayName("M22: a refused confirm requeues; the redelivery publishes the same outbox row, once")
    void refusedConfirm() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> calls.getAndIncrement() == 0 ? ReplyPublisher.Result.NOT_CONFIRMED : inv.callRealMethod())
                .when(publisher).publish(anyLong(), any(), anyString(), anyString());
        send(csv("50.00", "TRN000000000001"), "text/csv", "msg-1", "corr-1", replyQueue);
        receive(replyQueue);
        assertNothingMore(replyQueue, Duration.ofSeconds(1));
        assertThat(details()).isEqualTo(1);
        assertThat(admin.sql("SELECT CONCAT(COUNT(*), '|', MAX(attempts), '|', COUNT(sent_at)) FROM pauth.outbox")
                .query(String.class).single()).isEqualTo("1|2|1");
    }

    @Test
    @DisplayName("M23: the connection lost between commit and publish: the redelivery publishes the unsent row once")
    void lostBetweenCommitAndPublish() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                serviceConnections.resetConnection();
                return ReplyPublisher.Result.NOT_CONFIRMED;
            }
            return inv.callRealMethod();
        }).when(publisher).publish(anyLong(), any(), anyString(), anyString());
        send(csv("50.00", "TRN000000000001"), "text/csv", "msg-1", "corr-1", replyQueue);
        receive(replyQueue);
        assertNothingMore(replyQueue, Duration.ofSeconds(1));
        assertThat(details()).isEqualTo(1);
        assertThat(admin.sql("SELECT COUNT(*) FROM pauth.outbox WHERE sent_at IS NOT NULL").query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    @DisplayName("M24: a SQL failure on every delivery: 6 deliveries held 200/400/600/800/1000 ms by the queue's delayed retry, dead-lettered within the reply's 5 s; another request is not held up; no reply, no write")
    void sqlFailureEveryTime() throws Exception {
        byte[] failing = MessageKey.of("msg-1", null).hash();
        List<Long> deliveredAt = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            if (!Arrays.equals(inv.<MessageKey>getArgument(1).hash(), failing)) {
                return inv.callRealMethod();
            }
            deliveredAt.add(System.nanoTime());
            throw new AuthorizationStoreException(FailurePoint.SUMMARY_WRITE, new QueryTimeoutException("simulated"));
        }).when(processor).process(any(), any(), any());
        long sent = System.nanoTime();
        send(csv("50.00", "TRN000000000001"), "text/csv", "msg-1", "corr-1", replyQueue);

        // During the 400 ms hold after the second delivery, a second request is answered at once.
        awaitDeliveries(deliveredAt, 2);
        send(csv("60.00", "TRN000000000002"), "text/csv", "msg-2", "corr-2", replyQueue);
        GetResponse other = receive(replyQueue);
        assertThat(other.getProps().getCorrelationId()).isEqualTo("corr-2");
        assertThat(deliveredAt).as("msg-2 answered before msg-1's third delivery").hasSize(2);

        GetResponse dead = receive(AuthorizationTopology.REQUEST_DLQ);
        long deadAt = System.nanoTime();
        assertThat(dead.getProps().getMessageId()).isEqualTo("msg-1");
        // The quiet second also lets a seventh delivery, if the broker made one, reach the count.
        assertNothingMore(AuthorizationTopology.REQUEST_DLQ, Duration.ofSeconds(1));
        // A quorum queue dead-letters a message rejected more often than its delivery limit: the
        // first delivery and 5 requeued ones, each held min(200 ms x its delivery count, 1000 ms).
        assertThat(deliveredAt).hasSize(6);
        long[] held = {200, 400, 600, 800, 1000};
        for (int i = 1; i < 6; i++) {
            long gap = Duration.ofNanos(deliveredAt.get(i) - deliveredAt.get(i - 1)).toMillis();
            assertThat(gap).as("hold before delivery %d", i + 1).isGreaterThanOrEqualTo(held[i - 1] - 50);
        }
        assertThat(Duration.ofNanos(deadAt - sent)).isLessThan(Duration.ofSeconds(5));
        assertThat(channel.basicGet(replyQueue, true)).isNull();
        assertThat(details()).isEqualTo(1);
        assertThat(admin.sql("SELECT COUNT(*) FROM pauth.outbox").query(Integer.class).single()).isEqualTo(1);
        assertThat(admin.sql("SELECT COUNT(*) FROM pauth.processed_message WHERE msg_key = :k")
                .param("k", failing).query(Integer.class).single()).isZero();
    }

    static void awaitDeliveries(List<Long> deliveredAt, int count) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (deliveredAt.size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("fewer than " + count + " deliveries within " + WAIT);
            }
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("§4.1, ruling A2: the request queue alone retries after a delay; the reply queue and the DLQ do not")
    void delayedRetryArguments() throws Exception {
        assertThat(BROKER.queueArguments(AuthorizationTopology.REQUEST_QUEUE)).containsAllEntriesOf(Map.of(
                "x-delayed-retry-type", "failed", "x-delayed-retry-min", 200, "x-delayed-retry-max", 1000));
        assertThat(BROKER.queueArguments(AuthorizationTopology.REPLY_QUEUE).keySet())
                .noneMatch(k -> k.startsWith("x-delayed-retry"));
        assertThat(BROKER.queueArguments(AuthorizationTopology.REQUEST_DLQ).keySet())
                .noneMatch(k -> k.startsWith("x-delayed-retry"));
    }
    @Test
    @DisplayName("M25: an empty correlation ID is answered with the message ID")
    void correlationFallback() throws Exception {
        send(csv("50.00", "TRN000000000001"), "text/csv", "msg-1", "", replyQueue);
        assertThat(receive(replyQueue).getProps().getCorrelationId()).isEqualTo("msg-1");
    }

    @Test
    @DisplayName("M26: without reply_to the reply is on carddemo.pauth.reply")
    void defaultReplyQueue() throws Exception {
        send(csv("50.00", "TRN000000000001"), "text/csv", "msg-1", "corr-1", null);
        assertThat(body(receive(AuthorizationTopology.REPLY_QUEUE))).hasSize(63).startsWith(CARD + ",");
    }

    @Test
    @DisplayName("M14, M16, M28: unparsable requests are dead-lettered without a reply or a write; no log holds the card")
    void unparsableDeadLettered(CapturedOutput output) throws Exception {
        String seventeen = csv("50.00", "TRN000000000001");
        seventeen = seventeen.substring(0, seventeen.lastIndexOf(','));
        send(seventeen, "text/csv", "msg-17", "corr-1", replyQueue);
        send(csv("50.00", "TRN000000000001") + "x".repeat(400), "text/csv", "msg-big", "corr-1", replyQueue);
        send(csv("50.00", "TRN000000000001"), "application/xml", "msg-type", "corr-1", replyQueue);
        for (int i = 0; i < 3; i++) {
            receive(AuthorizationTopology.REQUEST_DLQ);
        }
        assertThat(channel.basicGet(replyQueue, true)).isNull();
        assertThat(admin.sql("SELECT COUNT(*) FROM pauth.processed_message").query(Integer.class).single()).isZero();
        assertThat(output).contains("M003").doesNotContain(CARD);
    }

    @Test
    @DisplayName("M28: a full exchange leaves no card number, body or credential in the log")
    void noSecretsInTheLog(CapturedOutput output) throws Exception {
        send(csv("50.00", "TRN000000000001"), "text/csv", "msg-1", "corr-1", replyQueue);
        receive(replyQueue);
        assertThat(output).doesNotContain(CARD, "GROCERY STORE", BROKER.password(), DB.password(ServiceLogin.AUTHORIZATION));
    }

    @Test
    @DisplayName("§4.1, B4: a queue that exists with other arguments fails the start and is left as it was")
    void conflictingQueueFailsTheStart() throws Exception {
        Map<String, Object> other = Map.of("x-queue-type", "quorum", "x-delivery-limit", 9);
        try (TestBroker conflicting = TestBroker.create()) {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("127.0.0.1");
            factory.setPort(conflicting.amqpPort());
            factory.setVirtualHost(conflicting.vhost());
            factory.setUsername(conflicting.user());
            factory.setPassword(conflicting.password());
            try (Connection c = factory.newConnection(); Channel ch = c.createChannel()) {
                ch.queueDeclare(AuthorizationTopology.REQUEST_QUEUE, true, false, false, other);
            }
            // Arguments, not builder properties: those are defaults, below application.yml, whose
            // authorization_svc login does not exist on a test virtual host (ACCESS_REFUSED).
            assertThatThrownBy(() -> new SpringApplicationBuilder(AuthorizationApplication.class,
                    TestClockConfiguration.class, TestTokensConfiguration.class)
                    .run("--carddemo.ledger.dir=target/no-ledger", "--server.port=0",
                            "--spring.datasource.url=" + DB.jdbcUrl(),
                            "--spring.datasource.username=" + DB.loginName(ServiceLogin.AUTHORIZATION),
                            "--spring.datasource.password=" + DB.password(ServiceLogin.AUTHORIZATION),
                            "--spring.rabbitmq.host=127.0.0.1", "--spring.rabbitmq.port=" + conflicting.amqpPort(),
                            "--spring.rabbitmq.virtual-host=" + conflicting.vhost(),
                            "--spring.rabbitmq.username=" + conflicting.user(),
                            "--spring.rabbitmq.password=" + conflicting.password())
                    .close())
                    .satisfies(e -> assertThat(causes(e)).contains("PRECONDITION_FAILED"));
            try (Connection c = factory.newConnection(); Channel ch = c.createChannel()) {
                ch.queueDeclare(AuthorizationTopology.REQUEST_QUEUE, true, false, false, other);
            }
        }
    }

    static String causes(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
