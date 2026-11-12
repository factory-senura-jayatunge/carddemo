package org.carddemo.authorization;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * A private virtual host {@code carddemo_test_auth_<random>} on the compose RabbitMQ, created and
 * deleted through the management API with the admin login from {@code .env} (ruling B4). Nothing
 * outside it is touched. The login is only read from the environment, never logged.
 */
public final class TestBroker implements AutoCloseable {

    private final String amqpPort;
    private final String managementPort;
    private final String user;
    private final String password;
    private final String vhost;
    // The default client offers an h2c upgrade on plain HTTP, which the management API answers by
    // closing the stream (EOFException); it speaks HTTP/1.1 only.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private TestBroker(String amqpPort, String managementPort, String user, String password, String vhost) {
        this.amqpPort = amqpPort;
        this.managementPort = managementPort;
        this.user = user;
        this.password = password;
        this.vhost = vhost;
    }

    public static TestBroker create() {
        String password = System.getenv("RABBITMQ_DEFAULT_PASS");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("RABBITMQ_DEFAULT_PASS is not set; run the build through"
                    + " modernization/scripts/with_env.py with the compose RabbitMQ running");
        }
        String user = orDefault(System.getenv("RABBITMQ_DEFAULT_USER"), "carddemo");
        byte[] random = new byte[6];
        new SecureRandom().nextBytes(random);
        TestBroker broker = new TestBroker(orDefault(System.getenv("RABBITMQ_AMQP_HOST_PORT"), "25672"),
                orDefault(System.getenv("RABBITMQ_MGMT_HOST_PORT"), "35672"), user, password,
                "carddemo_test_auth_" + HexFormat.of().formatHex(random));
        broker.call("PUT", "/api/vhosts/" + broker.encodedVhost(), "{}");
        broker.call("PUT", "/api/permissions/" + broker.encodedVhost() + "/" + encode(user),
                "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}");
        return broker;
    }

    public String vhost() {
        return vhost;
    }

    public int amqpPort() {
        return Integer.parseInt(amqpPort);
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /** Points the service's {@code spring.rabbitmq.*} at the private virtual host. */
    public void register(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> amqpPort);
        registry.add("spring.rabbitmq.virtual-host", () -> vhost);
        registry.add("spring.rabbitmq.username", () -> user);
        registry.add("spring.rabbitmq.password", () -> password);
    }

    /** The arguments the broker holds for {@code queue} in the private virtual host (management API). */
    public Map<String, Object> queueArguments(String queue) {
        String json = call("GET", "/api/queues/" + encodedVhost() + "/" + encode(queue), null);
        Map<String, Object> arguments = new LinkedHashMap<>();
        JsonMapper.builder().build().readTree(json).get("arguments").properties()
                .forEach(e -> arguments.put(e.getKey(), e.getValue().isIntegralNumber() ? (Object) e.getValue().asInt()
                        : e.getValue().asString()));
        return arguments;
    }

    @Override    public void close() {
        call("DELETE", "/api/vhosts/" + encodedVhost(), null);
    }

    private String encodedVhost() {
        return encode(vhost);
    }

    private String call(String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (user + ":" + password).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(method + " " + path + " answered " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
