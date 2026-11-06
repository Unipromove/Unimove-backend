package com.unimove.domain.ride.dto;

import com.unimove.domain.user.dto.DriverPublicInfo;
import com.unimove.domain.user.dto.PassengerPublicInfo;

import java.math.BigDecimal;

/**
 * Quem esta na corrida, do jeito que o outro lado precisa ver: o passageiro
 * reconhece o carro; o motorista sabe quem embarca. Os dados do motorista so
 * existem depois do aceite. Os do passageiro so sao enviados com a corrida
 * aceita (nunca no mural).
 */
public record RideParticipants(
        String motoristaNome,
        String veiculoMarca,
        String veiculoModelo,
        String veiculoCor,
        String veiculoPlaca,
        BigDecimal motoristaRatingAvg,
        Integer motoristaRatingCount,
        String passageiroNome,
        BigDecimal passageiroRatingAvg,
        Integer passageiroRatingCount
) {
    public static final RideParticipants NONE = of(null, null);

    public static RideParticipants of(DriverPublicInfo driver, PassengerPublicInfo passenger) {
        return new RideParticipants(
                driver == null ? null : driver.firstName(),
                driver == null ? null : driver.vehicleBrand(),
                driver == null ? null : driver.vehicleModel(),
                driver == null ? null : driver.vehicleColor(),
                driver == null ? null : driver.vehiclePlate(),
                driver == null ? null : driver.ratingAvg(),
                driver == null ? null : driver.ratingCount(),
                passenger == null ? null : passenger.firstName(),
                passenger == null ? null : passenger.ratingAvg(),
                passenger == null ? null : passenger.ratingCount());
    }
}
