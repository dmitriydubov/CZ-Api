package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CrptApiTest {
    private MockWebServer mockWebServer;
    private CrptApi crptApi;
    private ObjectMapper objectMapper;

    private final CrptApi.Document testDocument = createTestDocument();
    private final String testProductGroup = "electronics";

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        String baseUrl = mockWebServer.url("").toString().replaceAll("/$", "");
        crptApi = new CrptApi(
                TimeUnit.MILLISECONDS,
                1,
                baseUrl,
                "test-key",
                "test-password"
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
        crptApi.shutdown();
    }

    @Test
    void testSuccessfulDocumentCreation() throws Exception {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("{\"uuid\":\"auth-uuid-1\",\"data\":\"data-to-sign\"}"));

        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("{\"token\":\"test-token\"}"));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"value\":\"document-id-123\"}"));

        crptApi.createDocument(testDocument, testProductGroup);

        RecordedRequest authKeyRequest = mockWebServer.takeRequest();
        assertEquals("/api/v3/auth/cert/key", authKeyRequest.getPath());

        RecordedRequest authTokenRequest = mockWebServer.takeRequest();
        assertEquals("/api/v3/auth/cert/", authTokenRequest.getPath());

        RecordedRequest createRequest = mockWebServer.takeRequest();
        assertEquals(
                "/api/v3/lk/documents/commissioning/contract/create?pg=electronics",
                createRequest.getPath()
        );
        assertEquals("Bearer test-token", createRequest.getHeader("Authorization"));
    }

    @Test
    void testAuthenticationFailure() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        assertThrows(RuntimeException.class, () -> crptApi.createDocument(testDocument, testProductGroup));
    }

    @Test
    void testRateLimiting() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"uuid\":\"auth-uuid-1\",\"data\":\"data-to-sign\"}"));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"token\":\"test-token\"}"));

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        long startTime = System.currentTimeMillis();
        crptApi.createDocument(testDocument, testProductGroup);
        crptApi.createDocument(testDocument, testProductGroup);
        long duration = System.currentTimeMillis() - startTime;

        assertTrue(duration >= 10, "Requests not throttled");
        assertEquals(4, mockWebServer.getRequestCount());
    }

    @Test
    void testTokenRefresh() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"uuid\":\"auth-uuid-1\",\"data\":\"data-to-sign\"}"));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"token\":\"first-token\"}"));

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        crptApi.createDocument(testDocument, testProductGroup);

        crptApi.tokenExpiration = LocalDateTime.now().minusHours(11);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"uuid\":\"auth-uuid-2\",\"data\":\"data-to-sign\"}"));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"token\":\"second-token\"}"));

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        crptApi.createDocument(testDocument, testProductGroup);

        mockWebServer.takeRequest();
        mockWebServer.takeRequest();
        mockWebServer.takeRequest();
        mockWebServer.takeRequest();
        mockWebServer.takeRequest();
        RecordedRequest lastRequest = mockWebServer.takeRequest();
        assertEquals("Bearer second-token", lastRequest.getHeader("Authorization"));
    }

    private CrptApi.Document createTestDocument() {
        CrptApi.Description description = new CrptApi.Description("1234567890");
        List<CrptApi.Product> products = Collections.singletonList(
                new CrptApi.Product(
                        "cert_doc_123", LocalDate.now(), "cert_num_456",
                        "owner_inn_1", "producer_inn_2", LocalDate.now(),
                        "tnved_code_789", "uit_code_abc", "uitu_code_def"
                )
        );
        return new CrptApi.Document(
                description,
                "doc_123",
                "DRAFT",
                "LP_INTRODUCE_GOODS",
                false,
                "owner_inn_3",
                "participant_inn_4",
                "producer_inn_5",
                LocalDate.now(),
                "OWN_PRODUCTION",
                products,
                LocalDate.now(),
                "reg_num_001"
        );
    }
}