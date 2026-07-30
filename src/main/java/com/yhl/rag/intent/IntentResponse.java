package com.yhl.rag.intent;

public class IntentResponse {

    private String intent;

    private Double confidence;

    public IntentResponse() {
    }

    public IntentResponse(String intent, Double confidence) {
        this.intent = intent;
        this.confidence = confidence;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }
}
