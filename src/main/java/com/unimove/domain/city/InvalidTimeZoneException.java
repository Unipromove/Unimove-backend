package com.unimove.domain.city;

import com.unimove.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

/** Fuso que nao e um identificador IANA valido (ex.: "Bahia"). */
public class InvalidTimeZoneException extends BusinessException {
    public InvalidTimeZoneException() {
        super(HttpStatus.BAD_REQUEST, "Fuso horário inválido. Use o formato IANA, ex.: America/Bahia.");
    }
}
