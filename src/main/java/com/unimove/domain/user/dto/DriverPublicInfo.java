package com.unimove.domain.user.dto;

import com.unimove.domain.user.VehicleType;

import java.math.BigDecimal;

/** Dados do motorista que o passageiro precisa para reconhecer o veiculo. */
public record DriverPublicInfo(
        String firstName,
        VehicleType vehicleType,
        String vehiclePlate,
        String vehicleBrand,
        String vehicleModel,
        String vehicleColor,
        BigDecimal ratingAvg,
        Integer ratingCount
) {}
