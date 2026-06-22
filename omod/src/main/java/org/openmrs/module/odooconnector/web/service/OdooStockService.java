package org.openmrs.module.odooconnector.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.odooconnector.web.model.StockInfo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Fetches the exact available stock quantity for a drug from Odoo's
 * /api/bdsec/available-quantity/{drugUuid} endpoint. The OpenMRS drug UUID is sent directly —
 * Odoo's product master data is mapped 1:1 to it via product_uuid.
 *
 * Odoo connection settings are read from the same Global Properties used by the rest of the
 * odooconnector module:
 *   odooconnector.odooUrl, odooconnector.odooDb, odooconnector.odooLogin, odooconnector.odooPassword
 */
@Service
public class OdooStockService {

    private static final Log log = LogFactory.getLog(OdooStockService.class);

    private static final String DEFAULT_UNIT = "Units";

    private String getGlobalProperty(String key, String defaultValue) {
        String value = Context.getAdministrationService().getGlobalProperty(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    private String getOdooBaseUrl() {
        return getGlobalProperty("odooconnector.odooUrl", "https://bdsec.grace-erp-consultancy.com");
    }

    private String getOdooDb() {
        return getGlobalProperty("odooconnector.odooDb", "odoo");
    }

    private String getOdooLogin() {
        return getGlobalProperty("odooconnector.odooLogin", "emrsync");
    }

    private String getOdooPassword() {
        return getGlobalProperty("odooconnector.odooPassword", "Admin123");
    }

    public StockInfo getAvailableQuantity(String drugUuid) {
        if (drugUuid == null || drugUuid.isEmpty()) {
            return StockInfo.notFound();
        }

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();

            String sessionId = authenticate(client);

            String url = getOdooBaseUrl() + "/api/bdsec/available-quantity/" + drugUuid;
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Cookie", "session_id=" + sessionId)
                    .build();

            log.info("[DrugAvailability] Fetching available quantity — drugUuid=" + drugUuid + " url=" + url);

            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                log.info("[DrugAvailability] Odoo response — HTTP " + response.code() + " body: " + body);

                if (!response.isSuccessful()) {
                    return StockInfo.notFound();
                }
                return parseAvailableQuantityResponse(body, drugUuid);
            }
        }
        catch (IOException e) {
            log.warn("[DrugAvailability] Failed to fetch available quantity for drugUuid=" + drugUuid
                    + " — " + e.getMessage());
            return StockInfo.notFound();
        }
    }

    private StockInfo parseAvailableQuantityResponse(String json, String drugUuid) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            JsonNode products = root.path("products");
            if (!"success".equals(root.path("status").asText())
                    || !products.isArray() || products.size() == 0) {
                return StockInfo.notFound();
            }

            JsonNode product = products.get(0);
            double quantity = product.path("available_quantity").asDouble(0);

            StockInfo info = new StockInfo();
            info.setDrugUuid(product.path("product_uuid").asText(drugUuid));
            info.setDrugName(product.path("product_name").asText(null));
            info.setQuantityAvailable(quantity);
            info.setAvailable(quantity > 0);
            info.setUnit(DEFAULT_UNIT);
            return info;
        }
        catch (Exception e) {
            log.warn("[DrugAvailability] Could not parse Odoo response for drugUuid=" + drugUuid
                    + " — " + e.getMessage());
            return StockInfo.notFound();
        }
    }

    /**
     * Authenticates against Odoo using JSON-RPC and returns the session_id, the same way
     * ConsultationFeeOdooService and BedOrderOdooService do for their own Odoo calls.
     */
    private String authenticate(OkHttpClient client) throws IOException {
        String authUrl = getOdooBaseUrl() + "/web/session/authenticate";
        String authBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"method\":\"call\","
                        + "\"params\":{\"db\":\"%s\",\"login\":\"%s\",\"password\":\"%s\"}}",
                getOdooDb(), getOdooLogin(), getOdooPassword());

        Request authRequest = new Request.Builder()
                .url(authUrl)
                .post(okhttp3.RequestBody.create(authBody, okhttp3.MediaType.parse("application/json; charset=utf-8")))
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(authRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Odoo authentication failed — HTTP " + response.code());
            }

            String sessionId = extractSessionIdFromCookie(response);
            if (sessionId == null || sessionId.isEmpty()) {
                throw new IOException("Odoo auth response contains no session_id");
            }
            return sessionId;
        }
    }

    private String extractSessionIdFromCookie(Response response) {
        for (String cookie : response.headers("Set-Cookie")) {
            for (String part : cookie.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("session_id=")) {
                    String value = trimmed.substring("session_id=".length()).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }
}
