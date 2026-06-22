package org.openmrs.module.odooconnector.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.odooconnector.web.model.StockInfo;
import org.openmrs.module.odooconnector.web.service.OdooStockService;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * REST endpoint: GET /openmrs/ws/rest/v1/odooconnector/stock?drugUuid=<uuid>
 *
 * Returns the exact available stock quantity for a drug, sourced from Odoo's
 * /api/bdsec/available-quantity/{drugUuid}. The OpenMRS drug UUID is passed straight through —
 * Odoo's product master data is mapped 1:1 to it via product_uuid.
 */
@Controller
@RequestMapping("/rest/v1/odooconnector/stock")
public class StockController {

    private static final Log log = LogFactory.getLog(StockController.class);

    @Autowired
    private OdooStockService odooStockService;

    @RequestMapping(method = RequestMethod.GET)
    public @ResponseBody SimpleObject getStock(@RequestParam(value = "drugUuid") String drugUuid) {

        StockInfo info;
        try {
            info = odooStockService.getAvailableQuantity(drugUuid);
        }
        catch (Exception e) {
            log.warn("[DrugAvailability] Lookup failed for drugUuid=" + drugUuid + " — " + e.getMessage());
            info = StockInfo.notFound();
        }

        SimpleObject result = new SimpleObject();
        if (info.getError() != null) {
            result.put("available", info.isAvailable());
            result.put("quantityAvailable", info.getQuantityAvailable());
            result.put("error", info.getError());
        }
        else {
            result.put("drugUuid", info.getDrugUuid());
            result.put("drugName", info.getDrugName());
            result.put("available", info.isAvailable());
            result.put("quantityAvailable", info.getQuantityAvailable());
            result.put("unit", info.getUnit());
        }
        return result;
    }
}
