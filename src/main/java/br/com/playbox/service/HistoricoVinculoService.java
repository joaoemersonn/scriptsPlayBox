package br.com.playbox.service;

import br.com.playbox.dto.HistoricoVinculoDTO;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.HistoricoVinculoMaquina;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.HistoricoVinculoMaquinaRepository;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class HistoricoVinculoService {

    private static final Logger log = LoggerFactory.getLogger(HistoricoVinculoService.class);

    private final HistoricoVinculoMaquinaRepository historicoRepository;
    private final MaquinaRepository maquinaRepository;
    private final EstabelecimentoRepository estabelecimentoRepository;
    private final TransacaoRepository transacaoRepository;

    public HistoricoVinculoService(
            HistoricoVinculoMaquinaRepository historicoRepository,
            MaquinaRepository maquinaRepository,
            EstabelecimentoRepository estabelecimentoRepository,
            TransacaoRepository transacaoRepository
    ) {
        this.historicoRepository = historicoRepository;
        this.maquinaRepository = maquinaRepository;
        this.estabelecimentoRepository = estabelecimentoRepository;
        this.transacaoRepository = transacaoRepository;
    }

    /**
     * Aloca ou transfere uma máquina para um estabelecimento com data/hora de vigência.
     */
    @Transactional
    public HistoricoVinculoDTO alocarMaquina(Long maquinaId, Long estabelecimentoId, LocalDateTime dataHora, String motivo) {
        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina não encontrada para o ID: " + maquinaId));

        Estabelecimento estabelecimento = estabelecimentoRepository.findById(estabelecimentoId)
                .orElseThrow(() -> new IllegalArgumentException("Estabelecimento não encontrado para o ID: " + estabelecimentoId));

        LocalDateTime timestampInicio = (dataHora != null) ? dataHora : LocalDateTime.now();

        // Verificar se já existe um vínculo ativo para esta máquina
        Optional<HistoricoVinculoMaquina> vinculoAtivoOpt = historicoRepository
                .findFirstByMaquinaIdAndDataFimIsNullOrderByDataInicioDesc(maquinaId);

        if (vinculoAtivoOpt.isPresent()) {
            HistoricoVinculoMaquina ativo = vinculoAtivoOpt.get();
            if (ativo.getEstabelecimento().getId().equals(estabelecimentoId)) {
                log.info("Máquina {} já está alocada no estabelecimento {}. Mantendo vínculo ativo.", maquinaId, estabelecimentoId);
                if (motivo != null && !motivo.isBlank()) {
                    ativo.setMotivo(motivo);
                    historicoRepository.save(ativo);
                }
                return toDTO(ativo);
            }

            // Fechar o vínculo anterior na data informada da transferência
            ativo.setDataFim(timestampInicio);
            historicoRepository.save(ativo);
            log.info("Encerrado vínculo anterior da máquina {} no estabelecimento {} em {}.",
                    maquinaId, ativo.getEstabelecimento().getId(), timestampInicio);
        }

        // Criar novo vínculo temporal ativo
        HistoricoVinculoMaquina novoVinculo = new HistoricoVinculoMaquina(
                maquina,
                estabelecimento,
                timestampInicio,
                null,
                (motivo != null && !motivo.isBlank()) ? motivo : "Alocação no estabelecimento"
        );
        HistoricoVinculoMaquina salvo = historicoRepository.save(novoVinculo);

        // Atualizar ponteiro atual da máquina
        maquina.setEstabelecimento(estabelecimento);
        maquinaRepository.save(maquina);

        // REPROCESSAMENTO RETROATIVO AUTOMÁTICO:
        // Caso a data informada seja anterior ao momento atual, reatribui imediatamente todas as transações
        // já registradas para esta máquina a partir de timestampInicio para o novo estabelecimento.
        List<Transacao> txsRetroativas = transacaoRepository.findByMaquinaIdAndTimestampGreaterThanEqual(maquinaId, timestampInicio);
        if (!txsRetroativas.isEmpty()) {
            for (Transacao tx : txsRetroativas) {
                tx.setEstabelecimento(estabelecimento);
            }
            transacaoRepository.saveAll(txsRetroativas);
            log.info("[REPROCESSAMENTO-RETROATIVO] {} transações da máquina {} a partir de {} foram transferidas automaticamente para o estabelecimento {}.",
                    txsRetroativas.size(), maquinaId, timestampInicio, estabelecimentoId);
        }

        log.info("Máquina {} alocada com sucesso no estabelecimento {} a partir de {}.",
                maquinaId, estabelecimentoId, timestampInicio);

        return toDTO(salvo);
    }

    /**
     * Desvincula uma máquina do estabelecimento atual (retirada / para oficina / sem loja).
     */
    @Transactional
    public void desalocarMaquina(Long maquinaId, LocalDateTime dataHora, String motivo) {
        Maquina maquina = maquinaRepository.findById(maquinaId)
                .orElseThrow(() -> new IllegalArgumentException("Máquina não encontrada para o ID: " + maquinaId));

        LocalDateTime timestampFim = (dataHora != null) ? dataHora : LocalDateTime.now();

        // Encontrar vínculo ativo e fechá-lo
        Optional<HistoricoVinculoMaquina> vinculoAtivoOpt = historicoRepository
                .findFirstByMaquinaIdAndDataFimIsNullOrderByDataInicioDesc(maquinaId);

        if (vinculoAtivoOpt.isPresent()) {
            HistoricoVinculoMaquina ativo = vinculoAtivoOpt.get();
            ativo.setDataFim(timestampFim);
            if (motivo != null && !motivo.isBlank()) {
                ativo.setMotivo(motivo);
            }
            historicoRepository.save(ativo);
            log.info("Vínculo da máquina {} no estabelecimento {} encerrado em {}.",
                    maquinaId, ativo.getEstabelecimento().getId(), timestampFim);
        }

        // Remover ponteiro da máquina
        maquina.setEstabelecimento(null);
        maquinaRepository.save(maquina);

        // REPROCESSAMENTO RETROATIVO AUTOMÁTICO NA DESVINCULAÇÃO:
        // Se a máquina foi retirada retroativamente, as transações a partir da data da retirada
        // não pertencem mais àquele estabelecimento.
        List<Transacao> txsRetroativas = transacaoRepository.findByMaquinaIdAndTimestampGreaterThanEqual(maquinaId, timestampFim);
        if (!txsRetroativas.isEmpty()) {
            for (Transacao tx : txsRetroativas) {
                tx.setEstabelecimento(null);
            }
            transacaoRepository.saveAll(txsRetroativas);
            log.info("[REPROCESSAMENTO-RETROATIVO] {} transações da máquina {} a partir de {} foram desvinculadas do estabelecimento por retirada.",
                    txsRetroativas.size(), maquinaId, timestampFim);
        }
    }

    /**
     * Localiza qual estabelecimento a máquina pertencia no timestamp da transação.
     */
    @Transactional(readOnly = true)
    public Estabelecimento findEstabelecimentoVigente(Long maquinaId, LocalDateTime timestamp) {
        if (maquinaId == null || timestamp == null) {
            return null;
        }

        List<HistoricoVinculoMaquina> lista = historicoRepository.findVigenteNoMomento(maquinaId, timestamp);
        if (!lista.isEmpty()) {
            return lista.get(0).getEstabelecimento();
        }

        return null;
    }

    /**
     * Lista o histórico completo de vínculos de uma máquina em ordem cronológica reversa.
     */
    @Transactional(readOnly = true)
    public List<HistoricoVinculoDTO> getHistoricoByMaquina(Long maquinaId) {
        return historicoRepository.findByMaquinaIdOrderByDataInicioDesc(maquinaId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Lista o histórico completo de máquinas que passaram por um estabelecimento.
     */
    @Transactional(readOnly = true)
    public List<HistoricoVinculoDTO> getHistoricoByEstabelecimento(Long estabelecimentoId) {
        return historicoRepository.findByEstabelecimentoIdOrderByDataInicioDesc(estabelecimentoId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Varre transações que estão sem estabelecimento e preenche automaticamente
     * utilizando a tabela de histórico de vigência temporal.
     */
    @Transactional
    public int reassociarTransacoesOrfas(Long maquinaId) {
        List<Transacao> orfas = transacaoRepository.findAll().stream()
                .filter(t -> t.getEstabelecimento() == null)
                .filter(t -> maquinaId == null || (t.getMaquina() != null && t.getMaquina().getId().equals(maquinaId)))
                .collect(Collectors.toList());

        int count = 0;
        for (Transacao tx : orfas) {
            if (tx.getMaquina() == null || tx.getTimestamp() == null) continue;

            Estabelecimento est = findEstabelecimentoVigente(tx.getMaquina().getId(), tx.getTimestamp());
            if (est == null && tx.getMaquina().getEstabelecimento() != null) {
                est = tx.getMaquina().getEstabelecimento();
            }

            if (est != null) {
                tx.setEstabelecimento(est);
                count++;
            }
        }

        if (count > 0) {
            transacaoRepository.saveAll(orfas);
            log.info("[HISTORICO-VINCULO] {} transações órfãs foram associadas automaticamente.", count);
        }

        return count;
    }

    public HistoricoVinculoDTO toDTO(HistoricoVinculoMaquina h) {
        if (h == null) return null;
        Maquina m = h.getMaquina();
        Estabelecimento e = h.getEstabelecimento();

        String maquinaNome = (m != null)
                ? (m.getNomeIntegracao() != null ? m.getNomeIntegracao() : ("Máquina #" + m.getId()))
                : "Desconhecida";

        return new HistoricoVinculoDTO(
                h.getId(),
                m != null ? m.getId() : null,
                maquinaNome,
                e != null ? e.getId() : null,
                e != null ? e.getNome() : "Desconhecido",
                e != null ? e.getCidade() : "",
                h.getDataInicio(),
                h.getDataFim(),
                h.getMotivo(),
                h.isAtivo()
        );
    }
}
