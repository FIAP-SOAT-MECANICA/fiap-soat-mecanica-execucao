package br.com.fiap.soat.mecanica.consultas;

import java.util.List;

public interface ConsultaExecucaoRepository {
    FilaPage listarFila(int limite, String cursor);
    List<TempoMedioServico> temposMedios();
}
