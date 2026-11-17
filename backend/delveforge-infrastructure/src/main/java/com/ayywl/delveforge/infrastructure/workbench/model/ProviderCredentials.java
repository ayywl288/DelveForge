package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Frozen;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;

/** 宿主密钥和控制数据库分离。每次替换获得新引用，旧 Run 无法借用新密钥。 */
final class ProviderCredentials {
    record Reference(String preset, String workspace, String id, boolean saved) {}

    private final JdbcTemplate jdbc;
    private final Function<String, String> environment;
    private final SecureRandom random = new SecureRandom();

    ProviderCredentials(JdbcTemplate jdbc, Function<String, String> environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    Reference reference(ProviderFamily family) {
        var saved = jdbc.query("SELECT preset,workspace,credential_ref FROM workbench_provider WHERE provider=?",
                (rs, n) -> new Reference(rs.getString(1), rs.getString(2), rs.getString(3), true), family.id());
        return saved.isEmpty() ? environmentReference(family) : saved.getFirst();
    }

    Reference environmentReference(ProviderFamily family) {
        String key = environment.apply(family.environment);
        return environmentReference(family, key);
    }

    private Reference environmentReference(ProviderFamily family, String key) {
        String preset = family.defaultPreset();
        if (key == null || key.isBlank()) {
            return new Reference(preset, "", "", false);
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String fingerprint = HexFormat.of().formatHex(digest.digest((family.id() + ":" + key)
                    .getBytes(StandardCharsets.UTF_8)));
            return new Reference(preset, "", "env-" + fingerprint, false);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String resolve(Frozen frozen) {
        var family = ProviderFamily.named(frozen.choice().provider());
        var current = reference(family);
        if (current.id().isEmpty() || !current.id().equals(frozen.credentialRef())
                || !current.preset().equals(frozen.preset()) || !current.workspace().equals(frozen.workspace())) {
            throw new AgentException("MODEL_CREDENTIAL_CHANGED", "任务绑定的凭据已移除或更换");
        }
        if (!current.saved()) {
            String key = environment.apply(family.environment);
            if (!environmentReference(family, key).id().equals(frozen.credentialRef())) {
                throw new AgentException("MODEL_CREDENTIAL_CHANGED", "任务绑定的凭据已更换");
            }
            return key;
        }
        var values = jdbc.query("SELECT ciphertext FROM workbench_provider WHERE provider=? AND credential_ref=?",
                (rs, n) -> rs.getBytes(1), family.id(), current.id());
        if (values.isEmpty()) {
            throw new AgentException("MODEL_CREDENTIAL_CHANGED", "任务绑定的凭据已更换");
        }
        byte[] plaintext = protect(values.getFirst(), family, current, false);
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    void save(ProviderFamily family, String preset, String workspace, String key) {
        family.endpoint(preset, workspace);
        if (key == null || !key.matches("[!-~]{1,4096}")) {
            throw ProviderFamily.invalid("请输入完整的 API Key");
        }
        var ref = new Reference(preset, workspace, UUID.randomUUID().toString(), true);
        byte[] encrypted = protect(key.getBytes(StandardCharsets.UTF_8), family, ref, true);
        jdbc.update("""
                INSERT INTO workbench_provider(provider,preset,workspace,credential_ref,ciphertext) VALUES(?,?,?,?,?)
                ON CONFLICT(provider) DO UPDATE SET preset=excluded.preset,workspace=excluded.workspace,
                    credential_ref=excluded.credential_ref,ciphertext=excluded.ciphertext
                """, family.id(), preset, workspace, ref.id(), encrypted);
    }

    void delete(ProviderFamily family) {
        jdbc.update("DELETE FROM workbench_provider WHERE provider=?", family.id());
    }

    private byte[] protect(byte[] value, ProviderFamily family, Reference ref, boolean encrypt) {
        byte[] scope = String.join("|", family.id(), ref.preset(), ref.workspace(), ref.id())
                .getBytes(StandardCharsets.UTF_8);
        try {
            if (Platform.isWindows()) {
                return encrypt ? Crypt32Util.cryptProtectData(value, scope, 1, "DelveForge", null)
                        : Crypt32Util.cryptUnprotectData(value, scope, 1, null);
            }
            String master = environment.apply("DELVEFORGE_CREDENTIAL_MASTER_KEY");
            if (master == null || master.isBlank()) {
                throw ProviderFamily.invalid("请配置宿主密钥 DELVEFORGE_CREDENTIAL_MASTER_KEY，或使用提供方环境变量");
            }
            byte[] key = Base64.getDecoder().decode(master);
            if (key.length != 32) {
                throw ProviderFamily.invalid("宿主加密密钥须为 32 字节的 Base64 编码");
            }
            try {
                byte[] iv = encrypt ? new byte[12] : Arrays.copyOf(value, 12);
                if (encrypt) {
                    random.nextBytes(iv);
                }
                var cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
                cipher.updateAAD(scope);
                if (!encrypt) {
                    return cipher.doFinal(value, 12, value.length - 12);
                }
                byte[] sealed = cipher.doFinal(value);
                return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException | LinkageError
                | com.sun.jna.platform.win32.Win32Exception unavailable) {
            throw ProviderFamily.invalid("宿主凭据存储不可用，请检查当前系统账户或使用提供方环境变量");
        } finally {
            if (encrypt) {
                Arrays.fill(value, (byte) 0);
            }
        }
    }
}
