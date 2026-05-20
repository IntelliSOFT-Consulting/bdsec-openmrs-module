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

@Service
public class OdooStockService {

    private static final Log log = LogFactory.getLog(OdooStockService.class);

    private static final StockInfo UNAVAILABLE = new StockInfo(StockInfo.StockStatus.UNAVAILABLE, false);

    private String getGlobalProperty(String key, String defaultValue) {
        try {
            String value = Context.getAdministrationService().getGlobalProperty(key);
            return (value != null && !value.isEmpty()) ? value : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public StockInfo getStock(String drugName, String drugCode) {
        String odooUrl = getGlobalProperty("odooconnector.odooUrl", "http://localhost:8069");

        StringBuilder url = new StringBuilder(odooUrl)
                .append("/api/stock?drug_name=")
                .append(encodeParam(drugName));
        if (drugCode != null && !drugCode.isEmpty()) {
            url.append("&drug_code=").append(encodeParam(drugCode));
        }

        log.info("=== STOCK FETCH === drugName=" + drugName + " drugCode=" + drugCode);
        System.out.println("=== STOCK FETCH === drugName=" + drugName + " drugCode=" + drugCode);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(url.toString())
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Stock fetch returned non-success for drug: " + drugName);
                return UNAVAILABLE;
            }
            String body = response.body().string();
            log.info("=== STOCK RESPONSE === " + body);
            System.out.println("=== STOCK RESPONSE === " + body);
            return parseStockResponse(body, drugName, drugCode);
        } catch (IOException e) {
            log.warn("Stock fetch failed for drug: " + drugName + " — " + e.getMessage());
            System.out.println("=== STOCK FETCH FAILED === " + e.getMessage());
            return UNAVAILABLE;
        }
    }

    private StockInfo parseStockResponse(String json, String drugName, String drugCode) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            StockInfo info = new StockInfo();
            info.setDrugName(drugName);
            info.setDrugCode(drugCode);

            double qty = root.path("quantity").asDouble(0);
            String unit = root.path("unit").asText("units");
            double threshold = root.path("low_stock_threshold").asDouble(10);

            info.setQuantity(qty);
            info.setUnit(unit);
            info.setLowStockThreshold(threshold);

            if (qty == 0) {
                info.setStatus(StockInfo.StockStatus.OUT);
                info.setAvailable(false);
            } else if (qty <= threshold) {
                info.setStatus(StockInfo.StockStatus.LOW);
                info.setAvailable(true);
            } else {
                info.setStatus(StockInfo.StockStatus.AVAILABLE);
                info.setAvailable(true);
            }

            return info;
        } catch (Exception e) {
            log.warn("Failed to parse stock response: " + e.getMessage());
            return UNAVAILABLE;
        }
    }

    private String encodeParam(String value) {
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
