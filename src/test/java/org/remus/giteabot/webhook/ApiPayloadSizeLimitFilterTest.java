package org.remus.giteabot.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiPayloadSizeLimitFilterTest {

    @Test
    void doFilter_rejectsDeclaredPayloadAboveTheLimit() throws Exception {
        ApiPayloadSizeLimitFilter filter = new ApiPayloadSizeLimitFilter(10);
        MockHttpServletRequest request = apiRequest("01234567890");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertEquals(413, response.getStatus());
        assertFalse(chainCalled.get());
    }

    @Test
    void doFilter_allowsDeclaredPayloadAtTheLimit() throws Exception {
        ApiPayloadSizeLimitFilter filter = new ApiPayloadSizeLimitFilter(10);
        MockHttpServletRequest request = apiRequest("0123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertTrue(chainCalled.get());
    }

    @Test
    void doFilter_rejectsPayloadUnderServletContextPath() throws Exception {
        ApiPayloadSizeLimitFilter filter = new ApiPayloadSizeLimitFilter(10);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/bot");
        request.setRequestURI("/bot/api/webhook/test-secret");
        request.setContent("01234567890".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("The filter must reject the oversized API request");
        });

        assertEquals(413, response.getStatus());
    }

    @Test
    void doFilter_stopsChunkedPayloadAboveTheLimit() throws Exception {
        ApiPayloadSizeLimitFilter filter = new ApiPayloadSizeLimitFilter(10);
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setRequestURI("/api/webhook/test-secret");
        request.setContent("01234567890".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrappedRequest, ignoredResponse) -> assertThrows(
                IOException.class, () -> wrappedRequest.getInputStream().readAllBytes()));
    }

    private static MockHttpServletRequest apiRequest(String content) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/webhook/test-secret");
        request.setContent(content.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
