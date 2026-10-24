package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparedBaselineTest {
    private static final String A = "a".repeat(40);
    private static final String P = "b".repeat(40);

    @Test
    void originalEvidenceAndPreparedTrustedBaselineRemainDistinctAcrossCopiesAndReconstitution() {
        var provenance = new PreparationProvenance(A, P, "maven-generated-v1",
                List.of("backend/target/classes/Hello.class"), List.of(".gitignore"));
        WorkingCopy copy = WorkingCopy.create(new WorkingCopyId("copy"), new SoftwareAssetId("asset"), A, "managed");
        copy.markReady(P, provenance);
        assertEquals(A, copy.sourceRevision());
        assertEquals(P, copy.currentRevision());
        assertEquals(P, copy.lastVerifiedRevision());
        assertEquals(provenance, copy.copy().preparation());
        assertEquals(P, WorkingCopy.reconstitute(copy.id(), copy.sourceAssetId(), A, copy.location(), P, P,
                WorkingCopyStatus.READY, provenance).preparedRevision());
        assertThrows(EvolutionPlanStateException.class, () -> copy.markReady(P, provenance));
    }

    @Test
    void legacyRecordsKeepIdentityMeaningAndMismatchesCannotCreateTrust() {
        WorkingCopy legacy = WorkingCopy.reconstitute(new WorkingCopyId("legacy"), new SoftwareAssetId("asset"),
                A, "managed", A, A, WorkingCopyStatus.READY);
        assertEquals(A, legacy.preparedRevision());
        assertEquals("IDENTITY", legacy.preparation().policyVersion());
        WorkingCopy fresh = WorkingCopy.create(new WorkingCopyId("copy"), new SoftwareAssetId("asset"), A, "managed");
        assertThrows(EvolutionPlanStateException.class, () -> fresh.markReady(P));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparationProvenance(A, P, "IDENTITY", List.of(), List.of()));
        assertEquals(WorkingCopyStatus.CREATING, fresh.status());
    }
}
