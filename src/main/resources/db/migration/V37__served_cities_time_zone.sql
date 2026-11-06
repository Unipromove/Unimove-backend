-- ============================================================================
-- V37: Fuso horario de cada cidade atendida
-- ============================================================================
-- Ganhos do motorista e series diarias cortavam o dia em UTC: corrida
-- concluida apos as 21h (BRT) caia no dia seguinte. O "dia" passa a ser o
-- local da cidade. O Brasil tem cidades em UTC-3 e UTC-4, entao o fuso e
-- dado da cidade, e nao da aplicacao.
-- ============================================================================

ALTER TABLE served_cities
    ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'America/Sao_Paulo';

-- Cidades da Bahia (slug "-ba", ex.: remanso-ba) usam o fuso IANA do estado.
UPDATE served_cities SET time_zone = 'America/Bahia' WHERE slug LIKE '%-ba';
