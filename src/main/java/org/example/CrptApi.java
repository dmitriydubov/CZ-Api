package org.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CrptApi {
    private final int requestLimit;
    private final long intervalMillis;
    private int availableTokens;
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String authToken;
    LocalDateTime tokenExpiration;
    private final String baseUrl;
    private final String privateKeyAlias;
    private final String keyStorePassword;

    public static final String DEMO_ENV = "https://markirovka.demo.crpt.tech";

    private static final String CREATE_RF_DOC_PATH = "/api/v3/lk/documents/commissioning/contract/create";
    private static final String AUTH_KEY_PATH = "/api/v3/auth/cert/key";
    private static final String AUTH_TOKEN_PATH = "/api/v3/auth/cert/";

    public CrptApi(
            TimeUnit timeUnit,
            int requestLimit,
            String environment,
            String privateKeyAlias,
            String keyStorePassword
    ) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit должен быть положительным числом");
        }
        this.requestLimit = requestLimit;
        this.availableTokens = requestLimit;
        this.intervalMillis = timeUnit.toMillis(1);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.scheduler.scheduleAtFixedRate(this::refillTokens, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = createObjectMapper();
        this.baseUrl = environment;
        this.privateKeyAlias = privateKeyAlias;
        this.keyStorePassword = keyStorePassword;
        this.authToken = null;
        this.tokenExpiration = null;
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    private void refillTokens() {
        lock.lock();
        try {
            availableTokens = requestLimit;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    public static void main(String[] args) throws Exception {
        String environment = CrptApi.DEMO_ENV;
        String keyAlias = "my-key-alias";
        String keystorePass = "password";

        CrptApi api = new CrptApi(TimeUnit.SECONDS, 5, environment, keyAlias, keystorePass);
        Random random = new Random();
        
        Supplier<String> randomString = () -> UUID.randomUUID().toString().substring(0, 10);
        Supplier<LocalDate> randomDate = () -> LocalDate.now()
                .minusDays(random.nextInt(3650))
                .plusDays(random.nextInt(3650));
        Supplier<String> randomInn = () -> String.format("%010d", random.nextInt(1000000000));
        Description description = new Description(randomInn.get());

        List<Product> products = createRandomProducts(random, randomString, randomDate, randomInn);
        Document doc = createRandomDocument(description, randomString, random, randomInn, randomDate, products);

        String productGroup = "electronics";
        api.createDocument(doc, productGroup);

        api.shutdown();
    }

    private static Document createRandomDocument(Description description, Supplier<String> randomString, Random random, Supplier<String> randomInn, Supplier<LocalDate> randomDate, List<Product> products) {
        return new Document(
            description,
            "doc_" + randomString.get(),
            "status_" + randomString.get(),
            "LP_INTRODUCE_GOODS",
            random.nextBoolean(),
            randomInn.get(),
            randomInn.get(),
            randomInn.get(),
            randomDate.get(),
            "prod_type_" + randomString.get(),
            products,
            randomDate.get(),
            "reg_num_" + randomString.get()
        );
    }

    private static List<Product> createRandomProducts(Random random, Supplier<String> randomString, Supplier<LocalDate> randomDate, Supplier<String> randomInn) {
        return IntStream.range(0, random.nextInt(3) + 1)
            .mapToObj(i -> new Product(
                "cert_doc_" + randomString.get(),
                randomDate.get(),
                "cert_num_" + randomString.get(),
                randomInn.get(),
                randomInn.get(),
                randomDate.get(),
                "tnved_" + randomString.get(),
                "uit_" + randomString.get(),
                "uitu_" + randomString.get()
            ))
            .collect(Collectors.toList());
    }

    public void createDocument(
            Document document,
            String productGroup
    ) throws InterruptedException
    {
        acquireToken();

        try {
            refreshTokenIfNeeded();
            String jsonDocument = objectMapper.writeValueAsString(document);
            String base64Document = Base64.getEncoder().encodeToString(
                    jsonDocument.getBytes(StandardCharsets.UTF_8)
            );
            String signature = generateSignature(jsonDocument);

            ApiRequest apiRequest = new ApiRequest(
                    "MANUAL",
                    base64Document,
                    "LP_INTRODUCE_GOODS",
                    signature,
                    productGroup
            );

            String requestBody = objectMapper.writeValueAsString(apiRequest);
            sendRequest(requestBody, productGroup);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization error", e);
        } catch (Exception e) {
            throw new RuntimeException("API request failed", e);
        }
    }

    private void refreshTokenIfNeeded() throws Exception {
        if (authToken == null || tokenExpiration == null || LocalDateTime.now().isAfter(tokenExpiration)) {
            authenticate();
        }
    }

    private void authenticate() throws Exception {
        HttpResponse<String> keyResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + AUTH_KEY_PATH))
                .GET()
                .header("Accept", "*/*")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        if (keyResponse.statusCode() != 200) {
            throw new RuntimeException("Failed to get auth key: " + keyResponse.body());
        }

        AuthKeyResponse authKey = objectMapper.readValue(keyResponse.body(), AuthKeyResponse.class);
        String signedData = signData(authKey.getData());
        AuthTokenRequest tokenRequest = new AuthTokenRequest(authKey.getUuid(), signedData);
        String requestBody = objectMapper.writeValueAsString(tokenRequest);

        HttpResponse<String> tokenResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + AUTH_TOKEN_PATH))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        if (tokenResponse.statusCode() != 200) {
            throw new RuntimeException("Failed to get auth token: " + tokenResponse.body());
        }

        AuthTokenResponse authTokenResponse = objectMapper.readValue(tokenResponse.body(), AuthTokenResponse.class);
        this.authToken = authTokenResponse.getToken();
        this.tokenExpiration = LocalDateTime.now().plusHours(10);
    }

    private String generateSignature(String data) {
        return Base64.getEncoder().encodeToString(("signed:" + data).getBytes());
    }

    private String signData(String data) {
        return Base64.getEncoder().encodeToString(("signed:" + data).getBytes());
    }

    private void acquireToken() throws InterruptedException {
        lock.lock();
        try {
            while (availableTokens <= 0) {
                lock.unlock();
                Thread.sleep(10);
                lock.lock();
            }
            availableTokens--;
        } finally {
            lock.unlock();
        }
    }

    private void sendRequest(String jsonBody, String productGroup) {
        try {
            String encodedProductGroup = URLEncoder.encode(productGroup, StandardCharsets.UTF_8);
            String urlWithParams = baseUrl + CREATE_RF_DOC_PATH + "?pg=" + encodedProductGroup;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlWithParams))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + authToken)
                    .header("Accept", "*/*")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            handleResponse(response);
        } catch (Exception e) {
            System.err.println("Error sending request: " + e.getMessage());
        }
    }

    private void handleResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body();

        if (status >= 200 && status < 300) {
            System.out.println("Document created successfully. Response: " + body);
        } else {
            String errorMessage;
            try {
                if (status == 401) {
                    errorMessage = "Unauthorized: " + extractErrorMessageFromXml(body);
                } else if (
                        status == 400 || status == 403 || status == 404 ||
                        status == 500 || status == 503
                ) {
                    ErrorResponse error = objectMapper.readValue(body, ErrorResponse.class);
                    errorMessage = "Error " + status + ": " + error.getErrorMessage();
                } else {
                    errorMessage = "Unexpected error " + status + ": " + body;
                }
            } catch (Exception e) {
                errorMessage = "Error parsing error response: " + body;
            }

            System.err.println("API request failed. " + errorMessage);
        }
    }

    private String extractErrorMessageFromXml(String xml) {
        if (xml.contains("<error_message>")) {
            int start = xml.indexOf("<error_message>") + "<error_message>".length();
            int end = xml.indexOf("</error_message>");
            if (end > start) {
                return xml.substring(start, end);
            }
        }
        return xml;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class ApiRequest {
        private final String document_format;
        private final String product_document;
        private final String type;
        private final String signature;
        private final String product_group;

        public ApiRequest(
            String document_format,
            String product_document,
            String type,
            String signature,
            String product_group
        ) {
            this.document_format = document_format;
            this.product_document = product_document;
            this.type = type;
            this.signature = signature;
            this.product_group = product_group;
        }

        public String getDocument_format() { return document_format; }
        public String getProduct_document() { return product_document; }
        public String getType() { return type; }
        public String getSignature() { return signature; }
        public String getProduct_group() { return product_group; }
    }

    private static class AuthKeyResponse {
        private String uuid;
        private String data;

        public String getUuid() { return uuid; }
        public String getData() { return data; }
    }

    private static class AuthTokenRequest {
        private final String uuid;
        private final String data;

        public AuthTokenRequest(String uuid, String data) {
            this.uuid = uuid;
            this.data = data;
        }

        public String getUuid() { return uuid; }
        public String getData() { return data; }
    }

    private static class AuthTokenResponse {
        private String token;

        public String getToken() { return token; }
    }

    private static class ErrorResponse {
        private String error_message;

        public String getErrorMessage() { return error_message; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class Document {
        private Description description;
        private String doc_id;
        private String doc_status;
        private String doc_type;
        private Boolean importRequest;
        private String owner_inn;
        private String participant_inn;
        private String producer_inn;
        private LocalDate production_date;
        private String production_type;
        private List<Product> products;
        private LocalDate reg_date;
        private String reg_number;

        public Document(Description description,
            String doc_id,
            String doc_status,
            String doc_type,
            Boolean importRequest,
            String owner_inn,
            String participant_inn,
            String producer_inn,
            LocalDate production_date,
            String production_type,
            List<Product> products,
            LocalDate reg_date,
            String reg_number
        ) {
            this.description = description;
            this.doc_id = doc_id;
            this.doc_status = doc_status;
            this.doc_type = doc_type;
            this.importRequest = importRequest;
            this.owner_inn = owner_inn;
            this.participant_inn = participant_inn;
            this.producer_inn = producer_inn;
            this.production_date = production_date;
            this.production_type = production_type;
            this.products = products;
            this.reg_date = reg_date;
            this.reg_number = reg_number;
        }

        public Description getDescription() { return description; }
        public void setDescription(Description description) { this.description = description; }
        public String getDoc_id() { return doc_id; }
        public void setDoc_id(String doc_id) { this.doc_id = doc_id; }
        public String getDoc_status() { return doc_status; }
        public void setDoc_status(String doc_status) { this.doc_status = doc_status; }
        public String getDoc_type() { return doc_type; }
        public void setDoc_type(String doc_type) { this.doc_type = doc_type; }
        public Boolean getImportRequest() { return importRequest; }
        public void setImportRequest(Boolean importRequest) { this.importRequest = importRequest; }
        public String getOwner_inn() { return owner_inn; }
        public void setOwner_inn(String owner_inn) { this.owner_inn = owner_inn; }
        public String getParticipant_inn() { return participant_inn; }
        public void setParticipant_inn(String participant_inn) { this.participant_inn = participant_inn; }
        public String getProducer_inn() { return producer_inn; }
        public void setProducer_inn(String producer_inn) { this.producer_inn = producer_inn; }
        public LocalDate getProduction_date() { return production_date; }
        public void setProduction_date(LocalDate production_date) { this.production_date = production_date; }
        public String getProduction_type() { return production_type; }
        public void setProduction_type(String production_type) { this.production_type = production_type; }
        public List<Product> getProducts() { return products; }
        public void setProducts(List<Product> products) { this.products = products; }
        public LocalDate getReg_date() { return reg_date; }
        public void setReg_date(LocalDate reg_date) { this.reg_date = reg_date; }
        public String getReg_number() { return reg_number; }
        public void setReg_number(String reg_number) { this.reg_number = reg_number; }
    }

    static class Description {
        private String participantInn;

        public Description(String participantInn) {
            this.participantInn = participantInn;
        }

        public String getParticipantInn() { return participantInn; }
        public void setParticipantInn(String participantInn) { this.participantInn = participantInn; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class Product {
        private String certificate_document;
        private LocalDate certificate_document_date;
        private String certificate_document_number;
        private String owner_inn;
        private String producer_inn;
        private LocalDate production_date;
        private String tnved_code;
        private String uit_code;
        private String uitu_code;

        public Product(String certificate_document,
               LocalDate certificate_document_date,
               String certificate_document_number,
               String owner_inn,
               String producer_inn,
               LocalDate production_date,
               String tnved_code,
               String uit_code,
               String uitu_code
        ) {
            this.certificate_document = certificate_document;
            this.certificate_document_date = certificate_document_date;
            this.certificate_document_number = certificate_document_number;
            this.owner_inn = owner_inn;
            this.producer_inn = producer_inn;
            this.production_date = production_date;
            this.tnved_code = tnved_code;
            this.uit_code = uit_code;
            this.uitu_code = uitu_code;
        }

        public String getCertificate_document() { return certificate_document; }
        public void setCertificate_document(String certificate_document) { this.certificate_document = certificate_document; }
        public LocalDate getCertificate_document_date() { return certificate_document_date; }
        public void setCertificate_document_date(LocalDate certificate_document_date) { this.certificate_document_date = certificate_document_date; }
        public String getCertificate_document_number() { return certificate_document_number; }
        public void setCertificate_document_number(String certificate_document_number) { this.certificate_document_number = certificate_document_number; }
        public String getOwner_inn() { return owner_inn; }
        public void setOwner_inn(String owner_inn) { this.owner_inn = owner_inn; }
        public String getProducer_inn() { return producer_inn; }
        public void setProducer_inn(String producer_inn) { this.producer_inn = producer_inn; }
        public LocalDate getProduction_date() { return production_date; }
        public void setProduction_date(LocalDate production_date) { this.production_date = production_date; }
        public String getTnved_code() { return tnved_code; }
        public void setTnved_code(String tnved_code) { this.tnved_code = tnved_code; }
        public String getUit_code() { return uit_code; }
        public void setUit_code(String uit_code) { this.uit_code = uit_code; }
        public String getUitu_code() { return uitu_code; }
        public void setUitu_code(String uitu_code) { this.uitu_code = uitu_code; }
    }
}
