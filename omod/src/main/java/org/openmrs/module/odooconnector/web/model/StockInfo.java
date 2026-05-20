package org.openmrs.module.odooconnector.web.model;

public class StockInfo {

    public enum StockStatus {
        AVAILABLE, LOW, OUT, UNAVAILABLE
    }

    private String drugCode;
    private String drugName;
    private double quantity;
    private String unit;
    private double lowStockThreshold;
    private StockStatus status;
    private boolean available;

    public StockInfo() {}

    public StockInfo(StockStatus status, boolean available) {
        this.status = status;
        this.available = available;
        this.quantity = 0;
        this.unit = "units";
        this.lowStockThreshold = 10;
    }

    public String getDrugCode() { return drugCode; }
    public void setDrugCode(String drugCode) { this.drugCode = drugCode; }

    public String getDrugName() { return drugName; }
    public void setDrugName(String drugName) { this.drugName = drugName; }

    public double getQuantity() { return quantity; }
    public void setQuantity(double quantity) { this.quantity = quantity; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public double getLowStockThreshold() { return lowStockThreshold; }
    public void setLowStockThreshold(double lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }

    public StockStatus getStatus() { return status; }
    public void setStatus(StockStatus status) { this.status = status; }

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
}
