package com.ayywl.delveforge.application.evolution.preparation;

public sealed interface CodingResponse {
    record Change(CodingProposal proposal) implements CodingResponse {}
    record NeedMore(String expectedRevision, String missingDependency, String pathPrefix) implements CodingResponse {}
}
