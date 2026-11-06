package com.unimove.domain.user.dto;

import java.math.BigDecimal;

public record PassengerPublicInfo(
        String firstName,
        BigDecimal ratingAvg,
        Integer ratingCount
) {}
