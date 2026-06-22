package org.openmrs.module.odooconnector.web.model;

public class StockInfo {

    private String drugUuid;
    private String drugName;
    private boolean available;
    private double quantityAvailable;
    private String unit;
    private String error;

    public StockInfo() {}

    public static StockInfo notFound() {
        StockInfo info = new StockInfo();
        info.setAvailable(false);
        info.setQuantityAvailable(0);
        info.setError("Drug mapping not found");
        return info;
    }

    public String getDrugUuid() { return drugUuid; }
    public void setDrugUuid(String drugUuid) { this.drugUuid = drugUuid; }

    public String getDrugName() { return drugName; }
    public void setDrugName(String drugName) { this.drugName = drugName; }

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    public double getQuantityAvailable() { return quantityAvailable; }
    public void setQuantityAvailable(double quantityAvailable) { this.quantityAvailable = quantityAvailable; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
