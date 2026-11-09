package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 离线计数，专属当前 DeepSeek V4.1 Flash 权重与非思考模板。 */
public final class DeepSeekTokenCounterAdapter implements AgentTokenCounter, AutoCloseable {
    private static final String RESOURCE = "/deepseek-v4.1-flash-1017/";
    private static final String SHA256 = "c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b";
    private final HuggingFaceTokenizer tokenizer;
    private final String instructions;

    public DeepSeekTokenCounterAdapter(String model) {
        if (!Set.of("deepseek-flash", "DeepSeek-V4.1-Flash-1017", "DeepSeek-V4.1-Flash").contains(model)) {
            throw new IllegalArgumentException("No matching Agent tokenizer for configured model: " + model);
        }
        try {
            byte[] bytes = resource("tokenizer.json");
            if (!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
                throw new IllegalStateException("DeepSeek tokenizer checksum mismatch");
            }
            instructions = new String(resource("tool-instructions.txt"), StandardCharsets.UTF_8);
            var json = new ObjectMapper();
            var plain = (ObjectNode) json.readTree(bytes);
            // recipe 使用空 added vocabulary 编码普通文本，仅渲染器标记的控制符按单 token 计。
            plain.putArray("added_tokens");
            tokenizer = openBundledTokenizer(json.writeValueAsBytes(plain));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot initialize bundled DeepSeek tokenizer", failure);
        }
    }

    private static synchronized HuggingFaceTokenizer openBundledTokenizer(byte[] model) throws IOException {
        // DJL 默认可在有 GPU 的 Linux 上下载 CUDA 库。初始化期间固定选择 JAR 自带的 CPU 库。
        String environmentFlavor = System.getenv("RUST_FLAVOR");
        if (environmentFlavor != null && !"cpu".equals(environmentFlavor)) {
            throw new IllegalStateException("Set RUST_FLAVOR=cpu or unset it to use the bundled offline tokenizer");
        }
        String previous = System.setProperty("RUST_FLAVOR", "cpu");
        try {
            return HuggingFaceTokenizer.newInstance(new ByteArrayInputStream(model),
                    Map.of("truncation", "false", "padding", "false", "addSpecialTokens", "false"));
        } finally {
            if (previous == null) {
                System.clearProperty("RUST_FLAVOR");
            } else {
                System.setProperty("RUST_FLAVOR", previous);
            }
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (var stream = DeepSeekTokenCounterAdapter.class.getResourceAsStream(RESOURCE + name)) {
            if (stream == null) {
                throw new IOException("Missing tokenizer resource: " + name);
            }
            return stream.readAllBytes();
        }
    }

    @Override
    public synchronized long countPromptTokens(List<AgentProtocol.Message> messages, List<AgentProtocol.ToolSpec> tools) {
        var prompt = new DeepSeekPromptRenderer().render(messages, tools, instructions);
        long count = prompt.controls();
        for (String region : prompt.regions()) {
            count += tokenizer.encode(region, false, false).getIds().length;
        }
        return count;
    }

    @Override
    public synchronized void close() {
        tokenizer.close();
    }
}
