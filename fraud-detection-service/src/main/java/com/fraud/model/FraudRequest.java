package com.fraud.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.HashMap;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class FraudRequest {

    // 🔹 Core fields (almost always present)
    private String memberuid;
    @NotNull
    private String txn_id;
    private Double txn_amount;
    private String txn_type;
    private String created_at;

    // 🔹 Payment-related (optional)
    private String payment_mode;
    private String card_type;
    private String card_ownership;
    private String bank_name;

    // 🔹 Device / network (optional)
    private String ip_address;
    private String device_id;

    // 🔹 UPI (optional)
    private String payer_vpa;
    private String receiver_vpa;

    //  Catch-all for future unknown fields (inbound via @JsonAnySetter, outbound merged at root via @JsonAnyGetter)
    @Getter(AccessLevel.NONE)
    private Map<String, Object> extraFields = new HashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getExtraFields() {
        return extraFields;
    }

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void setExtra(String key, Object value) {
        extraFields.put(key, value);
    }
}