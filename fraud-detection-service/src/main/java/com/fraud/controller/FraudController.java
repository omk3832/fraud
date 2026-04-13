package com.fraud.controller;

import com.fraud.model.*;
import com.fraud.service.FraudService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * {@code POST /fraud/check} expects a payment-sized payload (txn_id, amounts, card/UPI fields, etc.).
 * The service calls DE to build model features; ML is scored from DE output only, not from sending the
 * 197-key feature map in this body, unless {@code fraud.de.enabled=false} (local profile).
 */
@RestController
@RequestMapping("/fraud")
public class FraudController {

    private final FraudService service;

    public FraudController(FraudService service) {
        this.service = service;
    }

    @PostMapping("/check")
    public Mono<FraudResponse> check(@Valid @RequestBody FraudRequest req) {
        return service.process(req);
    }
}