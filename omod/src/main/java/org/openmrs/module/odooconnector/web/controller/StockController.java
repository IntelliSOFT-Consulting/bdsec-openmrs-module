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

@Controller
@RequestMapping("/rest/v1/odooconnector/stock")
public class StockController {

    private static final Log log = LogFactory.getLog(StockController.class);

    @Autowired
    private OdooStockService odooStockService;

    @RequestMapping(method = RequestMethod.GET)
    public @ResponseBody SimpleObject getStock(
            @RequestParam(value = "drugName", required = false, defaultValue = "") String drugName,
            @RequestParam(value = "drugCode", required = false, defaultValue = "") String drugCode) {

        log.info("=== STOCK REQUEST === drugName=" + drugName + " drugCode=" + drugCode);
        System.out.println("=== STOCK REQUEST === drugName=" + drugName + " drugCode=" + drugCode);

        StockInfo info;
        try {
            info = odooStockService.getStock(drugName, drugCode);
        } catch (Exception e) {
            log.warn("Stock lookup failed, returning UNAVAILABLE: " + e.getMessage());
            info = new StockInfo(StockInfo.StockStatus.UNAVAILABLE, false);
        }

        SimpleObject result = new SimpleObject();
        result.put("drugName", info.getDrugName());
        result.put("drugCode", info.getDrugCode());
        result.put("quantity", info.getQuantity());
        result.put("unit", info.getUnit());
        result.put("low_stock_threshold", info.getLowStockThreshold());
        result.put("status", info.getStatus() != null ? info.getStatus().name() : "UNAVAILABLE");
        result.put("available", info.isAvailable());
        return result;
    }
}
