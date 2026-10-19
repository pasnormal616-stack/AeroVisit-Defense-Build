package com.paf.aerovisit.model;

public class MuseumPrediction {
    
    private int totalPrediction;
    private int walkIns;
    
    // Confidence metrics
    private int minWalkIns;
    private int maxWalkIns;
    private int confidencePercentage;
    // True while the AI uses its starter estimates (not enough visit history to learn from yet)
    private boolean baselineEstimate;

    // Environmental Telemetry
    private String weather;

    // Tactical Briefing
    private String securityCondition;
    private String personnel;
    private String facilityProtocol;

    // Default constructor (Required for Spring Boot / Jackson JSON parsing)
    public MuseumPrediction() {}

    // Overloaded constructor (Backwards compatible if weather is omitted)
    public MuseumPrediction(int totalPrediction, int walkIns, int minWalkIns, int maxWalkIns, int confidencePercentage, String securityCondition, String personnel, String facilityProtocol) {
        this(totalPrediction, walkIns, minWalkIns, maxWalkIns, confidencePercentage, "Clear / Nominal", securityCondition, personnel, facilityProtocol);
    }

    // Full constructor
    public MuseumPrediction(int totalPrediction, int walkIns, int minWalkIns, int maxWalkIns, int confidencePercentage, String weather, String securityCondition, String personnel, String facilityProtocol) {
        this.totalPrediction = totalPrediction;
        this.walkIns = walkIns;
        this.minWalkIns = minWalkIns;
        this.maxWalkIns = maxWalkIns;
        this.confidencePercentage = confidencePercentage;
        this.weather = weather;
        this.securityCondition = securityCondition;
        this.personnel = personnel;
        this.facilityProtocol = facilityProtocol;
    }

    // Getters
    public int getTotalPrediction() { return totalPrediction; }
    public int getWalkIns() { return walkIns; }
    public int getMinWalkIns() { return minWalkIns; }
    public int getMaxWalkIns() { return maxWalkIns; }
    public int getConfidencePercentage() { return confidencePercentage; }
    public boolean isBaselineEstimate() { return baselineEstimate; }
    public String getWeather() { return weather; }
    public String getSecurityCondition() { return securityCondition; }
    public String getPersonnel() { return personnel; }
    public String getFacilityProtocol() { return facilityProtocol; }

    // Setters
    public void setTotalPrediction(int totalPrediction) { this.totalPrediction = totalPrediction; }
    public void setWalkIns(int walkIns) { this.walkIns = walkIns; }
    public void setMinWalkIns(int minWalkIns) { this.minWalkIns = minWalkIns; }
    public void setMaxWalkIns(int maxWalkIns) { this.maxWalkIns = maxWalkIns; }
    public void setConfidencePercentage(int confidencePercentage) { this.confidencePercentage = confidencePercentage; }
    public void setBaselineEstimate(boolean baselineEstimate) { this.baselineEstimate = baselineEstimate; }
    public void setWeather(String weather) { this.weather = weather; }
    public void setSecurityCondition(String securityCondition) { this.securityCondition = securityCondition; }
    public void setPersonnel(String personnel) { this.personnel = personnel; }
    public void setFacilityProtocol(String facilityProtocol) { this.facilityProtocol = facilityProtocol; }
}