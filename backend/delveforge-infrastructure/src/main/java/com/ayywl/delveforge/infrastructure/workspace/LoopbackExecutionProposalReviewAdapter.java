package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.evolution.preparation.ExecutionProposalReviewException;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionProposalReviewPort;
import com.ayywl.delveforge.application.evolution.preparation.ValidatedExecutionProposal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * 仅向显式配置的 IPv4 loopback 技术审查端点提交调用内提案，不操作源码或领域状态。
 * 默认不启用；禁止代理、重定向和非 loopback 目标，拒绝与不可用均失败关闭。
 */
public final class LoopbackExecutionProposalReviewAdapter implements ExecutionProposalReviewPort {
    private final URI endpoint;
    private final ObjectMapper mapper;

    public LoopbackExecutionProposalReviewAdapter(URI endpoint, ObjectMapper mapper) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null || endpoint.getQuery() != null) {
            throw new IllegalArgumentException("Execution proposal review requires an explicit loopback HTTP endpoint");
        }
        this.endpoint = endpoint;
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void review(ValidatedExecutionProposal validated) {
        HttpURLConnection connection = null;
        try {
            String digest = sha256(validated.proposal().unifiedDiff());
            byte[] request = mapper.writeValueAsBytes(Map.of(
                    "planId", validated.basis().planId().value(),
                    "stepId", validated.basis().stepId().value(),
                    "workingCopyId", validated.basis().workingCopyId().value(),
                    "expectedRevision", validated.basis().expectedRevision(),
                    "patchSha256", digest, "proposal", validated.proposal()));
            if (request.length > 1048576) {
                throw new ExecutionProposalReviewException(ExecutionProposalReviewException.Reason.UNAVAILABLE);
            }
            connection = (HttpURLConnection) endpoint.toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(300000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(request.length);
            try (var output = connection.getOutputStream()) {
                output.write(request);
            }
            if (connection.getResponseCode() != 200) {
                throw new ExecutionProposalReviewException(ExecutionProposalReviewException.Reason.UNAVAILABLE);
            }
            byte[] response;
            try (var input = connection.getInputStream()) {
                response = input.readNBytes(4097);
            }
            if (response.length > 4096) {
                throw new ExecutionProposalReviewException(ExecutionProposalReviewException.Reason.UNAVAILABLE);
            }
            JsonNode result = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response);
            if (result == null || !result.isObject() || !result.path("approved").isBoolean()) {
                throw new ExecutionProposalReviewException(ExecutionProposalReviewException.Reason.UNAVAILABLE);
            }
            if (!result.path("approved").booleanValue()) {
                throw new ExecutionProposalReviewException(ExecutionProposalReviewException.Reason.REJECTED);
            }
            if (!digest.equals(result.path("patchSha256").asText())) {
                throw new ExecutionProposalReviewException(ExecutionProposalReviewException.Reason.UNAVAILABLE);
            }
        } catch (IOException failure) {
            throw new ExecutionProposalReviewException(ExecutionProposalReviewException.Reason.UNAVAILABLE, failure);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String sha256(String patch) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(patch.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 algorithm unavailable", failure);
        }
    }
}
