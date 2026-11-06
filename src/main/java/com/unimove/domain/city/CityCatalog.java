package com.unimove.domain.city;

import com.unimove.domain.city.dto.CityItem;

import java.time.ZoneId;
import java.util.List;

/**
 * Contrato publico do dominio de cidades — o unico que os outros dominios
 * importam (ver "regra dura" do CLAUDE.md). Mantem a entidade {@code ServedCity}
 * privada ao pacote e permite quebrar isto num servico proprio depois.
 */
public interface CityCatalog {

    /**
     * Garante que o slug corresponde a uma cidade atendida e ativa.
     *
     * @throws CityNotServedException se a cidade nao existe ou foi desativada
     */
    void assertServed(String slug);

    /** Cidades abertas, ordenadas por nome de exibicao — alimenta o app. */
    List<CityItem> listActive();

    /**
     * Fuso da cidade, que define o "dia" de ganhos e relatorios. Cidade
     * desconhecida cai no {@link #defaultZone()}: o relatorio nunca quebra
     * por falta de cadastro.
     */
    ZoneId zoneOf(String slug);

    /** Fuso das visoes globais (painel do admin sem filtro de cidade). */
    ZoneId defaultZone();
}
