package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.app.error.ApiErrorCode;
import com.ayywl.delveforge.app.error.ApiExceptionHandler;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionProposalReviewException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExecutionProposalReviewErrorMappingTest {
    @Test
    void hostReviewFailuresRemainDistinctFromProviderAndVerificationFailures() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        var request = new MockHttpServletRequest("POST", "/api/evolution-plans/plan/steps/step/execute");
        for (var reason : ExecutionProposalReviewException.Reason.values()) {
            var response = handler.handleExecutionProposalReviewFailure(new ExecutionProposalReviewException(reason), request);
            assertNotNull(response.getBody());
            assertEquals(reason == ExecutionProposalReviewException.Reason.REJECTED ? 409 : 503,
                    response.getStatusCode().value());
            assertEquals(reason == ExecutionProposalReviewException.Reason.REJECTED
                    ? ApiErrorCode.EXECUTION_PROPOSAL_REVIEW_REJECTED : ApiErrorCode.EXECUTION_PROPOSAL_REVIEW_UNAVAILABLE,
                    response.getBody().code());
            assertEquals(request.getRequestURI(), response.getBody().path());
        }
    }
}
