package org.openmrs.module.odooconnector.web.service;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.context.Context;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatus;
import org.openmrs.module.odooconnector.OdooBillingPaymentStatusDTO;
import org.openmrs.module.odooconnector.api.OdooBillingPaymentStatusService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Processes incoming billing orders pushed from Odoo.
 *
 * Responsibilities:
 *   - Validates Bearer-token / API-key authentication against Global Properties
 *   - Resolves patient_uuid and visit_uuid to OpenMRS integer IDs
 *   - Fans out the services[] array into one OdooBillingPaymentStatus record each
 *   - Enforces idempotency: a duplicate sale_id returns the original response immediately
 *
 * Global Properties consumed:
 *   odooconnector.billing.inboundBearerToken  — expected value of "Bearer <token>"
 *   odooconnector.billing.inboundApiKey       — expected value of "x-api-key" header
 */
@Service("odooconnector.BillingOrderService")
public class BillingOrderService {

    protected final Log log = LogFactory.getLog(getClass());

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    public boolean isAuthenticated(String authHeader, String apiKey) {
        String allowedToken = gp("odooconnector.billing.inboundBearerToken");
        String allowedApiKey = gp("odooconnector.billing.inboundApiKey");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            boolean ok = !token.isEmpty() && token.equals(allowedToken);
            if (!ok) {
                log.warn("[BillingOrder] Auth FAILED — invalid Bearer token (header present)");
            }
            return ok;
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            boolean ok = apiKey.equals(allowedApiKey);
            if (!ok) {
                log.warn("[BillingOrder] Auth FAILED — invalid x-api-key");
            }
            return ok;
        }
        log.warn("[BillingOrder] Auth FAILED — no Authorization header or x-api-key present");
        return false;
    }

    // -------------------------------------------------------------------------
    // Main processing
    // -------------------------------------------------------------------------

    public SimpleObject processBillingOrder(Map<String, Object> body, HttpServletResponse httpResponse) {

        // --- Required field extraction ---
        Object saleIdObj   = body.get("sale_id");
        String patientUuid = (String) body.get("patient_uuid");
        String visitUuid   = (String) body.get("visit_uuid");

        if (saleIdObj == null || patientUuid == null || visitUuid == null
                || patientUuid.isEmpty() || visitUuid.isEmpty()) {
            log.warn("[BillingOrder] Rejected — sale_id, patient_uuid, and visit_uuid are all required");
            httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return errorResponse("sale_id, patient_uuid, and visit_uuid are required");
        }

        int    saleId    = ((Number) saleIdObj).intValue();
        String saleIdStr = String.valueOf(saleId);
        String saleName  = nvl((String) body.get("sale_name"), saleIdStr);

        log.info("[BillingOrder] Received — sale_id=" + saleId + " sale_name=" + saleName
                + " patient_uuid=" + patientUuid + " visit_uuid=" + visitUuid);

        // --- Idempotency: if we have already stored a record for this sale_id, return early ---
        OdooBillingPaymentStatus existing =
                Context.getService(OdooBillingPaymentStatusService.class)
                       .getFirstByServiceReferenceId(saleIdStr);
        if (existing != null) {
            log.info("[BillingOrder] Duplicate sale_id=" + saleId
                    + " — returning cached response (record id=" + existing.getId() + ")");
            return successResponse(saleId, existing.getId(), existing.getUpdatedAt(),
                    "Billing order already processed (duplicate sale_id)");
        }

        // --- Resolve patient UUID → integer ID ---
        // Proxy privileges are required because the request authenticates via a custom
        // Bearer token, not an OpenMRS session, so the thread has no user context.
        Context.addProxyPrivilege("Get Patients");
        Context.addProxyPrivilege("Get Visits");
        Patient patient;
        Visit   visit;
        try {
            patient = Context.getPatientService().getPatientByUuid(patientUuid);
            visit   = Context.getVisitService().getVisitByUuid(visitUuid);
        } finally {
            Context.removeProxyPrivilege("Get Patients");
            Context.removeProxyPrivilege("Get Visits");
        }

        if (patient == null) {
            log.warn("[BillingOrder] Patient not found — uuid=" + patientUuid);
            httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return errorResponse("Patient not found for patient_uuid=" + patientUuid);
        }
        if (visit == null) {
            log.warn("[BillingOrder] Visit not found — uuid=" + visitUuid);
            httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return errorResponse("Visit not found for visit_uuid=" + visitUuid);
        }

        // --- Extract financial / metadata fields ---
        String      paymentStatus = nvl((String) body.get("payment_status"), "PENDING").toUpperCase();
        String      currency      = nvl((String) body.get("currency"), "ETB");
        String      customerType  = nvl((String) body.get("customer_type"), "");
        BigDecimal  amountDue     = toBigDecimal(body.get("amountDue"));
        BigDecimal  amountPaid    = toBigDecimal(body.get("amountPaid"));
        Date        paymentDate   = parseDate((String) body.get("payment_date"));
        Date        syncTs        = new Date();

        log.info("[BillingOrder] Mapped — patientId=" + patient.getId()
                + " visitId=" + visit.getId()
                + " paymentStatus=" + paymentStatus
                + " amountDue=" + amountDue
                + " amountPaid=" + amountPaid
                + " currency=" + currency
                + " customerType=" + customerType);

        // --- Fan out services[] into one record per service type ---
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services =
                (List<Map<String, Object>>) body.getOrDefault("services", Collections.emptyList());
        if (services.isEmpty()) {
            services = Collections.singletonList(Collections.singletonMap("serviceType", "CONSULTATION"));
        }

        int firstRecordId = -1;
        OdooBillingPaymentStatusService svc =
                Context.getService(OdooBillingPaymentStatusService.class);

        for (Map<String, Object> serviceItem : services) {
            String rawType   = (String) serviceItem.getOrDefault("serviceType", "CONSULTATION");
            String svcType   = rawType.trim().toUpperCase();

            OdooBillingPaymentStatusDTO dto = new OdooBillingPaymentStatusDTO();
            dto.setPatientId(patient.getId());
            dto.setVisitId(visit.getId());
            dto.setServiceType(svcType);
            dto.setServiceReferenceId(saleIdStr);   // used for idempotency
            dto.setOdooInvoiceId(saleName);
            dto.setPaymentStatus(paymentStatus);
            dto.setAmountDue(amountDue);
            dto.setAmountPaid(amountPaid);
            dto.setCurrency(currency);
            dto.setPaymentDate(paymentDate);
            dto.setOdooSyncTimestamp(syncTs);

            OdooBillingPaymentStatus saved = svc.saveOrUpdatePaymentStatus(dto);
            log.info("[BillingOrder] Saved record — id=" + saved.getId()
                    + " serviceType=" + svcType + " status=" + paymentStatus);

            if (firstRecordId < 0) {
                firstRecordId = saved.getId();
            }
        }

        return successResponse(saleId, firstRecordId, syncTs, "Billing order processed successfully");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SimpleObject successResponse(int saleId, int recordId, Date processedAt, String message) {
        SimpleObject r = new SimpleObject();
        r.put("status", "success");
        r.put("message", message);
        r.put("external_sale_id", saleId);
        r.put("internal_billing_id", "BILL-" + String.format("%06d", recordId));
        r.put("processed_at", processedAt != null
                ? new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(processedAt)
                : Instant.now().toString());
        return r;
    }

    private SimpleObject errorResponse(String message) {
        SimpleObject r = new SimpleObject();
        r.put("status", "error");
        r.put("message", message);
        return r;
    }

    private String gp(String property) {
        String val = Context.getAdministrationService().getGlobalProperty(property);
        return (val != null) ? val.trim() : "";
    }

    private String nvl(String value, String fallback) {
        return (value != null && !value.isEmpty()) ? value : fallback;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) { return BigDecimal.ZERO; }
        if (value instanceof Number) { return new BigDecimal(value.toString()); }
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private Date parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) { return null; }
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            try { return new SimpleDateFormat(pattern).parse(dateStr); }
            catch (ParseException ignored) {}
        }
        log.warn("[BillingOrder] Could not parse date string: " + dateStr);
        return null;
    }
}
