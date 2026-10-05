package com.ayywl.delveforge.app.api.evolutionplan;
import com.ayywl.delveforge.application.evolution.provisioning.GetWorkingCopyUseCase;
import com.ayywl.delveforge.domain.evolution.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/working-copies")
public class WorkingCopyController {
    private final GetWorkingCopyUseCase get;
    public WorkingCopyController(GetWorkingCopyUseCase get) { this.get = get; }
    @GetMapping("/{id}")
    public WorkingCopyResponse get(@PathVariable String id) {
        var copy = get.get(new WorkingCopyId(id));
        return new WorkingCopyResponse(copy.id().value(), copy.sourceAssetId().value(), copy.sourceRevision(),
                copy.location(), copy.currentRevision(), copy.lastVerifiedRevision(), copy.status());
    }
    public record WorkingCopyResponse(String id, String sourceAssetId, String sourceRevision, String location,
            String currentRevision, String lastVerifiedRevision, WorkingCopyStatus status) {}
}
