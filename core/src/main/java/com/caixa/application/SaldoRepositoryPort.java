package com.caixa.application;

import com.caixa.persistence.dto.GrupoSaldoDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SaldoRepositoryPort {

    List<GrupoSaldoDTO> porTipo(UUID comercianteId, LocalDate data);
}
