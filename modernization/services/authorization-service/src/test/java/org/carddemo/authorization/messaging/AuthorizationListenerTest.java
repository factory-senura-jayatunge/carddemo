package org.carddemo.authorization.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.carddemo.authorization.decision.Decision;
import org.carddemo.authorization.decision.Reason;
import org.carddemo.authorization.processing.AlreadyProcessedException;
import org.carddemo.authorization.processing.AuthorizationProcessor;
import org.carddemo.authorization.processing.AuthorizationRepository;
import org.carddemo.authorization.processing.AuthorizationRepository.StoredReply;
import org.carddemo.authorization.processing.AuthorizationStoreException;
import org.carddemo.authorization.processing.FailurePoint;
import org.carddemo.authorization.processing.Outcome;
import org.carddemo.authorization.processing.ReplyRoute;
import org.carddemo.authorization.reply.AuthorizationReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

/**
 * The listener's settle rules (contract §4.3, §4.4, §6.2 steps 10 to 12, §6.3, §6.4) with stand-ins
 * for the transaction and the broker; the broker tests run them against RabbitMQ.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthorizationListenerTest {

    static final String CARD = "4111111111111111";
    static final String CSV = "240405,143015," + CARD + ",SALE,0426,0100  ,POS   ,000000,50.00,5411,840,01,"
            + "MERCH0000000001,GROCERY STORE,SPRINGFIELD,IL,62701,TRN000000000001";
    static final String JSON = "{\"authDate\":\"240405\",\"authTime\":\"143015\",\"cardNum\":\"" + CARD + "\","
            + "\"authType\":\"SALE\",\"cardExpiryDate\":\"0426\",\"messageType\":\"0100\",\"messageSource\":\"POS\","
            + "\"processingCode\":\"0\",\"transactionAmt\":\"50.00\",\"merchantCategoryCode\":\"5411\","
            + "\"acqrCountryCode\":\"840\",\"posEntryMode\":\"1\",\"merchantId\":\"MERCH0000000001\","
            + "\"merchantName\":\"GROCERY STORE\",\"merchantCity\":\"SPRINGFIELD\",\"merchantState\":\"IL\","
            + "\"merchantZip\":\"62701\",\"transactionId\":\"TRN000000000001\"}";
    static final AuthorizationReply REPLY = new AuthorizationReply(CARD, "TRN000000000001", "000042", "00", "0000",
            new BigDecimal("50.00"));
    static final ReplyRoute TO_REQUESTER = new ReplyRoute("", "amq.gen-reply", "corr-1");

    final AuthorizationProcessor processor = mock(AuthorizationProcessor.class);
    final AuthorizationRepository repository = mock(AuthorizationRepository.class);
    final OutboxRepository outbox = mock(OutboxRepository.class);
    final ReplyPublisher publisher = mock(ReplyPublisher.class);
    final Channel channel = mock(Channel.class);
    final AuthorizationListener listener = new AuthorizationListener(processor, repository, outbox, publisher);

    @BeforeEach
    void newRequest() {
        when(processor.findProcessed(any())).thenReturn(Optional.empty());
        when(processor.process(any(), any(), any())).thenReturn(
                new Outcome(new Decision(Reason.APPROVED, new BigDecimal("50.00")), REPLY, 42L, 7L, REPLY.toCsv()));
        when(publisher.publish(anyLong(), any(), anyString(), anyString())).thenReturn(ReplyPublisher.Result.CONFIRMED);
        when(repository.insertOutbox(any(), anyString(), anyInt(), anyString())).thenReturn(8L);
    }

    static Message message(String body, String contentType, String messageId, String correlationId, String replyTo,
            boolean redelivered) {
        MessageProperties p = new MessageProperties();
        p.setContentType(contentType);
        p.setMessageId(messageId);
        p.setCorrelationId(correlationId);
        p.setReplyTo(replyTo);
        p.setRedelivered(redelivered);
        p.setDeliveryTag(99L);
        return new Message(body.getBytes(StandardCharsets.UTF_8), p);
    }

    static Message csv() {
        return message(CSV, "text/csv", "msg-1", "corr-1", "amq.gen-reply", false);
    }

    @Test
    @DisplayName("§6.2: commit, then publish with a confirm, then mark the row sent and acknowledge")
    void confirmedReplyIsAcknowledged() throws Exception {
        listener.onMessage(csv(), channel);
        verify(processor).process(any(), any(), eq(TO_REQUESTER));
        verify(publisher).publish(7L, TO_REQUESTER, "text/csv", REPLY.toCsv());
        verify(outbox).markSent(7L);
        verify(channel).basicAck(99L, false);
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
        verify(channel, never()).basicReject(anyLong(), any(Boolean.class));
    }

    @Test
    @DisplayName("M22, §6.2 step 12: an unconfirmed reply counts an attempt and requeues the request")
    void unconfirmedReplyRequeues(CapturedOutput output) throws Exception {
        when(publisher.publish(anyLong(), any(), anyString(), anyString()))
                .thenReturn(ReplyPublisher.Result.NOT_CONFIRMED);
        listener.onMessage(csv(), channel);
        verify(outbox).markAttempt(7L);
        verify(outbox, never()).markSent(anyLong());
        verify(channel).basicReject(99L, true);
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
        verify(channel, never()).basicAck(anyLong(), any(Boolean.class));
        assertThat(output).contains("M004");
    }

    @Test
    @DisplayName("§4.4: a returned (unroutable) reply counts as sent, with a warning")
    void returnedReplyCountsAsSent(CapturedOutput output) throws Exception {
        when(publisher.publish(anyLong(), any(), anyString(), anyString())).thenReturn(ReplyPublisher.Result.RETURNED);
        listener.onMessage(csv(), channel);
        verify(outbox).markSent(7L);
        verify(channel).basicAck(99L, false);
        assertThat(output).contains("returned unroutable", "corr-1");
    }

    @Test
    @DisplayName("§6.2 step 11: a confirmed reply whose row cannot be marked is acknowledged, with a warning")
    void markFailureStillAcknowledges(CapturedOutput output) throws Exception {
        doThrow(new DataAccessResourceFailureException("down")).when(outbox).markSent(7L);
        listener.onMessage(csv(), channel);
        verify(outbox).markSent(7L);
        verify(channel).basicAck(99L, false);
        verify(channel, never()).basicReject(anyLong(), any(Boolean.class));
        verify(publisher, org.mockito.Mockito.times(1)).publish(anyLong(), any(), anyString(), anyString());
        assertThat(output.getOut().lines().filter(l -> l.contains("not marked sent")))
                .singleElement().satisfies(line -> assertThat(line)
                        .contains("WARN", "msg-1", "outbox_id 7")
                        .doesNotContain(CARD, "************1111", REPLY.toCsv(), "GROCERY"));
    }

    @Test
    @DisplayName("M25: an empty correlation ID is replaced by the message ID")
    void correlationFallsBackToTheMessageId() throws Exception {
        listener.onMessage(message(CSV, "text/csv", "msg-1", "", "amq.gen-reply", false), channel);
        verify(processor).process(any(), any(), eq(new ReplyRoute("", "amq.gen-reply", "msg-1")));
    }

    @Test
    @DisplayName("M26: without reply_to the reply goes to carddemo.pauth with key reply")
    void noReplyTo() throws Exception {
        listener.onMessage(message(CSV, "text/csv", "msg-1", "corr-1", null, false), channel);
        verify(processor).process(any(), any(), eq(new ReplyRoute("carddemo.pauth", "reply", "corr-1")));
    }

    @Test
    @DisplayName("M14, M16, M28: an unparsable request is rejected without requeue, with no reply, write or body in the log")
    void unparsableIsDeadLettered(CapturedOutput output) throws Exception {
        String seventeen = CSV.substring(0, CSV.lastIndexOf(','));
        listener.onMessage(message(seventeen, "text/csv", "msg-1", "corr-1", "amq.gen-reply", false), channel);
        listener.onMessage(message(CSV, "application/xml", "msg-2", "corr-1", "amq.gen-reply", false), channel);
        verify(channel, org.mockito.Mockito.times(2)).basicReject(99L, false);
        verifyNoInteractions(processor, repository, outbox, publisher);
        assertThat(output).contains("M003", "msg-1", "TOO_FEW_VALUES", "msg-2", "UNKNOWN_CONTENT_TYPE")
                .doesNotContain(CARD, "GROCERY");
    }

    @Test
    @DisplayName("M18, §6.3: a duplicate gets the stored reply on a new outbox row; no decision is made")
    void duplicateGetsTheStoredReply() throws Exception {
        when(processor.findProcessed(any())).thenReturn(Optional.of(new StoredReply(42L, REPLY.toCsv())));
        listener.onMessage(csv(), channel);
        verify(processor, never()).process(any(), any(), any());
        verify(outbox, never()).findUnsent(any(), anyString());
        verify(repository).insertOutbox(TO_REQUESTER, "text/csv", 5000, REPLY.toCsv());
        verify(publisher).publish(8L, TO_REQUESTER, "text/csv", REPLY.toCsv());
        verify(outbox).markSent(8L);
        verify(channel).basicAck(99L, false);
    }

    @Test
    @DisplayName("§6.3: a JSON duplicate gets the stored reply rebuilt as JSON")
    void jsonDuplicate() throws Exception {
        when(processor.findProcessed(any())).thenReturn(Optional.of(new StoredReply(42L, REPLY.toCsv())));
        listener.onMessage(message(JSON, "application/json", "msg-1", "corr-1", "amq.gen-reply", false), channel);
        verify(repository).insertOutbox(TO_REQUESTER, "application/json", 5000, REPLY.toJson());
    }

    @Test
    @DisplayName("M22, M23: a redelivery whose reply is unsent publishes that same row, without a new one")
    void redeliveryPublishesTheUnsentRow() throws Exception {
        when(processor.findProcessed(any())).thenReturn(Optional.of(new StoredReply(42L, REPLY.toCsv())));
        when(outbox.findUnsent(TO_REQUESTER, REPLY.toCsv())).thenReturn(OptionalLong.of(7L));
        listener.onMessage(message(CSV, "text/csv", "msg-1", "corr-1", "amq.gen-reply", true), channel);
        verify(repository, never()).insertOutbox(any(), anyString(), anyInt(), anyString());
        verify(publisher).publish(7L, TO_REQUESTER, "text/csv", REPLY.toCsv());
        verify(outbox).markSent(7L);
        verify(channel).basicAck(99L, false);
    }

    @Test
    @DisplayName("§6.3: a redelivery whose reply was already sent gets a new row")
    void redeliveryAfterTheReplyWasSent() throws Exception {
        when(processor.findProcessed(any())).thenReturn(Optional.of(new StoredReply(42L, REPLY.toCsv())));
        when(outbox.findUnsent(any(), anyString())).thenReturn(OptionalLong.empty());
        listener.onMessage(message(CSV, "text/csv", "msg-1", "corr-1", "amq.gen-reply", true), channel);
        verify(repository).insertOutbox(TO_REQUESTER, "text/csv", 5000, REPLY.toCsv());
        verify(publisher).publish(8L, TO_REQUESTER, "text/csv", REPLY.toCsv());
    }

    @Test
    @DisplayName("§6.3: a key stored by another delivery during the transaction takes the duplicate path")
    void keyStoredDuringTheTransaction() throws Exception {
        when(processor.findProcessed(any())).thenReturn(Optional.empty(),
                Optional.of(new StoredReply(42L, REPLY.toCsv())));
        when(processor.process(any(), any(), any())).thenThrow(new AlreadyProcessedException());
        listener.onMessage(csv(), channel);
        verify(repository).insertOutbox(TO_REQUESTER, "text/csv", 5000, REPLY.toCsv());
        verify(channel).basicAck(99L, false);
    }

    @Test
    @DisplayName("M24, §6.4: a SQL failure requeues the request with no reply; the log names the legacy location")
    void sqlFailureRequeues(CapturedOutput output) throws Exception {
        when(processor.process(any(), any(), any())).thenThrow(
                new AuthorizationStoreException(FailurePoint.SUMMARY_WRITE, new QueryTimeoutException("slow")));
        listener.onMessage(csv(), channel);
        verify(channel).basicReject(99L, true);
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
        verifyNoInteractions(publisher);
        assertThat(output).contains("I003", "msg-1", "************1111").doesNotContain(CARD);
    }

    @Test
    @DisplayName("§6.4: a failing duplicate-key read requeues the request too")
    void duplicateReadFailureRequeues() throws Exception {
        when(processor.findProcessed(any())).thenThrow(new AuthorizationStoreException(
                FailurePoint.PROCESSED_MESSAGE_READ, new DataAccessResourceFailureException("down")));
        listener.onMessage(csv(), channel);
        verify(channel).basicReject(99L, true);
    }

    @Test
    @DisplayName("Any other failure is requeued, never left unsettled")
    void unexpectedFailureRequeues() throws Exception {
        when(processor.process(any(), any(), any())).thenThrow(new IllegalStateException("boom " + CARD));
        listener.onMessage(csv(), channel);
        verify(channel).basicReject(99L, true);
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
    }

    @Test
    @DisplayName("M28: the decision log line masks the card")
    void decisionLogMasksTheCard(CapturedOutput output) throws Exception {
        listener.onMessage(csv(), channel);
        assertThat(output).contains("authorization decided", "msg-1", "************1111").doesNotContain(CARD);
    }

    @Test
    @DisplayName("§4.2: a request without a content type stays without one, so it is read as CSV")
    void absentContentType() {
        AuthorizationTopology.AbsentContentType converter = new AuthorizationTopology.AbsentContentType();
        Envelope envelope = new Envelope(1L, false, "carddemo.pauth", "request");
        assertThat(converter.toMessageProperties(new AMQP.BasicProperties.Builder().build(), envelope, "UTF-8")
                .getContentType()).isNull();
        assertThat(converter.toMessageProperties(new AMQP.BasicProperties.Builder().contentType("text/plain").build(),
                envelope, "UTF-8").getContentType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("§4.1, B4: exactly the messaging.md topology of the authorization names")
    void topology() {
        Declarables d = new AuthorizationTopology().authorizationDeclarables();
        assertThat(d.getDeclarablesByType(Exchange.class)).extracting(Exchange::getName, Exchange::getType,
                Exchange::isDurable, Exchange::isAutoDelete).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("carddemo.pauth", "direct", true, false),
                org.assertj.core.groups.Tuple.tuple("carddemo.dlx", "direct", true, false));
        assertThat(d.getDeclarablesByType(Queue.class)).extracting(Queue::getName, Queue::isDurable,
                Queue::isExclusive, Queue::isAutoDelete, Queue::getArguments).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("carddemo.pauth.request", true, false, false, Map.of(
                        "x-queue-type", "quorum", "x-delivery-limit", 5,
                        "x-dead-letter-exchange", "carddemo.dlx", "x-dead-letter-routing-key", "carddemo.pauth.request",
                        "x-delayed-retry-type", "failed", "x-delayed-retry-min", 200, "x-delayed-retry-max", 1000)),
                org.assertj.core.groups.Tuple.tuple("carddemo.pauth.reply", true, false, false, Map.of(
                        "x-queue-type", "quorum", "x-delivery-limit", 5,
                        "x-dead-letter-exchange", "carddemo.dlx", "x-dead-letter-routing-key", "carddemo.pauth.reply")),
                org.assertj.core.groups.Tuple.tuple("carddemo.pauth.request.dlq", true, false, false,
                        Map.of("x-queue-type", "quorum")));
        assertThat(d.getDeclarablesByType(Binding.class)).extracting(Binding::getExchange, Binding::getRoutingKey,
                Binding::getDestination).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("carddemo.pauth", "request", "carddemo.pauth.request"),
                org.assertj.core.groups.Tuple.tuple("carddemo.pauth", "reply", "carddemo.pauth.reply"),
                org.assertj.core.groups.Tuple.tuple("carddemo.dlx", "carddemo.pauth.request", "carddemo.pauth.request.dlq"));
    }

    @Test
    @DisplayName("§4.4: the reply carries the content type, correlation ID, a 5000 ms expiration and is transient")
    void replyProperties() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        Message[] sent = new Message[1];
        doAnswer(inv -> {
            sent[0] = inv.getArgument(2);
            CorrelationData cd = inv.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(eq(""), eq("amq.gen-reply"), any(Message.class), any(CorrelationData.class));
        assertThat(new ReplyPublisher(template).publish(7L, TO_REQUESTER, "text/csv", REPLY.toCsv()))
                .isEqualTo(ReplyPublisher.Result.CONFIRMED);
        MessageProperties p = sent[0].getMessageProperties();
        assertThat(p.getContentType()).isEqualTo("text/csv");
        assertThat(p.getCorrelationId()).isEqualTo("corr-1");
        assertThat(p.getExpiration()).isEqualTo("5000");
        assertThat(p.getDeliveryMode()).isEqualTo(MessageDeliveryMode.NON_PERSISTENT);
        assertThat(new String(sent[0].getBody(), StandardCharsets.UTF_8)).isEqualTo(REPLY.toCsv()).hasSize(63);
    }

    @Test
    @DisplayName("§6.2 step 12: a nack or a send failure is not a confirm")
    void nackIsNotConfirmed() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(new ReplyPublisher(template).publish(7L, TO_REQUESTER, "text/csv", REPLY.toCsv()))
                .isEqualTo(ReplyPublisher.Result.NOT_CONFIRMED);
        RabbitTemplate broken = mock(RabbitTemplate.class);
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("refused")))
                .when(broken).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(new ReplyPublisher(broken).publish(7L, TO_REQUESTER, "text/csv", REPLY.toCsv()))
                .isEqualTo(ReplyPublisher.Result.NOT_CONFIRMED);
    }
}
