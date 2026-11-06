package com.unimove.domain.ride.dto;

import com.unimove.domain.ride.CancelledBy;
import com.unimove.domain.ride.PaymentMethod;
import com.unimove.domain.ride.PaymentStatus;
import com.unimove.domain.ride.Ride;
import com.unimove.domain.ride.RideCategory;
import com.unimove.domain.ride.RideStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RideResponse(
        UUID id,
        UUID passageiroId,
        UUID motoristaId,
        String cidade,
        BigDecimal latOrigem,
        BigDecimal lngOrigem,
        BigDecimal latDestino,
        BigDecimal lngDestino,
        String origemEndereco,
        String destinoEndereco,
        List<StopPoint> stops,
        BigDecimal distanciaKm,
        Integer tempoMin,
        BigDecimal preco,
        BigDecimal surgeMultiplier,
        RideCategory category,
        RideStatus status,
        PaymentMethod paymentMethod,
        String pixPayload,
        PaymentStatus paymentStatus,
        BigDecimal cashChangeFor,
        BigDecimal cashReceivedAmount,
        Instant paymentConfirmedAt,
        String paymentNote,
        BigDecimal driverCurrentLat,
        BigDecimal driverCurrentLng,
        Instant driverLocationUpdatedAt,
        BigDecimal driverDistanceKm,
        Integer pickupEtaMin,
        String motoristaNome,
        String veiculoMarca,
        String veiculoModelo,
        String veiculoCor,
        String veiculoPlaca,
        BigDecimal motoristaRatingAvg,
        Integer motoristaRatingCount,
        String passageiroNome,
        BigDecimal passageiroRatingAvg,
        Integer passageiroRatingCount,
        Instant createdAt,
        Instant acceptedAt,
        Instant startedAt,
        Instant completedAt,
        Instant expiredAt,
        Instant cancelledAt,
        CancelledBy cancelledBy,
        String cancelReason,
        BigDecimal cancellationFee
) {
    public static RideResponse from(Ride r, BigDecimal driverDistanceKm, RideParticipants p) {
        List<StopPoint> stops = r.getStops().stream()
                .map(s -> new StopPoint(s.getLat(), s.getLng()))
                .toList();
        return new RideResponse(
                r.getId(),
                r.getPassageiroId(),
                r.getMotoristaId(),
                r.getCidade(),
                r.getLatOrigem(),
                r.getLngOrigem(),
                r.getLatDestino(),
                r.getLngDestino(),
                r.getOrigemEndereco(),
                r.getDestinoEndereco(),
                stops,
                r.getDistanciaKm(),
                r.getTempoMin(),
                r.getPreco(),
                r.getSurgeMultiplier(),
                r.getCategory(),
                r.getStatus(),
                r.getPaymentMethod(),
                r.getPixPayload(),
                r.getPaymentStatus(),
                r.getCashChangeFor(),
                r.getCashReceivedAmount(),
                r.getPaymentConfirmedAt(),
                r.getPaymentNote(),
                r.getDriverCurrentLat(),
                r.getDriverCurrentLng(),
                r.getDriverLocationUpdatedAt(),
                driverDistanceKm,
                r.getPickupEtaMin(),
                p.motoristaNome(),
                p.veiculoMarca(),
                p.veiculoModelo(),
                p.veiculoCor(),
                p.veiculoPlaca(),
                p.motoristaRatingAvg(),
                p.motoristaRatingCount(),
                p.passageiroNome(),
                p.passageiroRatingAvg(),
                p.passageiroRatingCount(),
                r.getCreatedAt(),
                r.getAcceptedAt(),
                r.getStartedAt(),
                r.getCompletedAt(),
                r.getExpiredAt(),
                r.getCancelledAt(),
                r.getCancelledBy(),
                r.getCancelReason(),
                r.getCancellationFee()
        );
    }
}
