package com.ayywl.delveforge.app.api.softwareasset;

import com.ayywl.delveforge.domain.asset.UsageAuthorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/** 完整、显式授权提交；null licenseInfo 表示用户记录为未知，不是省略更新。 */
public record SoftwareAssetAuthorizationRequest(
        @JsonDeserialize(using = SoftwareAssetAuthorizationRevisionDeserializer.class) Long expectedAuthorizationRevision,
        Boolean readPermissionAllowed,
        @JsonProperty(value = "licenseInfo", required = true) String licenseInfo,
        UsageAuthorization usageAuthorization) {
}
