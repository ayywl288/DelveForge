package com.ayywl.delveforge.application.repositoryanalysis.scout;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File Catalog 的**序列化**：一组源码描述符 → 一次 File Scout 调用会发出的那段载荷。
 *
 * <pre>
 * analyzedRevision           本次分析固定的 commit id
 * fileCatalog                [{ reference, path, sizeBytes, language, materialKind, roleHints }]
 * </pre>
 *
 * <p>只发描述符，**不发文件内容**——这是「让模型看一眼整棵树」之所以有界的前提
 * （ADR-0004）。
 *
 * <h2>为什么它是一个独立类型</h2>
 *
 * <p>「这个目录装不装得下」这个判断，必须与 File Scout **实际发出的那一份载荷**是同一种度量，
 * 否则算出来的字节数说明不了任何事。分层导航（ADR-0005）的递归停止条件正是这个判断，
 * 而它发生在 File Scout 之外；如果各写一份序列化，两者迟早会漂移。
 *
 * <p>因此渲染收在这里一处：{@link RepositoryScoutExtraction} 发请求时用它，
 * 分层导航度量候选分支时也用它。
 *
 * <p>它不做任何判断：不判定上限、不截断、不采样。载荷多大是调用方的事。
 */
public final class FileCatalogPayload {

    private final ObjectMapper objectMapper;

    public FileCatalogPayload(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("FileCatalogPayload 必须指定 objectMapper");
        }
        this.objectMapper = objectMapper;
    }

    /**
     * 渲染一组源码描述符的目录载荷。
     *
     * <p>描述符按传入顺序出现，编号取自描述符自身——调用方负责给出本次调用应有的编号
     * （{@code RF-1}…{@code RF-n}），本类不重新编号。
     *
     * @param analyzedRevision 本次分析固定的 commit id，不得为空白
     * @param entries          源码描述符，不得为 {@code null} 或含 {@code null}
     * @return 即将发出的那段载荷
     * @throws IllegalArgumentException 参数不满足上述约束
     * @throws AiGatewayException       序列化失败
     */
    public String render(String analyzedRevision, List<RepositoryMapEntry> entries) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException(
                    "File Catalog 载荷必须指定 analyzedRevision");
        }
        if (entries == null) {
            throw new IllegalArgumentException("File Catalog 载荷的 entries 不能为 null");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analyzedRevision", analyzedRevision);

        List<Map<String, Object>> catalog = new ArrayList<>(entries.size());
        for (RepositoryMapEntry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException(
                        "File Catalog 载荷的 entries 不能包含 null");
            }
            Map<String, Object> described = new LinkedHashMap<>();
            described.put("reference", entry.reference().value());
            described.put("path", entry.relativePath());
            described.put("sizeBytes", entry.sizeInBytes());
            described.put("language", entry.language().name());
            described.put("materialKind", entry.materialKind().name());
            described.put("roleHints", entry.roleHints().stream()
                    .map(Enum::name)
                    .toList());
            catalog.add(described);
        }
        payload.put("fileCatalog", catalog);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException("无法构造 Scout 请求内容", exception);
        }
    }

    /**
     * 这组描述符的目录载荷大小，按 UTF-8 字节计。
     *
     * @param analyzedRevision 本次分析固定的 commit id，不得为空白
     * @param entries          源码描述符，不得为 {@code null} 或含 {@code null}
     * @return UTF-8 字节数
     */
    public int payloadBytes(String analyzedRevision, List<RepositoryMapEntry> entries) {
        return render(analyzedRevision, entries).getBytes(StandardCharsets.UTF_8).length;
    }
}
