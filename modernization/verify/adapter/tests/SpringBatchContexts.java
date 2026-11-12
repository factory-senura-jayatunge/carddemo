import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.batch.core.repository.dao.DefaultExecutionContextSerializer;

/**
 * Prints the fixture spring_batch_contexts.json: execution contexts as Spring Batch's
 * DefaultExecutionContextSerializer stores them, with what Java holds for each key.
 *
 * "expect" maps each key to [class, value]: value is String.valueOf(v), the epoch
 * milliseconds for a Date (so the output does not depend on the time zone), or for a
 * string longer than 200 characters "sha256:" and the SHA-256 of its UTF-8 bytes.
 *
 * Regenerate (JDK 21; jars from the batch-service build's local Maven repository):
 *   java -cp spring-batch-core-6.0.6.jar:spring-batch-infrastructure-6.0.6.jar:spring-core-7.0.10.jar \
 *       SpringBatchContexts.java | gzip -n -9 > spring_batch_contexts.json.gz
 */
public class SpringBatchContexts {
    public static void main(String[] args) throws Exception {
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        Map<String, Object> mixed = new HashMap<>();
        mixed.put("accountsMigrated", 44L);
        mixed.put("accountLayoutWarnings", 3);
        mixed.put("acctdataSha256", "c".repeat(64));
        mixed.put("accountLayoutManifestSha256", "");
        mixed.put("creditReleased", "-12.50");
        mixed.put("flag", Boolean.TRUE);
        mixed.put("off", Boolean.FALSE);
        mixed.put("negative", -7L);
        mixed.put("maxLong", Long.MAX_VALUE);
        mixed.put("minInt", Integer.MIN_VALUE);
        mixed.put("batch.taskletType", "org.springframework.batch.core.step.item.ChunkOrientedTasklet");
        mixed.put("text", "Zo\u00eb \u20ac \ud83d\ude00");
        mixed.put("nul", "a\u0000b");
        cases.put("mixed", mixed);
        cases.put("empty", new HashMap<>());
        Map<String, Object> linked = new LinkedHashMap<>();
        linked.put("b", 2L);
        linked.put("a", "one");
        cases.put("linked", linked);
        Map<String, Object> refs = new HashMap<>();
        String same = new String("shared-value");
        Long seven = Long.valueOf(7000L);
        refs.put("a", same);
        refs.put("b", same);
        refs.put("c", seven);
        refs.put("d", seven);
        cases.put("refs", refs);
        Map<String, Object> opaque = new HashMap<>();
        opaque.put("amount", new BigDecimal("1.50"));
        opaque.put("when", new Date(0L));
        opaque.put("ratio", 0.5d);
        opaque.put("nested", new HashMap<>(Map.of("k", 1L)));
        opaque.put("count", 5L);
        cases.put("opaque", opaque);
        Map<String, Object> big = new HashMap<>();
        big.put("long", "x".repeat(70000));
        big.put("n", 1L);
        cases.put("longstring", big);

        DefaultExecutionContextSerializer serializer = new DefaultExecutionContextSerializer();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        StringBuilder json = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Map<String, Object>> e : cases.entrySet()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            serializer.serialize(e.getValue(), out);
            json.append("  ").append(quote(e.getKey())).append(": {\"stored\": ")
                .append(quote(out.toString(StandardCharsets.ISO_8859_1))).append(", \"expect\": {");
            int j = 0;
            for (Map.Entry<String, Object> v : e.getValue().entrySet()) {
                String text = v.getValue() instanceof Date date ? String.valueOf(date.getTime())
                        : String.valueOf(v.getValue());
                if (v.getValue() instanceof String s && s.length() > 200) {
                    text = "sha256:" + HexFormat.of().formatHex(sha.digest(s.getBytes(StandardCharsets.UTF_8)));
                }
                json.append(j++ > 0 ? ", " : "").append(quote(v.getKey())).append(": [")
                    .append(quote(v.getValue().getClass().getName())).append(", ").append(quote(text)).append(']');
            }
            json.append("}}").append(++i < cases.size() ? ",\n" : "\n");
        }
        System.out.print(json.append("}\n"));
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
