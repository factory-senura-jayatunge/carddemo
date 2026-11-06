package org.carddemo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MessageCatalogTest {

    @Test
    void textsRenderTheirPlaceholdersInOrder() {
        assertThat(MessageCatalog.PAYMENT_SUCCESSFUL.params()).containsExactly("tranId");
        assertThat(MessageCatalog.PAYMENT_SUCCESSFUL.render("0000000000000301"))
                .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000301.");
        assertThat(MessageCatalog.OPTION_COMING_SOON.render("Credit Card Update"))
                .isEqualTo("This option Credit Card Update is coming soon ...");
        assertThat(MessageCatalog.ACCT_ID_REQUIRED.render()).isEqualTo("Acct ID can NOT be empty...");
        assertThatThrownBy(() -> MessageCatalog.PAYMENT_SUCCESSFUL.render())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MessageCatalog.OPTION_COMING_SOON.render("$1 \\ odd")).isEqualTo("This option $1 \\ odd is coming soon ...");
    }

    @Test
    void variantsShareTheirApiCode() {
        assertThat(MessageCatalog.NEW_PASSWORD_LENGTH_LEGACY.code()).isEqualTo("NEW_PASSWORD_LENGTH");
        assertThat(MessageCatalog.NEW_PASSWORD_LENGTH_FIXED.code()).isEqualTo("NEW_PASSWORD_LENGTH");
        assertThat(MessageCatalog.INVALID_INPUT_USER_ID.code()).isEqualTo("INVALID_INPUT");
        assertThat(MessageCatalog.get("BATCH_WINDOW")).isSameAs(MessageCatalog.BATCH_WINDOW);
    }

    @Test
    void everyConstantIsInTheIndex() throws IllegalAccessException {
        int constants = 0;
        for (var field : MessageCatalog.class.getFields()) {
            if (field.getType() == Message.class) {
                constants++;
                Message m = (Message) field.get(null);
                assertThat(m.key()).isEqualTo(field.getName());
                assertThat(MessageCatalog.all()).containsEntry(m.key(), m);
            }
        }
        assertThat(MessageCatalog.all()).hasSize(constants);
    }

    /** The web client's generated message module is built from this file. */
    @Test
    void exportsTheCatalogueAsJson() throws IOException {
        String json = MessageCatalog.toJson();
        JsonNode tree = JsonMapper.builder().build().readTree(json);
        assertThat(tree.size()).isEqualTo(MessageCatalog.all().size());
        for (Map.Entry<String, Message> e : MessageCatalog.all().entrySet()) {
            JsonNode node = tree.get(e.getKey());
            assertThat(node.get("code").asString()).isEqualTo(e.getValue().code());
            assertThat(node.get("text").asString()).isEqualTo(e.getValue().text());
            assertThat(node.get("severity").asString()).isEqualTo(e.getValue().severity().key());
        }
        Path out = Path.of("target", "messages.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }
}
