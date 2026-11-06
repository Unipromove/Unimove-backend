package com.unimove.domain.ride.dto;

import java.math.BigDecimal;

public record EarningsAggregate(
        long totalRides,
        BigDecimal grossEarnings,
        BigDecimal cashReceived,
        BigDecimal cashChangeReturned,
        BigDecimal cashPending,
        Long cashDivergentRides,
        BigDecimal pixSimulated
) {}
