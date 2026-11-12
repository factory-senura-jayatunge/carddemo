package org.carddemo.transaction;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The two Airflow REST calls the report trigger makes, on a loopback port: the sign-on and the
 * creation of a DAG run. Answers to the run creation are queued per test; with none queued it
 * answers {@code 200}. A delayed answer makes the client time out.
 */
final class AirflowStub implements AutoCloseable {

    static final String TOKEN = "stub-access-token";
    static final String RUNS_PATH = "/api/v2/dags/carddemo_transaction_report/dagRuns";

    record Call(String method, String path, String authorization, String body) {
    }

    record Answer(int status, Duration delay) {
        static Answer of(int status) {
            return new Answer(status, Duration.ZERO);
        }

        static Answer slow(Duration delay) {
            return new Answer(200, delay);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Queue<Answer> runAnswers = new ConcurrentLinkedQueue<>();
    private volatile int signOnStatus = 201;
    private volatile Consumer<Call> beforeRunAnswer = call -> { };

    private AirflowStub(HttpServer server) {
        this.server = server;
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    static AirflowStub start() {
        try {
            return new AirflowStub(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Clears the calls, the queued answers and the hooks. */
    AirflowStub reset() {
        calls.clear();
        runAnswers.clear();
        signOnStatus = 201;
        beforeRunAnswer = call -> { };
        return this;
    }

    AirflowStub answerRuns(Answer... answers) {
        runAnswers.addAll(List.of(answers));
        return this;
    }

    AirflowStub signOnAnswers(int status) {
        signOnStatus = status;
        return this;
    }

    /** Runs before each answer to a run creation, as the job might while Airflow answers. */
    AirflowStub beforeRunAnswer(Consumer<Call> hook) {
        beforeRunAnswer = hook;
        return this;
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    List<Call> runCalls() {
        return calls.stream().filter(c -> c.path().equals(RUNS_PATH)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Call call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), body);
        calls.add(call);
        try {
            if (call.path().equals("/auth/token")) {
                respond(exchange, signOnStatus, signOnStatus == 201 ? "{\"access_token\":\"" + TOKEN + "\"}" : "{}");
            } else if (call.path().equals(RUNS_PATH)) {
                Answer answer = runAnswers.poll();
                if (answer == null) {
                    answer = Answer.of(200);
                }
                if (!answer.delay().isZero()) {
                    Thread.sleep(answer.delay().toMillis());
                }
                beforeRunAnswer.accept(call);
                respond(exchange, answer.status(), "{}");
            } else {
                respond(exchange, 404, "{}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client gave up waiting; the answer has nowhere to go.
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
